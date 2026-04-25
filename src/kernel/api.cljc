(ns kernel.api
  "Unified API façade for intent dispatch.

   Single entry point for all state changes:
   - Validates intent against current UI state (state machine)
   - Compiles intents to ops
   - Interprets ops through the transaction pipeline
   - Returns new DB, emitted ops, session-updates, issues

   This namespace is pure: history recording is the caller's concern
   (see kernel.log / shell.log / dispatch-logged below)."
  (:require [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [kernel.log :as L]
            [kernel.db :as db]
            [kernel.state-machine :as sm]
            #_{:clj-kondo/ignore [:unused-namespace]} ; str/ used in CLJ reader conditional
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

   Pure: log appending is the caller's responsibility (see
   `dispatch-logged` or `shell.log/append-and-advance!`).

   Args:
   - db: Current database (persistent document graph)
   - session: Current session state (ephemeral UI state) or nil for session-independent intents
   - intent: Intent map (e.g., {:type :selection :mode :replace :ids [\"a\"]})
   - opts: Optional map with:
     - :state-machine/enforce? - Override *enforce-state-machine* (default: use dynamic var)
     - :tx/now-ms - Explicit timestamp used to materialize raw ops

   Returns:
   - {:db new-db :issues [] :trace [...] :session-updates {...} :ops [...]}

   `:ops` is the vector of materialized kernel ops that were actually applied
   by the transaction pipeline. Callers that track history should log these ops,
   not the raw handler output.

   State Machine Enforcement (LOGSEQ PARITY):
   - When *enforce-state-machine* is true (default), validates intent against current state
   - Invalid intents return no-op result (no changes, no errors) - matches Logseq behavior
   - Idle state guard: Enter/Backspace/Tab/etc. do nothing from idle state
   - Edit-only intents blocked when in selection mode and vice versa"
  ([db session intent] (dispatch* db session intent nil))
  ([db session intent {:keys [state-machine/enforce?] :as opts}]
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
        :ops []
        :issues []
        :trace [{:tx-id (or (:tx-id opts) :state-machine-blocked)
                 :ops []
                 :applied-ops []
                 :num-applied 0
                 :notes (str "State machine blocked: "
                             (:type intent) " not allowed in "
                             (sm/current-state session) " state")}]
                 :session-updates nil}

       ;; Intent allowed - proceed with dispatch
       (let [{handler-ops :ops session-updates :session-updates} (intent/apply-intent db session intent)
             result (tx/interpret db handler-ops opts)]
         #?(:clj (journal-tx! intent (:ops result)))
         ;; DB ops go through normal transaction pipeline.
         ;; Session updates + materialized ops are returned for callers
         ;; (shell/tests) that want to record history.
         (assoc result :session-updates session-updates))))))

(defn dispatch-logged
  "Dispatch an intent and append the resulting transaction to a log.

   Convenience wrapper for callers (tests, REPL, agents) that want undo/redo
   without manually building log entries. Appends iff structural ops were
   emitted. Identity (op-id, timestamp) is caller-provided via `mint`
   so the kernel stays entropy-free.

   Args:
     log     Current log value (see `kernel.log/empty-log`)
     db      Current db
     session Current session (ephemeral UI state)
     intent  Intent map
     mint    Fn of () -> {:op-id uuid :timestamp ms}

   Returns:
     {:log :db :ops :issues :session-updates :trace}"
  [log db session intent mint]
  (let [{:keys [op-id timestamp]} (mint)
        result (dispatch* db session intent {:tx/now-ms timestamp})
        db-after (:db result)
        ops (:ops result)
        ;; Log only on REAL state change, not merely 'intent emitted ops'.
        ;; Ops may validate-away (issues) or normalize-away (no-op :place)
        ;; leaving db-before = db-after; those shouldn't grow undo depth.
        changed? (not= db db-after)
        new-log (if changed?
                  (let [prev-op-id (:op-id (L/entry-at-head log))
                        entry (L/make-entry {:op-id op-id
                                             :prev-op-id prev-op-id
                                             :timestamp timestamp
                                             :intent intent
                                             :ops ops
                                             :session session})]
                    (L/append log entry))
                  log)]
    (assoc result :log new-log)))

(defn dispatch
  "Dispatch an intent: compile to ops, record history, interpret, return result.

   Args:
   - db: Current database (persistent document graph)
   - session: Current session state (ephemeral UI state) or nil
   - intent: Intent map (e.g., {:type :selection :mode :replace :ids [\"a\"]})

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
     (let [{:keys [db session-updates]} (api/dispatch @!db (vs/get-view-state) intent)]
       (reset! !db db)
       (when session-updates
         (vs/merge-view-state-updates! session-updates)))

   Use in tests/REPL:
     (let [{:keys [db issues]} (api/dispatch db test-session intent)]
       (if (empty? issues)
         db
         (throw (ex-info \"Validation failed\" {:issues issues}))))"
  ([db session intent]
   (dispatch db session intent nil))
  ([db session intent opts]
   (select-keys (dispatch* db session intent opts) [:db :ops :issues :session-updates])))

(defn dispatch!
  "Dispatch an intent, throwing on validation failure.

   Like dispatch, but throws ex-info if there are validation issues.
   Useful for tests and REPL workflows.

   Example:
     (dispatch! db session {:type :selection :mode :next})
     ;=> new-db (or throws)"
  ([db session intent]
   (dispatch! db session intent nil))
  ([db session intent opts]
  (let [{:keys [db issues]} (dispatch db session intent opts)]
    (if (empty? issues)
      db
      (throw (ex-info "Intent validation failed"
                      {:intent intent
                       :issues issues}))))))

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

(defn gc-tombstones
  "Garbage collect tombstoned nodes from database.
   
   Tombstones are nodes marked with {:tombstone? true} - they represent
   permanently deleted items that should eventually be purged.
   
   This operation BYPASSES the transaction pipeline (no history, no undo)
   because it's garbage collection, not a user action.
   
   Returns new DB with tombstoned nodes removed from :nodes and :children-by-parent.
   
   Example:
     (swap! !db gc-tombstones)  ; Purge all tombstones
     
   Typically called on app startup or periodically."
  [db]
  (let [tombstoned-ids (->> (:nodes db)
                            (filter (fn [[_id node]]
                                      (get-in node [:props :tombstone?])))
                            (map first)
                            set)]
    (if (empty? tombstoned-ids)
      db
      (-> db
          ;; Remove from :nodes
          (update :nodes #(apply dissoc % tombstoned-ids))
          ;; Remove from all children lists
          (update :children-by-parent
                  (fn [cbp]
                    (reduce-kv
                     (fn [acc parent children]
                       (let [filtered (filterv #(not (tombstoned-ids %)) children)]
                         (if (empty? filtered)
                           (dissoc acc parent)
                           (assoc acc parent filtered))))
                     {}
                     cbp)))
          ;; Recompute derived indexes
          db/derive-indexes))))

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
