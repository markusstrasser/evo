(ns macros.script
  "Macro script runner for multi-step operations.

   CONCEPT:
   A macro simulates a sequence of steps on a scratch DB, collecting normalized
   operations from each step. The accumulated ops are then committed to the real
   DB in a single atomic transaction.

   WHY:
   Enables multi-step operations where step N needs to see the results of step N-1.
   Example: Smart Backspace deletes a block, then queries which block to select next.

   GUARANTEES:
   - Scratch DB simulation (safe experimentation)
   - Full trace for debugging (:step, :ops, :db at each step)
   - Atomic final commit (one undo entry)
   - Kernel unchanged (still just 3 ops: create, place, update)

   USAGE:
   ```clojure
   (ns macros.editing
     (:require [macros.script :as script]))

   (defn smart-backspace [db {:keys [id]}]
     (:ops
       (script/run db
         [;; Step 1: Delete block (emit intent)
          {:type :delete :id id}

          ;; Step 2: Function sees result of step 1
          (fn [db-after-delete]
            (when-let [prev (get-in db-after-delete [:derived :prev-id-of id])]
              [{:type :select :id prev}
               {:type :cursor-move :id prev :where :end}]))])))
   ```

   READER GUIDE:
   1. step->ops: Convert various step types to ops
   2. run: Main runner - loops through steps, accumulates ops
   3. Safe guards: MAX-STEPS prevents infinite loops"
  (:require [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.db :as db]))

;; ── Configuration ──────────────────────────────────────────────────────────────

(def ^:const MAX-STEPS
  "Hard limit on number of steps to prevent infinite loops.
   Default: 64 steps should be more than enough for any reasonable macro."
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

(defn intent?
  "Check if x is an intent (has :type key, no :op key)."
  [x]
  (and (map? x) (contains? x :type) (not (contains? x :op))))

(defn intents?
  "Check if x is a vector of intents."
  [x]
  (and (sequential? x) (every? intent? x)))

;; ── Step Compilation ───────────────────────────────────────────────────────────

(defn step->ops
  "Convert a step to operations.

   A step can be:
   - nil: No-op, returns empty vector
   - Function: Called with current DB, must return another step
   - Operation: Wrapped in vector
   - Vector of ops: Passed through
   - Intent: Compiled via intent/apply-intent
   - Vector of intents: Each compiled via intent/apply-intent

   This enables flexible step composition:
   - Static ops: [{:op :place ...}]
   - Intents: {:type :delete :id \"a\"}
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
        (vec (mapcat (partial step->ops db) result))
        ;; Function returned single step, compile it
        (step->ops db result)))

    (op? step)
    [step]

    (ops? step)
    (vec step)

    (intent? step)
    ;; Macros run on scratch DB with no session context
    ;; Session-dependent handlers will receive nil session
    (:ops (intent/apply-intent db nil step))

    (intents? step)
    (vec (mapcat #(:ops (intent/apply-intent db nil %)) step))

    :else
    (throw (ex-info "Unknown step form"
                    {:step step
                     :type (type step)
                     :hint "Step must be: nil, fn, op, [ops], intent, or [intents]"}))))

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
  "Run a macro script on a scratch database.

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
     steps: Vector of steps (ops, intents, or functions)
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
                   [{:type :delete :id \"b\"}
                    (fn [db'] [{:type :select
                                :id (get-in db' [:derived :prev-id-of \"b\"])}])]))
     (:ops result)  ;=> [{:op :place ...} {:op :update-node ...}]
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
       (throw (ex-info "Macro exceeded max-steps limit"
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

             ;; 2. Normalize ops against current scratch DB
             ;; This resolves anchors (:at :last → :at 3, etc.)
             normalized-ops (#'tx/normalize-ops scratch-db raw-ops)

             ;; 3. Validate ops (throws on error)
             ;; We only need the issues vector, not the intermediate DB
             [_ issues] (try
                          (#'tx/validate-ops scratch-db normalized-ops)
                          (catch #?(:clj Exception :cljs :default) e
                            (throw (ex-info "Validation failed during macro"
                                            {:step step
                                             :step-index step-count
                                             :ops normalized-ops
                                             :trace trace
                                             :cause e}))))

             ;; If validation found issues, abort with trace
             _ (when (seq issues)
                 (throw (ex-info "Macro step failed validation"
                                 {:step step
                                  :step-index step-count
                                  :ops normalized-ops
                                  :issues issues
                                  :trace trace
                                  :hint "Check :trace for execution history"})))

             ;; 4. Apply ops to scratch DB
             scratch-db' (reduce #'tx/apply-op scratch-db normalized-ops)

             ;; 5. Derive indexes on scratch DB
             scratch-db'' (db/derive-indexes scratch-db')

             ;; 6. Record trace entry (for debugging)
             trace-entry {:step step
                          :step-index step-count
                          :ops normalized-ops
                          :db-after scratch-db''}
             trace' (conj trace trace-entry)]

         ;; Recurse: Continue with remaining steps
         (recur scratch-db''
                (subvec remaining 1)
                (into accumulated-ops normalized-ops)
                trace'
                (inc step-count)))))))

;; ── Public API ─────────────────────────────────────────────────────────────────

(defn run-ops
  "Convenience: Run macro and return ops only (drop :db and :trace).

   Use when you don't need the scratch DB or trace for debugging.

   Example:
     (tx/interpret real-db (run-ops real-db steps))"
  ([db steps]
   (:ops (run db steps)))
  ([db steps opts]
   (:ops (run db steps opts))))
