(ns kernel.api
  "Unified API façade for intent dispatch.

   Single entry point for all state changes:
   - Validates intent against current UI state (state machine)
   - Compiles intents to ops
   - Records history
   - Interprets ops through transaction pipeline
   - Returns new DB and any validation issues

   This is the primary interface for UI, tests, REPL, and agent scripts."
  (:require [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [kernel.history :as H]
            [kernel.db :as db]
            [kernel.state-machine :as sm]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io]))
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

;; Phases 4 & 5: ephemeral-op? removed - session nodes no longer in DB
;; All ops are now structural (affect document graph only)
;; Ephemeral state (cursor, selection, fold, zoom, buffer) lives purely in shell.session

;; ── State Machine Configuration ─────────────────────────────────────────────

(def ^:dynamic *enforce-state-machine*
  "When true, validate intents against state machine before dispatch.

   If intent is not allowed in current state, dispatch returns {:db db :ops [] :issues []}
   without executing the intent (silent no-op, matching Logseq behavior).

   Default: true (enabled)

   Override in tests:
     (binding [api/*enforce-state-machine* false]
       (dispatch db session intent))"
  true)

(defn dispatch*
  "Dispatch an intent with full trace output (for REPL/agents).

   Like dispatch, but returns {:db :issues :trace} for debugging and introspection.

   Args:
   - db: Current database (persistent document graph)
   - session: Current session state (ephemeral UI state) or nil for session-independent intents
   - intent: Intent map (e.g., {:type :select :ids \"a\"})
   - opts: Optional map with:
     - :history/enabled? - Set to false to disable history recording (default: true)
     - :state-machine/enforce? - Override *enforce-state-machine* (default: use dynamic var)

   Returns:
   - {:db new-db :issues [] :trace [...]} - Full result with trace

   State Machine Enforcement (LOGSEQ PARITY):
   - When *enforce-state-machine* is true (default), validates intent against current state
   - Invalid intents return no-op result (no changes, no errors) - matches Logseq behavior
   - Idle state guard: Enter/Backspace/Tab/etc. do nothing from idle state
   - Edit-only intents blocked when in selection mode and vice versa

   Example:
     (dispatch* db session {:type :selection :mode :extend-next})
     ;=> {:db db' :issues [] :trace [{:tx-id ... :ops [...]}]}

     (dispatch* db nil {:type :indent :id \"a\"} {:history/enabled? false})
     ;=> {:db db' :issues [] :trace [...]} (no history recorded)

   Use in REPL/agents for debugging:
     (let [{:keys [db issues trace]} (api/dispatch* db session intent)]
       (when (seq issues)
         (println \"Issues:\" issues))
       (println \"Trace:\" trace)
       db)"
  ([db session intent] (dispatch* db session intent nil))
  ([db session intent {:keys [history/enabled? state-machine/enforce?] :as _opts}]
   (let [enforce? (if (some? enforce?) enforce? *enforce-state-machine*)]

     ;; STATE MACHINE GUARD (LOGSEQ PARITY)
     ;; Check if intent is allowed in current state
     ;; If not, return silent no-op (matches Logseq's behavior)
     (if (and enforce?
              session ; Can't validate state without session
              (or (sm/idle-guard session intent)
                  (not (sm/intent-allowed? session intent))))
       ;; Intent blocked by state machine - return no-op
       {:db db
        :issues []
        :trace [{:tx-id #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
                 :ops []
                 :applied-ops []
                 :num-applied 0
                 :notes (str "State machine blocked: "
                             (:type intent) " not allowed in "
                             (sm/current-state session) " state")}]
        :session-updates nil}

       ;; Intent allowed - proceed with dispatch
       (let [{:keys [ops session-updates]} (intent/apply-intent db session intent)
             ;; Only record history when there are actual structural ops
             ;; Ephemeral intents (session-updates only) don't trigger history
             record? (and (not (false? enabled?)) (seq ops))
             ;; Record both DB and session for proper undo/redo cursor restore
             db0 (if record? (H/record db session) db)]
         #?(:clj (journal-tx! intent ops))
         ;; DB ops go through normal transaction pipeline
         ;; Session updates are returned for caller to apply
         (-> (tx/interpret db0 ops)
             (assoc :session-updates session-updates)))))))

(defn dispatch
  "Dispatch an intent: compile to ops, record history, interpret, return result.

   Args:
   - db: Current database (persistent document graph)
   - session: Current session state (ephemeral UI state) or nil
   - intent: Intent map (e.g., {:type :select :ids \"a\"})

   Returns:
   - {:db new-db :issues [] :session-updates {...}} - On success
   - {:db old-db :issues [...] :session-updates nil} - On validation failure

   The caller is responsible for applying :session-updates to session atom.

   Example:
     (dispatch db session {:type :selection :mode :extend-next})
     ;=> {:db db' :issues [] :session-updates {:selection {...}}}

     (dispatch db nil {:type :indent :id \"x\"})
     ;=> {:db db' :issues [] :session-updates nil}

   Use in app:
     (let [{:keys [db session-updates]} (api/dispatch @!db (session/get-session) intent)]
       (reset! !db db)
       (when session-updates
         (session/swap-session! merge session-updates)))

   Use in tests/REPL:
     (let [{:keys [db issues]} (api/dispatch db test-session intent)]
       (if (empty? issues)
         db
         (throw (ex-info \"Validation failed\" {:issues issues}))))"
  [db session intent]
  (select-keys (dispatch* db session intent) [:db :issues :session-updates]))

(defn dispatch!
  "Dispatch an intent, throwing on validation failure.

   Like dispatch, but throws ex-info if there are validation issues.
   Useful for tests and REPL workflows.

   Example:
     (dispatch! db session {:type :selection :mode :next})
     ;=> new-db (or throws)"
  [db session intent]
  (let [{:keys [db issues]} (dispatch db session intent)]
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

;; ── Journal Replay (depends on dispatch*) ────────────────────────────────────

#?(:clj
   (defn replay-journal
     "Replay all transactions from journal file.

      Returns final DB state after replaying all recorded transactions.
      Useful for REPL timetravel and debugging.

      Note: Replays with nil session - handlers that depend on session state
      will not work correctly during replay. For full replay, store session
      snapshots alongside intents in the journal.

      Example:
        (set-journal! true)
        ;; ... do some work ...
        (def db' (replay-journal (db/empty-db)))
        ;; db' now has all journaled changes applied"
     [initial-db]
     (let [journal (journal-path)]
       (if (.exists (File. journal))
         (with-open [rdr (io/reader journal)]
           (reduce
            (fn [current-db line]
              (if-not (str/blank? line)
                (try
                  (let [{:keys [intent]} (read-string line)]
                    ;; Replay with nil session - session-dependent intents may fail
                    (:db (dispatch* current-db nil intent)))
                  (catch Exception e
                    (println "Warning: Failed to replay transaction:" (.getMessage e))
                    current-db))
                current-db))
            initial-db
            (line-seq rdr)))
         (do
           (println "No journal file found at" journal)
           initial-db)))))
