(ns kernel.transaction
  "Transaction interpreter: normalization, validation, execution pipeline.

   READER GUIDE:
   ─────────────
   This is the transaction pipeline. It turns operation lists into state changes.
   Flow: normalize → validate → apply → derive (optional)
   - normalize: Filter no-ops, resolve references
   - validate: Check schema, invariants (cycles, missing refs)
   - apply: Execute ops via kernel.ops
   - derive: Recompute derived indexes (unless :tx/skip-derived? true)

   ONE LAW: The pipeline is a pure transformation (DB, ops) → {:db :issues :trace}.
   Issues block execution (return old DB). No issues means ops executed successfully.

   FUNCTIONAL REQUIREMENTS (Kernel Invariants):
   ─────────────────────────────────────────────
   This namespace enforces critical kernel invariants:

   :fr.kernel/derive-indexes
     All operations trigger automatic index derivation via db/derive-indexes.
     Derived state (:parent-of, :next-id-of, :prev-id-of, traversal orders)
     is NEVER mutated directly - always recomputed from canonical structure.

   :fr.kernel/undo-restores-all
     Transaction pipeline is pure (DB, ops) → DB'. Combined with kernel.history,
     this enables complete undo/redo: all state (content + session) is restored
     by replaying inverse operations through the same pipeline."
  (:require [kernel.db :as db]
            [kernel.ops :as ops]
            [kernel.position :as pos]
            [kernel.schema :as schema]
            [kernel.time :as time]
            [medley.core :as m]))

(defn- same-position-after-place?
  "Check if node would end up at the same index after place operation.
   Uses resolve-insert-index with :drop-id for consistent 'remove before place' semantics."
  [siblings id at]
  (let [current-idx (.indexOf siblings id)]
    (when-not (neg? current-idx)
      (try
        (let [target-idx (pos/resolve-insert-index siblings at {:drop-id id})]
          (= current-idx target-idx))
        (catch #?(:clj Exception :cljs :default) _
          ;; Invalid anchor means not same position
          false)))))

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
  "Filter out no-op :place operations, processing sequentially.

   CRITICAL: Ops must be checked against INTERMEDIATE state, not just the original db.
   When moving multiple blocks, op N may only appear as a noop against the original state
   but is NOT a noop after ops 1..N-1 have been applied.

   Example: Moving [A, B] after C with ops:
     Op1: place A after C
     Op2: place B after A

   In original state [A, B, C], Op2 looks like a noop (B is already after A).
   But after Op1, state is [B, C, A], so Op2 actually moves B from index 0 to after A.

   Solution: Simulate applying each non-noop op to a scratch DB before checking the next.

   NOTE: If ops/place throws (invalid anchor), we keep the op and let validation handle it."
  [db ops]
  (let [step (fn [{:keys [result scratch-db]} op]
               (if (is-noop-place? scratch-db op)
                 ;; Skip noop, scratch-db unchanged
                 {:result result
                  :scratch-db scratch-db}
                 ;; Keep op and update scratch-db for next iteration
                 (let [next-db (if (= (:op op) :place)
                                 ;; Apply place to scratch for next noop check
                                 ;; Invalid ops pass through to validation
                                 (try
                                   (ops/place scratch-db (:id op) (:under op) (:at op))
                                   (catch #?(:clj Exception :cljs :default) _
                                     scratch-db))
                                 ;; Non-place ops don't affect place noop detection
                                 scratch-db)]
                   {:result (conj result op)
                    :scratch-db next-db})))]
    (:result (reduce step {:result [] :scratch-db db} ops))))

(defn- merge-adjacent-updates
  "Merge adjacent :update-node operations on the same id.
   Uses deep merge to preserve nested property updates."
  [ops]
  (reduce
   (fn [acc op]
     (let [prev (peek acc)
           same-update? (and (= :update-node (:op op) (:op prev))
                             (= (:id op) (:id prev)))]
       (cond
         (nil? prev) [op]
         same-update? (conj (pop acc) (update prev :props ops/deep-merge (:props op)))
         :else (conj acc op))))
   []
   ops))

