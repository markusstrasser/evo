(ns kernel.api
  "Unified API façade for intent dispatch.

   Single entry point for all state changes:
   - Compiles intents to ops
   - Records history
   - Interprets ops through transaction pipeline
   - Returns new DB and any validation issues

   This is the primary interface for UI, tests, REPL, and agent scripts."
  (:require [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [kernel.history :as H]
            [kernel.db :as db]
            [kernel.constants :as const]
            [clojure.string :as str])
  #?(:clj (:import [java.io File])))

;; ── Journal for REPL Timetravel ──────────────────────────────────────────────

(defonce ^:private !journal-enabled (atom false))

(defn set-journal!
  "Enable or disable ops journaling for REPL timetravel.

   When enabled, every dispatch appends ops to .architect/ops.ednlog
   as one EDN transaction per line.

   Example:
     (set-journal! true)   ; enable journaling
     (set-journal! false)  ; disable journaling"
  [enabled?]
  (reset! !journal-enabled enabled?))

(defn journal-enabled?
  "Check if ops journaling is enabled."
  []
  @!journal-enabled)

#?(:clj
   (defn- journal-path
     "Get path to ops journal file."
     []
     ".architect/ops.ednlog"))

#?(:clj
   (defn- ensure-journal-dir!
     "Ensure .architect directory exists."
     []
     (let [dir (File. ".architect")]
       (when-not (.exists dir)
         (.mkdir dir)))))

#?(:clj
   (defn- journal-tx!
     "Append transaction to journal file (one EDN line per tx)."
     [intent ops]
     (when @!journal-enabled
       (try
         (ensure-journal-dir!)
         (let [entry {:intent intent :ops ops}
               edn-str (pr-str entry)]
           (spit (journal-path) (str edn-str "\n") :append true))
         (catch Exception e
           (println "Warning: Failed to write journal:" (.getMessage e)))))))

#?(:clj
   (defn replay-journal
     "Replay all transactions from journal file.

      Returns final DB state after replaying all recorded transactions.
      Useful for REPL timetravel and debugging.

      Example:
        (set-journal! true)
        ;; ... do some work ...
        (def db' (replay-journal (db/empty-db)))
        ;; db' now has all journaled changes applied"
     [initial-db]
     (let [journal (journal-path)]
       (if (.exists (File. journal))
         (with-open [rdr (clojure.java.io/reader journal)]
           (reduce
            (fn [db line]
              (when-not (str/blank? line)
                (try
                  (let [{:keys [intent]} (read-string line)]
                    (:db (dispatch db intent)))
                  (catch Exception e
                    (println "Warning: Failed to replay transaction:" (.getMessage e))
                    db))))
            initial-db
            (line-seq rdr)))
         (do
           (println "No journal file found at" journal)
           initial-db)))))

(defn ephemeral-op?
  "Check if an operation is ephemeral (UI-only, should not enter history).

   Ephemeral ops update the session UI node and should not trigger history recording.
   Examples: cursor state, edit mode, transient UI state.

   Returns true if op is an :update-node on the session UI node."
  [op]
  (and (= :update-node (:op op))
       (= const/session-ui-id (:id op))))

(defn dispatch*
  "Dispatch an intent with full trace output (for REPL/agents).

   Like dispatch, but returns {:db :issues :trace} for debugging and introspection.

   Args:
   - db: Current database
   - intent: Intent map (e.g., {:type :select :ids \"a\"})
   - opts: Optional map with:
     - :history/enabled? - Set to false to disable history recording (default: true)

   Returns:
   - {:db new-db :issues [] :trace [...]} - Full result with trace

   Example:
     (dispatch* db {:type :select :ids \"a\"})
     ;=> {:db db' :issues [] :trace [{:tx-id ... :ops [...]}]}

     (dispatch* db {:type :select :ids \"a\"} {:history/enabled? false})
     ;=> {:db db' :issues [] :trace [...]} (no history recorded)

   Use in REPL/agents for debugging:
     (let [{:keys [db issues trace]} (api/dispatch* db intent)]
       (when (seq issues)
         (println \"Issues:\" issues))
       (println \"Trace:\" trace)
       db)"
  ([db intent] (dispatch* db intent nil))
  ([db intent {:keys [history/enabled?] :as _opts}]
   (let [{:keys [ops]} (intent/apply-intent db intent)
         record? (and (not (false? enabled?))
                      (some (complement ephemeral-op?) ops))
         db0 (if record? (H/record db) db)]
     #?(:clj (journal-tx! intent ops))
     (tx/interpret db0 ops))))

(defn dispatch
  "Dispatch an intent: compile to ops, record history, interpret, return result.

   Args:
   - db: Current database
   - intent: Intent map (e.g., {:type :select :ids \"a\"})

   Returns:
   - {:db new-db :issues []} - On success (trace omitted for UI use)
   - {:db old-db :issues [...]} - On validation failure

   Example:
     (dispatch db {:type :select :ids \"a\"})
     ;=> {:db db' :issues []}

     (dispatch db {:type :create-node :id \"x\" :type :block})
     ;=> {:db db' :issues []}

   Use in app:
     (swap! !db #(-> (api/dispatch % intent) :db))

   Use in tests/REPL:
     (let [{:keys [db issues]} (api/dispatch db intent)]
       (if (empty? issues)
         db
         (throw (ex-info \"Validation failed\" {:issues issues}))))"
  [db intent]
  (select-keys (dispatch* db intent) [:db :issues]))

(defn dispatch!
  "Dispatch an intent, throwing on validation failure.

   Like dispatch, but throws ex-info if there are validation issues.
   Useful for tests and REPL workflows.

   Example:
     (dispatch! db {:type :select :ids \"a\"})
     ;=> new-db (or throws)"
  [db intent]
  (let [{:keys [db issues]} (dispatch db intent)]
    (if (empty? issues)
      db
      (throw (ex-info "Intent validation failed"
                      {:intent intent
                       :issues issues})))))

(defn list-intents
  "List all registered intent types with their metadata.

   Returns map of intent-type -> {:doc string :spec malli-schema}

   Example:
     (list-intents)
     ;=> {:select {:doc \"Set selection...\" :spec [:map ...]}
     ;    :indent {:doc \"Indent block...\" :spec [:map ...]}}"
  []
  (intent/list-intents))

(defn has-handler?
  "Check if an intent type has a registered handler.

   Example:
     (has-handler? :select) ;=> true
     (has-handler? :foo) ;=> false"
  [intent-type]
  (intent/has-handler? intent-type))

(defn check
  "Validate database invariants and return formatted report.

   Runs both:
   - Global DB validation (parent/child relationships, cycles, etc.)
   - Derived index freshness checks

   Returns:
   - {:ok? true} - If all checks pass
   - {:ok? false :errors [...]} - If validation fails

   Example:
     (check db)
     ;=> {:ok? true}

     (check broken-db)
     ;=> {:ok? false
     ;    :errors [\"Child x of parent y does not exist in :nodes\"
     ;             \":derived is stale - does not match recomputed version\"]}

   Use in REPL:
     (let [result (api/check db)]
       (if (:ok? result)
         (println \"✓ DB is valid\")
         (do
           (println \"✗ DB validation failed:\")
           (doseq [err (:errors result)]
             (println \"  -\" err)))))"
  [db]
  (db/validate db))
