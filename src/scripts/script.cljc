(ns scripts.script
  "Script runner for multi-step structural operations.

   CONCEPT:
   A script simulates a sequence of steps on a scratch DB, collecting normalized
   operations from each step. The accumulated ops are then committed to the real
   DB in a single atomic transaction.

   WHY:
   Enables multi-step structural operations where step N needs to see the
   results of step N-1.
   Example: Create a block, place it, then inspect the scratch DB to decide
   which block ID an outer handler should focus.

   GUARANTEES:
   - Scratch DB simulation (safe experimentation)
   - Full trace for debugging (:step, :ops, :db at each step)
   - Atomic final commit (one undo entry)
   - Kernel unchanged (still just 3 ops: create, place, update)

   USAGE:
   ```clojure
   (ns app.structural-script
     (:require [scripts.script :as script]))

   (defn insert-block [db {:keys [under at text]}]
     (let [new-id (str (random-uuid))
           result (script/run db
                    [{:op :create-node
                      :id new-id
                      :type :block
                      :props {:text (or text \"\")}}
                     {:op :place :id new-id :under under :at at}])]
       {:ops (:ops result)
        :new-id new-id}))
   ```

   READER GUIDE:
   1. `step->ops`: Convert structural step forms to ops
   2. `run`: Loop through steps and accumulate normalized ops
   3. Safe guards: `MAX-STEPS` prevents infinite loops"
  (:require [kernel.transaction :as tx]
            [kernel.db :as db]))

;; ── Configuration ──────────────────────────────────────────────────────────────

(def ^:const MAX-STEPS
  "Hard limit on number of steps to prevent infinite loops.
   Default: 64 steps should be more than enough for any reasonable script."
  64)

;; ── Step Type Predicates ───────────────────────────────────────────────────────

(defn op?
  "Check if x is an operation (has :op key)."
  [x]
  (and (map? x) (keyword? (:op x))))

(defn ops?
  "Check if x is a vector of operations."
  [x]
  (and (sequential? x) (every? op? x)))

;; ── Step Compilation ───────────────────────────────────────────────────────────

(defn step->ops
  "Convert a step to operations.

   A step can be:
   - nil: No-op, returns empty vector
   - Function: Called with current DB, must return another structural step
   - Operation: Wrapped in vector
   - Vector of ops: Passed through

   This enables flexible step composition:
   - Static ops: [{:op :place ...}]
   - Conditional logic: (fn [db] (when (pred db) [{:op ...}]))

   Args:
     db: Current scratch database state
     step: Step to compile

   Returns:
     Vector of operations

   Throws:
     ex-info if step is unrecognized type"
  [db step]
  (cond
    (nil? step)
    []

    (fn? step)
    ;; Function returns another step (or vector of steps)
    ;; Recur to compile the result
    (let [result (step db)]
      (if (sequential? result)
        ;; Function returned multiple steps, compile each
        (into [] (mapcat (partial step->ops db)) result)
        ;; Function returned single step, compile it
        (step->ops db result)))

    (op? step)
    [step]

    (ops? step)
    (vec step)

    :else
    (throw (ex-info "Unknown step form"
                    {:step step
                     :type (type step)
                     :hint "Step must be: nil, fn, op, or [ops]"}))))

;; ── Normalization Idempotence ──────────────────────────────────────────────────
;;
;; CRITICAL CONTRACT:
;; Normalized ops must be idempotent - running normalize on an already-normalized
;; op must return the same op unchanged. This ensures that ops collected during
;; scratch simulation can be safely re-normalized during final commit.
;;
;; Currently handled by tx/normalize-ops (canonicalize-place-anchor, etc.)
;; If :place ops with {:parent :idx} form are added in future, ensure they
;; pass through normalize unchanged.

;; ── Main Runner ────────────────────────────────────────────────────────────────