(defn- canonicalize-place-anchor
  "Canonicalize :at anchor for :place operations.
   Non-place operations pass through unchanged."
  [op]
  (if (= :place (:op op))
    (update op :at pos/canon)
    op))

;; =============================================================================
;; Timestamp enrichment - auto-add created-at/updated-at
;; =============================================================================

(defn- enrich-create-op
  "Add created-at and updated-at timestamps to :create-node ops.
   Preserves existing timestamps if already present (e.g., from file import)."
  [{:keys [props] :as op}]
  (let [ts (time/now-ms)
        existing (select-keys props [:created-at :updated-at])]
    (update op :props merge
            {:created-at ts :updated-at ts}
            existing)))

(defn- enrich-update-op
  "Add updated-at timestamp to :update-node ops."
  [op]
  (update op :props assoc :updated-at (time/now-ms)))

(defn- enrich-op
  "Enrich operation with auto-generated metadata (timestamps)."
  [op]
  (case (:op op)
    :create-node (enrich-create-op op)
    :update-node (enrich-update-op op)
    op))

(defn- enrich-ops
  "Enrich all operations with auto-generated metadata."
  [ops]
  (mapv enrich-op ops))

(defn- normalize-ops
  "Normalize operations:
   - Enrich with timestamps (created-at, updated-at)
   - Canonicalize :at anchors (:at-start → :first, :at-end → :last)
   - Drop no-op place (same parent & index)
   - Merge adjacent update-node on same id"
  [db ops]
  (->> ops
       enrich-ops
       (map canonicalize-place-anchor)
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
  (into {}
        (for [[parent kids] children-by-parent
              kid kids]
          [kid parent])))

(defn- descendant-of-fresh?
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
   2. parent is a descendant of node-id (would create loop)

   Uses descendant-of-fresh? which rebuilds parent-of from children-by-parent
   to avoid stale derived data during multi-op validation."
  [db node-id parent]
  (or (= node-id parent)
      (and (string? parent)
           (descendant-of-fresh? db node-id parent))))

(defn- validate-anchor
  "Validate anchor (keyword, integer, or map).
   Uses resolve-insert-index with :drop-id for consistent 'remove before place' semantics.
   Returns issue vector if anchor is invalid, empty vector otherwise."
  [db op op-index under at node-id]
  (let [siblings (get-in db [:children-by-parent under] [])]
    (try
      (pos/resolve-insert-index siblings at {:drop-id node-id})
      [] ;; Valid anchor, no issues
      (catch #?(:clj Exception :cljs :default) e
        (let [reason (ex-data e)]
          [(make-issue op op-index
                       (case (:reason reason)
                         ::pos/missing-target :anchor-not-sibling
                         ::pos/oob :anchor-oob
                         ::pos/bad-anchor :anchor-bad
                         :anchor-bad)
                       (str "Invalid anchor: " (pr-str at)))])))))

(defn- check-node-not-exists
  "Validate that node doesn't already exist. Returns issue if duplicate found, nil otherwise."
  [db op op-index id]
  (when (contains? (:nodes db) id)
    (make-issue op op-index :duplicate-create
                (str "Node " id " already exists"))))

(defn- validate-create-node
  "Validate :create-node operation."
  [db op op-index]
  (let [{:keys [id]} op]
    (keep identity [(check-node-not-exists db op op-index id)])))

(defn- check-node-exists
  "Validate that node exists. Returns issue if not found, nil otherwise."
  [db op op-index id]
  (when-not (contains? (:nodes db) id)
    (make-issue op op-index :node-not-found
                (str "Node " id " does not exist"))))

(defn- check-parent-valid
  "Validate that parent is valid. Returns issue if invalid, nil otherwise."
  [db op op-index under]
  (when-not (db/valid-parent? db under)
    (make-issue op op-index :parent-not-found
                (str "Parent " under " does not exist"))))

(defn- check-no-cycle
  "Validate that placement doesn't create cycle. Returns issue if cycle detected, nil otherwise."
  [db op op-index id under]
  (when (would-create-cycle? db id under)
    (make-issue op op-index :cycle-detected
                (str "Cannot place " id " under " under " - would create cycle"))))

(defn- validate-place
  "Validate :place operation.
   Checks node existence, parent validity, anchor validity, and cycle prevention."
  [db op op-index]
  (let [{:keys [id under at]} op
        node-exists? (contains? (:nodes db) id)
        parent-valid? (db/valid-parent? db under)]
    (into []
          (comp cat
                (remove nil?))
          [[(check-node-exists db op op-index id)]
           [(check-parent-valid db op op-index under)]
           ;; Only validate anchor if node exists (otherwise anchor check is moot)
           (when node-exists?
             (validate-anchor db op op-index under at id))
           ;; Only check for cycles if both node and parent exist
           (when (and node-exists? parent-valid?)
             [(check-no-cycle db op op-index id under)])])))

(defn- validate-update-node
  "Validate :update-node operation."
  [db op op-index]
  (let [{:keys [id]} op]
    (keep identity [(check-node-exists db op op-index id)])))

(defn- validate-op
  "Validate a single operation. Returns vector of issues.

   Validation pipeline:
   1. Check schema validity
   2. Validate operation-specific constraints

   Each validator returns a vector of issues (empty if valid)."
  [db op op-index]
  (into []
        (comp cat)
        [(when-not (schema/valid-op? op)
           [(make-issue op op-index :invalid-schema
                        (str "Operation does not match schema: " (schema/explain-op op)))])
         (case (:op op)
           :create-node (validate-create-node db op op-index)
           :place (validate-place db op op-index)
           :update-node (validate-update-node db op op-index)
           [(make-issue op op-index :unknown-op
                        (str "Unknown operation: " (:op op)))])]))

(defn- apply-op
  "Apply a single operation to the database."
  [db {:keys [op id props under at] node-type :type}]
  (case op
    :create-node (ops/create-node db id node-type props)
    :place (ops/place db id under at)
    :update-node (ops/update-node db id props)))

(defn- process-operation
  "Process a single operation during validation.
   Returns updated [db issues] or reduced value if validation fails."
  [[current-db all-issues] [op-index op]]
  (let [op-issues (validate-op current-db op op-index)]
    (if (seq op-issues)
      ;; Stop on first error
      (reduced [current-db (into all-issues op-issues)])
      ;; Apply valid operation and continue
      [(apply-op current-db op) all-issues])))

(defn- validate-ops
  "Validate all operations in sequence, accumulating issues.

   Processes operations one at a time:
   - Validates each operation against current DB state
   - Applies valid operations to maintain DB state
   - Stops on first error, returning DB and accumulated issues

   Returns: [final-db issues]"
  [db ops]
  (reduce process-operation [db []] (m/indexed ops)))

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

         ;; Optimization: skip derive-indexes for update-only transactions.
         ;; :update-node only changes node props, not tree structure.
         ;; :create-node without :place creates orphan (no derived change).
         ;; Only :place ops modify tree structure and require re-derivation.
         structure-changing? (some #(= (:op %) :place) normalized-ops)

         t1 (time/now-ms)
         derived-db (cond
                      skip-derived? final-db
                      (not structure-changing?) (assoc final-db :derived (:derived db))
                      :else (db/derive-indexes final-db))
         t2 (time/now-ms)

         ;; Performance instrumentation (DEBUG only, >5ms threshold)
         _ #?(:cljs (when ^boolean goog.DEBUG
                      (let [derive-ms (- t2 t1)
                            node-count (count (:nodes final-db))]
                        (when (and structure-changing? (> derive-ms 5))
                          (js/console.log "🔄 derive-indexes:"
                                          derive-ms "ms |"
                                          node-count "nodes |"
                                          (.toFixed (/ derive-ms (max 1 node-count)) 3) "ms/node"))))
              :clj nil)

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