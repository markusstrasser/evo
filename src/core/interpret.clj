(ns core.interpret
  "Transaction interpreter with validation and issue reporting.
   
   Provides deterministic stepper that fails closed and reports 
   constructive suggestions. Always re-derives after operations."
  (:require [core.schema :as S]
            [core.ops :as ops]
            [core.db :as db]
            [malli.core :as m]))

(defn- node-exists?
  "Checks if node with id exists in database."
  [db id]
  (contains? (:nodes db) id))

(defn- parent-exists?
  "Checks if parent exists (either keyword or existing node)."
  [db parent]
  (or (keyword? parent) (node-exists? db parent)))

(defn- validate-operation
  "Validates a single operation against current database state.
   
   Returns nil if valid, or issue map with suggestions if invalid."
  [db {:keys [op id under] :as operation}]
  (cond
    (not (m/validate S/Op operation))
    {:issue :schema/invalid
     :operation operation
     :suggest []}

    (and (not= op :create-node) (not (node-exists? db id)))
    {:issue :id/missing
     :operation operation
     :suggest [{:op :create-node
                :id id
                :type :div
                :props {}
                :under (or under :_tmp)
                :at :last}]}

    (and (#{:create-node :place} op) (not (parent-exists? db under)))
    {:issue :parent/missing
     :operation operation
     :suggest []}

    :else nil))

(defn interpret
  "Interprets a transaction against a database.
   
   Returns map with:
   - :db - final database state (always derived)
   - :issues - vector of validation issues
   - :trace - vector of operation trace entries
   
   Operations that fail validation are skipped but recorded in trace."
  [db txs]
  (reduce
   (fn [{:keys [db issues trace]} operation]
     (if-let [issue (validate-operation db operation)]
       {:db db
        :issues (conj issues issue)
        :trace (conj trace {:op operation :skipped true})}
       (let [db' (-> db (ops/apply-op operation) db/derive)]
         {:db db'
          :issues issues
          :trace (conj trace {:op operation})})))
   {:db (db/derive db) :issues [] :trace []}
   txs))

(defn validate
  "Validates database invariants.
   
   Returns map with:
   - :ok? - boolean indicating if all invariants pass
   - :errors - vector of invariant violation descriptions"
  [db]
  (let [children-by-parent (:children-by-parent db)
        nodes (:nodes db)
        child->parents (reduce-kv
                        (fn [m p kids]
                          (reduce (fn [m2 id]
                                    (update m2 id (fnil conj #{}) p))
                                  m kids))
                        {}
                        children-by-parent)
        errors (concat
                 ;; Parent must exist if it's not a keyword
                (for [[p kids] children-by-parent
                      :when (not (or (keyword? p) (contains? nodes p)))]
                  {:issue :parent/unknown :parent p})

                 ;; No duplicate children within a parent
                (for [[p kids] children-by-parent
                      :let [dups (->> kids
                                      frequencies
                                      (filter (fn [[_ count]] (> count 1)))
                                      (map first)
                                      seq)]
                      :when dups]
                  {:issue :siblings/duplicate :parent p :ids dups})

                 ;; All children must exist as nodes
                (for [[_ kids] children-by-parent
                      id kids
                      :when (not (contains? nodes id))]
                  {:issue :child/unknown :id id})

                 ;; No child can have multiple parents
                (for [[id parents] child->parents
                      :when (> (count parents) 1)]
                  {:issue :child/multiple-parents :id id :parents parents}))]
    {:ok? (empty? errors) :errors (vec errors)}))