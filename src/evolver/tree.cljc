(ns evolver.tree
  (:require [datascript.core :as d]))

;; Constants
(def temp-position-offset -1000000)
(def cycle-prevention-position -999999999)

(def schema
  {:id         {:db/unique :db.unique/identity}
   :parent     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :position   {:db/cardinality :db.cardinality/one :db/index true}
   :references {:db/valueType   :db.type/ref
                :db/cardinality :db.cardinality/many}})

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

(defn reorder-siblings [conn siblings]
  (d/transact! conn (mapv #(vector :db/add [:id %2] :position %1) (range) siblings)))

(defn- reorder-children! [conn parent-id ids]
  (let [temp-positions (map-indexed (fn [i id] [:db/add [:id id] :position (- temp-position-offset i)]) ids)
        final-positions (map-indexed (fn [i id] [:db/add [:id id] :position i]) ids)]
    (d/transact! conn temp-positions)
    (d/transact! conn final-positions)))

(defn generate-entity-id []
  (str "auto-" (swap! id-generator inc)))

(defn- get-parent-id [db entity-id]
  (d/q '[:find ?p-id . :in $ ?e-id :where [?e :id ?e-id] [?e :parent ?p] [?p :id ?p-id]] db entity-id))

(defn insert! [conn entity pos]
  (let [db @conn
        entity-id (or (:id entity) (generate-entity-id))
        [parent-id position-index] (resolve-insertion-position db pos)
        temp-id (- (swap! id-generator inc))
        entity-tx (-> entity
                      (assoc :db/id temp-id :id entity-id :parent [:id parent-id] :position temp-id)
                      (dissoc :children))
        existing-children (get-child-ids db parent-id)
        reordered-children (insert-at-position (vec existing-children) position-index entity-id)]
    (d/transact! conn [entity-tx])
    (reorder-children! conn parent-id reordered-children)
    entity-id))

(defn move! [conn entity-id pos]
  (let [db @conn
        [destination-parent-id insertion-index] (resolve-insertion-position db pos)]
    (when (contains? (set (get-descendant-ids db entity-id)) destination-parent-id)
      (throw (ex-info "Cycle detected" {:entity entity-id :target destination-parent-id})))
    (let [current-parent-id (get-parent-id db entity-id)
          same-parent? (= current-parent-id destination-parent-id)]
      (if same-parent?
        ;; Reorder within the same parent
        (let [existing-children (get-child-ids db current-parent-id)
              reordered-children (-> (vec (remove #{entity-id} existing-children))
                                     (insert-at-position insertion-index entity-id))]
          (reorder-children! conn current-parent-id reordered-children))
        ;; Move to a different parent
        (let [current-siblings (get-child-ids db current-parent-id)
              target-siblings (get-child-ids db destination-parent-id)
              updated-current-siblings (vec (remove #{entity-id} current-siblings))
              updated-target-siblings (insert-at-position (vec target-siblings) insertion-index entity-id)]
          (d/transact! conn [[:db/add [:id entity-id] :parent [:id destination-parent-id]]
                              [:db/add [:id entity-id] :position cycle-prevention-position]])
          (reorder-children! conn current-parent-id updated-current-siblings)
          (reorder-children! conn destination-parent-id updated-target-siblings))))))

(defn delete! [conn entity-id]
  (let [db @conn
        parent-id (get-parent-id db entity-id)
        descendants (get-descendant-ids db entity-id)
        deletion-txs (->> (cons entity-id descendants)
                          (map #(vector :db/retractEntity [:id %])))]
    (d/transact! conn deletion-txs)
    (when parent-id
      (reorder-children! conn parent-id (vec (remove #{entity-id} (get-child-ids db parent-id)))))))

(defn update! [conn entity-id attrs]
  (when (some #{:parent :position :id} (keys attrs))
    (throw (ex-info "Cannot modify structural attributes" {:attrs (select-keys attrs [:parent :position :id])})))
  (let [tx (reduce-kv
             (fn [acc k v]
               (if (= k :references)
                 (into acc (map (fn [ref] [:db/add [:id entity-id] k ref]) v))
                 (conj acc [:db/add [:id entity-id] k v])))
             []
             attrs)]
    (d/transact! conn tx)))