(defn run
  "Run a structural script on a scratch database.

   Simulates a sequence of steps on a throwaway copy of the DB, collecting
   normalized operations from each step. Returns accumulated ops for atomic
   commit to real DB.

   PIPELINE (per step):
   1. Compile step to ops (via step->ops)
   2. Normalize ops against current scratch DB
   3. Validate ops (throws on error)
   4. Apply ops to scratch DB
   5. Derive indexes on scratch DB
   6. Accumulate normalized ops
   7. Record trace entry

   FINAL:
   - Returns {:ops [...] :db scratch-db :trace [...]}
   - Caller commits :ops to real DB via tx/interpret
   - Result: One transaction, one undo entry

   Args:
     db: Starting database (the REAL db, not modified)
     steps: Vector of steps (ops or functions returning structural steps)
     opts: Optional map with:
       :max-steps - Override MAX-STEPS limit (default: 64)

   Returns:
     {:ops [...]        ; Accumulated normalized ops, ready for commit
      :db scratch-db    ; Final scratch DB state (for inspection)
      :trace [...]}     ; Debug trace: [{:step :ops :db} ...]

   Throws:
     ex-info if max-steps exceeded (infinite loop protection)
     ex-info if validation fails (with trace for debugging)

   Example:
     (def result (run db
                   [{:op :update-node :id \"b\" :props {:text \"updated\"}}
                    (fn [db'] {:op :update-node
                               :id \"b\"
                               :props {:tag (get-in db' [:nodes \"b\" :props :text])}})]))
     (:ops result)  ;=> [{:op :update-node ...} {:op :update-node ...}]
     (:trace result) ;=> [{:step {...} :ops [...] :db {...}} ...]"
  ([db steps]
   (run db steps {}))

  ([db steps {:keys [max-steps] :or {max-steps MAX-STEPS}}]
   (loop [scratch-db db
          remaining (vec steps)
          accumulated-ops []
          trace []
          step-count 0]

     ;; Guard: Prevent infinite loops
     (when (> step-count max-steps)
       (throw (ex-info "Script exceeded max-steps limit"
                       {:max-steps max-steps
                        :step-count step-count
                        :trace trace
                        :hint "Check for infinite recursion in step functions"})))

     ;; Base case: No more steps
     (if (empty? remaining)
       {:ops accumulated-ops
        :db scratch-db
        :trace trace}

       ;; Process next step
       (let [step (first remaining)

             ;; 1. Compile step to raw ops
             raw-ops (try
                       (step->ops scratch-db step)
                       (catch #?(:clj Exception :cljs :default) e
                         (throw (ex-info "Failed to compile step"
                                         {:step step
                                          :step-index step-count
                                          :trace trace
                                          :cause e}))))

             ;; 2-4. Normalize, validate, apply via public API
             {:keys [db ops issues]}
             (try
               (tx/dry-run scratch-db raw-ops)
               (catch #?(:clj Exception :cljs :default) e
                 (throw (ex-info "dry-run failed during script"
                                 {:step step
                                  :step-index step-count
                                  :trace trace
                                  :cause e}))))

             normalized-ops ops

             ;; If validation found issues, abort with trace
             _ (when (seq issues)
                 (throw (ex-info "Script step failed validation"
                                 {:step step
                                  :step-index step-count
                                  :ops normalized-ops
                                  :issues issues
                                  :trace trace
                                  :hint "Check :trace for execution history"})))

             ;; 5. Derive indexes on the result for next step's queries
             scratch-db' (db/derive-indexes db)

             ;; 6. Record trace entry (for debugging)
             trace-entry {:step step
                          :step-index step-count
                          :ops normalized-ops
                          :db-after scratch-db'}]

         ;; Recurse: Continue with remaining steps
         (recur scratch-db'
                (rest remaining)
                (into accumulated-ops normalized-ops)
                (conj trace trace-entry)
                (inc step-count)))))))

;; ── Public API ─────────────────────────────────────────────────────────────────

(defn run-ops
  "Convenience: Run a script and return ops only (drop :db and :trace).

   Use when you don't need the scratch DB or trace for debugging.

   Example:
     (tx/interpret real-db (run-ops real-db steps))"
  ([db steps]
   (:ops (run db steps)))
  ([db steps opts]
   (:ops (run db steps opts))))
