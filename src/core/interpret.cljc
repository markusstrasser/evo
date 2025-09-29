(ns core.interpret
  "Transaction interpreter: normalization, validation, execution pipeline."
  (:require [core.db :as db]
            [core.ops :as ops]
            [core.schema :as schema]))

(defn- normalize-ops
  "Normalize operations:
   - Drop no-op place (same parent & index)
   - Clamp integer indices to valid ranges
   - Merge adjacent update-node on same id"
  [db ops]
  (let [derived (:derived db)]
    (loop [normalized []
           remaining ops]
      (if (empty? remaining)
        ;; Final pass: merge adjacent updates
        (loop [result []
               ops normalized]
          (if (empty? ops)
            result
            (let [op (first ops)
                  rest-ops (rest ops)]
              (if (and (= (:op op) :update-node)
                       (seq rest-ops)
                       (= (:op (first rest-ops)) :update-node)
                       (= (:id op) (:id (first rest-ops))))
                ;; Merge this update with the next one
                (let [merged-op (update op :props #(merge % (:props (first rest-ops))))]
                  (recur result (cons merged-op (rest rest-ops))))
                ;; Keep this op as-is
                (recur (conj result op) rest-ops)))))

        (let [op (first remaining)
              rest-ops (rest remaining)]
          (case (:op op)
            :place
            (let [{:keys [id under at]} op
                  current-parent (get-in derived [:parent-of id])
                  current-siblings (get (:children-by-parent db) current-parent [])
                  current-idx (.indexOf current-siblings id)]

              ;; Check if this is a no-op move
              (if (and (= current-parent under)
                       (let [resolved-idx (case at
                                            :first 0
                                            :last (count current-siblings)
                                            (cond
                                              (integer? at) at
                                              (map? at) (cond
                                                          (:before at) (.indexOf current-siblings (:before at))
                                                          (:after at) (inc (.indexOf current-siblings (:after at)))
                                                          :else current-idx)
                                              :else current-idx))]
                         (= current-idx resolved-idx)))
                ;; Skip no-op place
                (recur normalized rest-ops)
                ;; Keep the place operation
                (recur (conj normalized op) rest-ops)))

            ;; All other ops pass through
            (recur (conj normalized op) rest-ops)))))))

(defn- validate-op
  "Validate a single operation. Returns vector of issues."
  [db op op-index]
  (let [issues (atom [])]
    (letfn [(issue! [issue-kw hint]
              (swap! issues conj {:issue issue-kw
                                  :op op
                                  :at op-index
                                  :hint hint}))]

      ;; Schema validation
      (when-not (schema/valid-op? op)
        (issue! :invalid-schema (str "Operation does not match schema: "
                                     (schema/explain-op op))))

      (case (:op op)
        :create-node
        (let [{:keys [id]} op]
          (when (contains? (:nodes db) id)
            (issue! :duplicate-create (str "Node " id " already exists"))))

        :place
        (let [{:keys [id under at]} op]
          ;; id must exist
          (when-not (contains? (:nodes db) id)
            (issue! :node-not-found (str "Node " id " does not exist")))

          ;; parent must exist or be in :roots
          (when-not (or (contains? (:roots db) under)
                        (contains? (:nodes db) under))
            (issue! :parent-not-found (str "Parent " under " does not exist")))

          ;; Check anchor membership for :before/:after
          (when (map? at)
            (let [siblings (get (:children-by-parent db) under [])]
              (cond
                (:before at)
                (when-not (some #(= % (:before at)) siblings)
                  (issue! :anchor-not-sibling
                          (str "Anchor :before " (:before at) " is not a sibling under " under)))

                (:after at)
                (when-not (some #(= % (:after at)) siblings)
                  (issue! :anchor-not-sibling
                          (str "Anchor :after " (:after at) " is not a sibling under " under))))))

          ;; Cycle guard: forbid placing under own descendant or self
          (when (contains? (:nodes db) id)
            (let [derived (:derived db)]
              (letfn [(is-descendant? [potential-ancestor potential-descendant]
                        (loop [current potential-descendant]
                          (cond
                            (nil? current) false
                            (= current potential-ancestor) true
                            (contains? (:roots db) current) false
                            :else (recur (get-in derived [:parent-of current])))))]
                (when (or (= id under)
                          (and (string? under) (is-descendant? id under)))
                  (issue! :cycle-detected
                          (str "Cannot place " id " under " under " - would create cycle")))))))

        :update-node
        (let [{:keys [id]} op]
          (when-not (contains? (:nodes db) id)
            (issue! :node-not-found (str "Node " id " does not exist"))))

        ;; Unknown op
        (issue! :unknown-op (str "Unknown operation: " (:op op)))))

    @issues))

(defn- validate-ops
  "Validate all operations in sequence, accumulating issues."
  [db ops]
  (loop [all-issues []
         current-db db
         remaining-ops ops
         op-index 0]
    (if (empty? remaining-ops)
      [current-db all-issues]
      (let [op (first remaining-ops)
            op-issues (validate-op current-db op op-index)]
        (if (seq op-issues)
          ;; Has issues - stop processing and return current state with issues
          [current-db (into all-issues op-issues)]
          ;; No issues - apply the op and continue
          (let [updated-db (case (:op op)
                             :create-node (ops/create-node current-db (:id op) (:type op) (:props op))
                             :place (ops/place current-db (:id op) (:under op) (:at op))
                             :update-node (ops/update-node current-db (:id op) (:props op)))]
            (recur all-issues
                   updated-db
                   (rest remaining-ops)
                   (inc op-index))))))))

(defn interpret
  "Interpret a transaction sequence. 
   
   Pipeline: normalize → validate → apply → derive → trace
   
   Args:
     db - starting database
     txs - vector of operations
     
   Returns:
     {:db final-db :issues [...] :trace [...]}"
  [db txs]
  (let [normalized-ops (normalize-ops db txs)
        [final-db issues] (validate-ops db normalized-ops)
        derived-db (db/derive-indexes final-db)

        ;; Simple trace - just the operations that were applied
        applied-ops (take (- (count normalized-ops) (count issues)) normalized-ops)
        trace (mapv (fn [op] {:type :op-applied :op op}) applied-ops)]

    {:db derived-db
     :issues issues
     :trace trace}))

;; Public API functions matching the spec
(defn derive-db
  "Recompute derived state for database."
  [db]
  (db/derive-indexes db))

(defn validate
  "Validate database invariants."
  [db]
  (db/validate db))

(defn describe-ops
  "Return Malli schemas for operations."
  []
  (schema/describe-ops))