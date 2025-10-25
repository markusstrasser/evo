(ns kernel.transaction
  "Transaction interpreter: normalization, validation, execution pipeline."
  (:require [kernel.db :as db]
            [kernel.ops :as ops]
            [kernel.position :as pos]
            [kernel.schema :as schema]
            [medley.core :as m]))

(defn- same-position-after-place?
  "Check if node would end up at the same index after place operation.
   Simulates remove → resolve anchor → insert to find final position."
  [siblings id at]
  (let [current-idx (.indexOf siblings id)]
    (when-not (neg? current-idx)
      (let [siblings-without-node (vec (remove #(= % id) siblings))]
        (try
          (let [target-idx (pos/resolve-anchor-in-vec siblings-without-node at)]
            (= current-idx target-idx))
          (catch #?(:clj Exception :cljs :default) _
            ;; Invalid anchor means not same position
            false))))))

(defn- is-noop-place?
  "Check if a :place operation is a no-op (same parent and index).

   A place is a noop when:
   1. The node stays under the same parent, AND
   2. After applying the place logic (remove → resolve → insert),
      the node ends up at the same index

   Returns true for noop operations, nil for non-:place operations."
  [db {:keys [op id under at] :as _op}]
  (when (= op :place)
    (let [current-parent (get-in db [:derived :parent-of id])]
      (and (= under current-parent)
           (same-position-after-place?
            (get-in db [:children-by-parent under] [])
            id
            at)))))

(defn- remove-noop-places
  "Filter out no-op :place operations."
  [db ops]
  (remove #(is-noop-place? db %) ops))

(defn- merge-adjacent-updates
  "Merge adjacent :update-node operations on the same id.
   Uses deep merge to preserve nested property updates."
  [ops]
  (reduce
   (fn [acc op]
     (if-let [prev (peek acc)]
       (if (and (= :update-node (:op op) (:op prev))
                (= (:id op) (:id prev)))
         (conj (pop acc) (update prev :props ops/deep-merge (:props op)))
         (conj acc op))
       [op]))
   []
   ops))

(defn- normalize-ops
  "Normalize operations:
   - Canonicalize :at anchors (:at-start → :first, :at-end → :last, int → {:at-index n})
   - Drop no-op place (same parent & index)
   - Merge adjacent update-node on same id"
  [db ops]
  (->> ops
       (map #(if (= (:op %) :place) (update % :at pos/canon) %))
       (remove-noop-places db)
       merge-adjacent-updates))

;; =============================================================================
;; Validation helpers - smaller, focused functions
;; =============================================================================

(defn- make-issue
  "Create an issue map with operation context."
  [op op-index issue-kw hint]
  {:issue issue-kw
   :op op
   :at op-index
   :hint hint})

(defn- parent-of-from
  "Build parent-of map from children-by-parent.
   Returns map of {child-id parent-id}."
  [children-by-parent]
  (reduce-kv
   (fn [m parent kids]
     (reduce #(assoc %1 %2 parent) m kids))
   {}
   children-by-parent))

(defn- descendant-of?
  "Check if potential-descendant is a descendant of potential-ancestor.
   Walks up the parent chain until reaching a root or detecting the ancestor.

   Builds fresh parent-of map from children-by-parent to avoid stale derived data
   during multi-op validation."
  [db potential-ancestor potential-descendant]
  (let [parent-of (parent-of-from (:children-by-parent db))
        roots (set (:roots db))]
    (loop [current potential-descendant]
      (cond
        (nil? current) false
        (= current potential-ancestor) true
        (contains? roots current) false
        :else (recur (get parent-of current))))))

(defn- would-create-cycle?
  "Check if placing node-id under parent would create a cycle.
   A cycle occurs when:
   1. node-id equals parent (self-parent)
   2. parent is a descendant of node-id (would create loop)"
  [db node-id parent]
  (or (= node-id parent)
      (and (string? parent)
           (descendant-of? db node-id parent))))

(defn- valid-parent?
  "Check if parent is valid (either a root or an existing node)."
  [db parent]
  (or (some #{parent} (:roots db))
      (contains? (:nodes db) parent)))

(defn- validate-anchor
  "Validate anchor (keyword, integer, or map).
   For place operations, simulates removal of the node being placed before validating.
   Returns issue vector if anchor is invalid, empty vector otherwise."
  [db op op-index under at node-id]
  (let [siblings (get-in db [:children-by-parent under] [])
        ;; Simulate removal of node being placed (if it's in this parent)
        siblings-for-validation (vec (remove #(= % node-id) siblings))]
    (try
      (pos/resolve-anchor-in-vec siblings-for-validation at)
      []  ;; Valid anchor, no issues
      (catch #?(:clj Exception :cljs :default) e
        (let [reason (ex-data e)]
          [(make-issue op op-index
                       (case (:reason reason)
                         ::pos/missing-target :anchor-not-sibling
                         ::pos/oob :anchor-oob
                         ::pos/bad-anchor :anchor-bad
                         :anchor-bad)
                       (str "Invalid anchor: " (pr-str at)))])))))

(defn- validate-create-node
  "Validate :create-node operation."
  [db op op-index]
  (let [{:keys [id]} op]
    (if (contains? (:nodes db) id)
      [(make-issue op op-index :duplicate-create
                   (str "Node " id " already exists"))]
      [])))

(defn- validate-place
  "Validate :place operation."
  [db op op-index]
  (let [{:keys [id under at]} op
        node-exists? (contains? (:nodes db) id)]
    (->> [(when-not node-exists?
            (make-issue op op-index :node-not-found
                        (str "Node " id " does not exist")))

          (when-not (valid-parent? db under)
            (make-issue op op-index :parent-not-found
                        (str "Parent " under " does not exist")))

          ;; Only validate anchor if node exists (otherwise anchor check is moot)
          (when node-exists?
            (validate-anchor db op op-index under at id))

          ;; Only check for cycles if both node and parent exist
          (when (and node-exists? (valid-parent? db under)
                     (would-create-cycle? db id under))
            (make-issue op op-index :cycle-detected
                        (str "Cannot place " id " under " under " - would create cycle")))]
         (remove nil?)
         flatten
         vec)))

(defn- validate-update-node
  "Validate :update-node operation."
  [db op op-index]
  (let [{:keys [id]} op]
    (if (contains? (:nodes db) id)
      []
      [(make-issue op op-index :node-not-found
                   (str "Node " id " does not exist"))])))

(defn- validate-op
  "Validate a single operation. Returns vector of issues.

   Validation pipeline:
   1. Check schema validity
   2. Validate operation-specific constraints

   Each validator returns a vector of issues (empty if valid)."
  [db op op-index]
  (let [schema-issues (when-not (schema/valid-op? op)
                        [(make-issue op op-index :invalid-schema
                                     (str "Operation does not match schema: " (schema/explain-op op)))])

        op-issues (case (:op op)
                    :create-node (validate-create-node db op op-index)
                    :place (validate-place db op op-index)
                    :update-node (validate-update-node db op op-index)
                    :update-ui []  ; Always valid - just merges into :ui
                    [(make-issue op op-index :unknown-op
                                 (str "Unknown operation: " (:op op)))])]

    (vec (concat schema-issues op-issues))))

(defn- apply-op
  "Apply a single operation to the database."
  [db {:keys [op id props under at] node-type :type}]
  (case op
    :create-node (ops/create-node db id node-type props)
    :place (ops/place db id under at)
    :update-node (ops/update-node db id props)
    :update-ui (ops/update-ui db props)))

(defn- validate-ops
  "Validate all operations in sequence, accumulating issues.

   Processes operations one at a time:
   - Validates each operation against current DB state
   - Applies valid operations to maintain DB state
   - Stops on first error, returning DB and accumulated issues

   Returns: [final-db issues]"
  [db ops]
  (reduce
   (fn [[current-db all-issues] [op-index op]]
     (let [op-issues (validate-op current-db op op-index)]
       (if (seq op-issues)
         ;; Stop on first error
         (reduced [current-db (into all-issues op-issues)])
         ;; Apply valid operation and continue
         [(apply-op current-db op) all-issues])))
   [db []]
   (m/indexed ops)))

(defn interpret
  "Interpret a transaction sequence.

   Pipeline: normalize → validate → apply → [derive] → trace

   Args:
     db - starting database
     txs - vector of operations
   Options (optional map):
     :tx-id - transaction ID for trace (default: system time)
     :seed - random seed for deterministic testing (default: system time)
     :notes - human-readable notes for this transaction
     :tx/skip-derived? - skip derive-indexes (for benchmarking, default: false)

   Returns:
     {:db final-db :issues [...] :trace [...]}

   Trace format:
   [{:tx-id <id> :seed <seed> :ops [<ops>] :notes \"...\" :num-applied <n>} ...]"
  ([db txs] (interpret db txs nil))
  ([db txs opts]
   (let [{:keys [tx-id seed notes tx/skip-derived?]} opts
         tx-id (or tx-id #?(:clj (System/currentTimeMillis)
                            :cljs (.now js/Date)))
         seed (or seed #?(:clj (System/currentTimeMillis)
                          :cljs (.now js/Date)))
         normalized-ops (normalize-ops db txs)
         [final-db issues] (validate-ops db normalized-ops)
         derived-db (if skip-derived?
                      final-db
                      (db/derive-indexes final-db))

         ;; Deterministic trace with all context
         num-applied (- (count normalized-ops) (count issues))
         applied-ops (take num-applied normalized-ops)
         trace-entry {:tx-id tx-id
                      :seed seed
                      :ops normalized-ops
                      :applied-ops applied-ops
                      :num-applied num-applied
                      :notes (or notes "")}]

     {:db derived-db
      :issues issues
      :trace [trace-entry]})))

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

(defn txret
  "Convenience wrapper for interpret that returns {:db :issues}.
   Omits :trace for cleaner test assertions."
  ([db txs]
   (select-keys (interpret db txs) [:db :issues]))
  ([db txs opts]
   (select-keys (interpret db txs opts) [:db :issues])))

;; ── Tagged Literal Readers ───────────────────────────────────────────────────

(defn read-create
  "Tagged literal reader for #op/create. Validates at read time."
  [m]
  (let [op (assoc m :op :create-node)]
    (when-not (schema/valid-op? op)
      (throw (ex-info "Invalid #op/create at read time"
                      {:reason ::invalid-create-op
                       :op op
                       :errors (schema/explain-op op)})))
    op))

(defn read-place
  "Tagged literal reader for #op/place. Validates at read time."
  [m]
  (let [op (assoc m :op :place)]
    (when-not (schema/valid-op? op)
      (throw (ex-info "Invalid #op/place at read time"
                      {:reason ::invalid-place-op
                       :op op
                       :errors (schema/explain-op op)})))
    op))

(defn read-update
  "Tagged literal reader for #op/update. Validates at read time."
  [m]
  (let [op (assoc m :op :update-node)]
    (when-not (schema/valid-op? op)
      (throw (ex-info "Invalid #op/update at read time"
                      {:reason ::invalid-update-op
                       :op op
                       :errors (schema/explain-op op)})))
    op))

(defn read-tx
  "Tagged literal reader for #tx. Just wraps in a vector."
  [xs]
  (vec xs))