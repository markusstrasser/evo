(ns evolver.tree
  (:require [datascript.core :as d]))

(def schema
  {:id         {:db/unique :db.unique/identity}
   :parent     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :position   {:db/cardinality :db.cardinality/one :db/index true}
   :references {:db/valueType   :db.type/ref
                :db/cardinality :db.cardinality/many}})
;:in $ ?p means "expect database as first param, parent-id as second param."

(def rules
  '[[(subtree-member ?a ?d) [?d :parent ?a]]
    [(subtree-member ?a ?d) [?d :parent ?m] (subtree-member ?a ?m)]])

(defn entity-by-id [db id]
  (d/entity db [:id id]))

(defn get-child-ids [db parent-id]
  (->> (d/q '[:find ?id ?p :in $ ?parent-lookup-ref
              :where [?c :parent ?parent-lookup-ref] [?c :id ?id] [?c :position ?p]]
            db [:id parent-id])
       (sort-by second)
       (mapv first)))

(defn insert-at-position [items position-index new-item]
  (let [i (min (max 0 (or position-index (count items))) (count items))]
    (into (subvec items 0 i) (cons new-item (subvec items i)))))

(defn- get-sibling-index [siblings sibling]
  (let [idx (.indexOf siblings sibling)]
    (if (neg? idx) (count siblings) idx)))

(defn resolve-insertion-position [db {:keys [parent sibling rel idx] :as pos}]
  (let [parent-id (or parent
                      (->> (entity-by-id db sibling) :parent :id)
                      (throw (ex-info "No parent specified" {:position pos})))
        children-ids (get-child-ids db parent-id)]
    [parent-id
     (case rel
       :first 0
       :last (count children-ids)
       :before (get-sibling-index children-ids sibling)
       :after (inc (get-sibling-index children-ids sibling))
       (min (or idx (count children-ids)) (count children-ids)))]))

(def id-generator (atom 0))

(defn get-descendant-ids [db entity-id]
  (d/q '[:find [?id ...] :in $ % ?p :where (subtree-member ?p ?d) [?d :id ?id]] db rules [:id entity-id]))

(defn- reorder-siblings! [conn parent-id ordered-child-ids]
  "Single source of truth for position assignment. One transaction."
  (d/transact! conn
               (map-indexed (fn [i id] [:db/add [:id id] :position i])
                            ordered-child-ids)))

(defn- reorder-children-after-insert! [conn parent-id siblings entity-id idx]
  (let [new-order (insert-at-position siblings idx entity-id)]
    (reorder-siblings! conn parent-id new-order)))

(defn generate-entity-id []
  (str "auto-" (swap! id-generator inc)))

(defn- get-parent-id [db entity-id]
  (d/q '[:find ?p-id . :in $ ?e-id :where [?e :id ?e-id] [?e :parent ?p] [?p :id ?p-id]] db entity-id))

(defn insert! [conn entity pos]
  (let [db @conn
        entity-id (or (:id entity) (generate-entity-id))
        [parent-id idx] (resolve-insertion-position db pos)
        siblings (get-child-ids db parent-id)]
    ;; Create entity with parent ref (position will be set by reorder)
    (d/transact! conn [(-> entity
                           (assoc :id entity-id
                                  :parent [:id parent-id]
                                  :db/id -1)
                           (dissoc :children :position))])
    ;; Reorder siblings with new entity included
    (reorder-children-after-insert! conn parent-id siblings entity-id idx)
    entity-id))

(defn move! [conn entity-id pos]
  (let [db @conn
        [dest-parent-id idx] (resolve-insertion-position db pos)
        src-parent-id (get-parent-id db entity-id)]

    ;; Cycle detection
    (when (contains? (set (get-descendant-ids db entity-id)) dest-parent-id)
      (throw (ex-info "Cycle" {:entity entity-id :parent dest-parent-id})))

    (if (= src-parent-id dest-parent-id)
      ;; Same parent - just reorder
      (let [siblings (get-child-ids db src-parent-id)
            new-siblings (vec (remove #{entity-id} siblings))]
        (reorder-children-after-insert! conn src-parent-id new-siblings entity-id idx))
      ;; Different parent - update parent ref + reorder both
      (do
        (d/transact! conn [[:db/add [:id entity-id] :parent [:id dest-parent-id]]])
        ;; Reorder source siblings (without entity)
        (reorder-siblings! conn src-parent-id
                           (vec (remove #{entity-id} (get-child-ids db src-parent-id))))
        ;; Reorder dest siblings (with entity)
        (reorder-children-after-insert! conn dest-parent-id (get-child-ids db dest-parent-id) entity-id idx))))
  entity-id)

(defn delete! [conn entity-id]
  (let [db @conn
        parent-id (get-parent-id db entity-id)
        descendants (get-descendant-ids db entity-id)]
    (d/transact! conn
                 (concat
                   ;; Retract entity + descendants
                   (map #(vector :db/retractEntity [:id %]) (cons entity-id descendants))
                   ;; Reorder remaining siblings
                   (when parent-id
                     (map-indexed (fn [i id] [:db/add [:id id] :position i])
                                  (remove #{entity-id} (get-child-ids db parent-id))))))))

(defn update! [conn entity-id attrs]
  (when (some #{:parent :position :id} (keys attrs))
    (throw (ex-info "Cannot modify structural attributes" {:attrs (keys attrs)})))

  (let [db @conn
        ;; Handle references specially - need retract old + add new
        refs-tx (when-let [new-refs (:references attrs)]
                  (let [old-refs (d/q '[:find [?r ...] :in $ ?eid
                                        :where [?e :id ?eid] [?e :references ?r]]
                                      db entity-id)]
                    (concat
                      (map #(vector :db/retract [:id entity-id] :references %) old-refs)
                      (map #(vector :db/add [:id entity-id] :references %) new-refs))))
        ;; Handle regular attributes
        attrs-tx (->> (dissoc attrs :references)
                      (map (fn [[k v]] [:db/add [:id entity-id] k v])))]
    (d/transact! conn (concat attrs-tx refs-tx))))


(defn apply-op! [conn {:keys [op] :as operation}]
  (case op
    :insert (let [{:keys [entity position]} operation]
              (insert! conn entity position))
    :move (let [{:keys [entity-id position]} operation]
            (move! conn entity-id position))
    :delete (let [{:keys [entity-id]} operation]
              (delete! conn entity-id))
    :update (let [{:keys [entity-id attrs]} operation]
              (update! conn entity-id attrs))))