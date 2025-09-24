(ns kernel
  (:require [datascript.core :as d]))

;; Constants
(def temp-pos-base -1000000)
(def cycle-detect-pos -999999999)

(def schema
  {:id         {:db/unique :db.unique/identity}
   :parent     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :pos        {:db/cardinality :db.cardinality/one :db/index true}
   :references {:db/valueType   :db.type/ref
                :db/cardinality :db.cardinality/many}})

(def rules
  '[[(subtree-member ?a ?d) [?d :parent ?a]]
    [(subtree-member ?a ?d) [?d :parent ?m] (subtree-member ?a ?m)]])

(defn entity-by-id [db id]
  (d/entity db [:id id]))

(defn children-ids [db parent-id]
  (->> (d/q '[:find ?id ?p :in $ ?parent-lookup-ref
              :where [?c :parent ?parent-lookup-ref] [?c :id ?id] [?c :pos ?p]]
            db [:id parent-id])
       (sort-by second)
       (mapv first)))

(defn splice [v idx x]
  (let [i (min (max 0 (or idx (count v))) (count v))]
    (into (subvec v 0 i) (cons x (subvec v i)))))

(defn- find-sibling-index [siblings sibling]
  (let [idx (.indexOf siblings sibling)]
    (if (neg? idx) (count siblings) idx)))

(defn resolve-position [db {:keys [parent sibling rel idx] :as pos}]
  (let [parent-id (or parent
                      (->> (entity-by-id db sibling) :parent :id)
                      (throw (ex-info "No parent specified" {:position pos})))
        children-ids (children-ids db parent-id)]
    [parent-id
     (case rel
       :first 0
       :last (count children-ids)
       :before (find-sibling-index children-ids sibling)
       :after (inc (find-sibling-index children-ids sibling))
       (min (or idx (count children-ids)) (count children-ids)))]))

(def auto-id-counter (atom 0))

(defn find-descendants [db entity-id]
  (d/q '[:find [?id ...] :in $ % ?p :where (subtree-member ?p ?d) [?d :id ?id]] db rules [:id entity-id]))

(defn update-sibling-positions [conn siblings]
  (d/transact! conn (mapv #(vector :db/add [:id %2] :pos %1) (range) siblings)))

(defn- reorder! [conn parent-id ids]
  (let [temp-positions (map-indexed (fn [i id] [:db/add [:id id] :pos (- temp-pos-base i)]) ids)
        final-positions (map-indexed (fn [i id] [:db/add [:id id] :pos i]) ids)]
    (d/transact! conn temp-positions)
    (d/transact! conn final-positions)))

(defn generate-auto-id []
  (str "auto-" (swap! auto-id-counter inc)))

(defn- parent-id-query [db entity-id]
  (d/q '[:find ?p-id . :in $ ?e-id :where [?e :id ?e-id] [?e :parent ?p] [?p :id ?p-id]] db entity-id))

(defn insert! [conn entity pos]
  (let [db @conn
        entity-id (or (:id entity) (generate-auto-id))
        [parent-id position-index] (resolve-position db pos)
        temp-id (- (swap! auto-id-counter inc))
        new-entity-tx (-> entity
                          (assoc :db/id temp-id :id entity-id :parent [:id parent-id] :pos temp-id)
                          (dissoc :children))
        current-siblings (children-ids db parent-id)
        reordered-siblings (splice (vec current-siblings) position-index entity-id)]
    (d/transact! conn [new-entity-tx])
    (reorder! conn parent-id reordered-siblings)
    entity-id))

(defn move! [conn entity-id pos]
  (let [db @conn
        [target-parent-id target-idx] (resolve-position db pos)]
    (when (contains? (set (find-descendants db entity-id)) target-parent-id)
      (throw (ex-info "Cycle detected" {:entity entity-id :target target-parent-id})))
    (let [current-parent-id (parent-id-query db entity-id)
          same-parent? (= current-parent-id target-parent-id)]
      (if same-parent?
        ;; Reorder within the same parent
        (let [siblings (children-ids db current-parent-id)
              reordered-siblings (-> (vec (remove #{entity-id} siblings))
                                     (splice target-idx entity-id))]
          (reorder! conn current-parent-id reordered-siblings))
        ;; Move to a different parent
        (let [current-siblings (children-ids db current-parent-id)
              target-siblings (children-ids db target-parent-id)
              updated-current-siblings (vec (remove #{entity-id} current-siblings))
              updated-target-siblings (splice (vec target-siblings) target-idx entity-id)]
          (d/transact! conn [[:db/add [:id entity-id] :parent [:id target-parent-id]]
                             [:db/add [:id entity-id] :pos cycle-detect-pos]])
          (reorder! conn current-parent-id updated-current-siblings)
          (reorder! conn target-parent-id updated-target-siblings))))))

(defn delete! [conn entity-id]
  (let [db @conn
        parent-id (parent-id-query db entity-id)
        descendants (find-descendants db entity-id)
        entity-retractions (->> (cons entity-id descendants)
                                (map #(vector :db/retractEntity [:id %])))]
    (d/transact! conn entity-retractions)
    (when parent-id
      (reorder! conn parent-id (vec (remove #{entity-id} (children-ids db parent-id)))))))

(defn update! [conn entity-id attrs]
  (when (some #{:parent :pos :id} (keys attrs))
    (throw (ex-info "Cannot modify structural attributes" {:attrs (select-keys attrs [:parent :pos :id])})))
  (let [tx (reduce-kv
             (fn [acc k v]
               (if (= k :references)
                 (into acc (map (fn [ref] [:db/add [:id entity-id] k ref]) v))
                 (conj acc [:db/add [:id entity-id] k v])))
             []
             attrs)]
    (d/transact! conn tx)))

