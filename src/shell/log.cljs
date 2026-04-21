(ns shell.log
  "Shell-level op log storage.

   Owns the `!log` atom. The current db is a pure function of the log
   (see `kernel.log/head-db`), but for performance we maintain a live
   `!db` atom alongside: forward progress updates it directly without
   a full refold, and undo/redo refold from the log's root.

   This namespace also mints the entry identity (op-id, timestamp) on
   behalf of the kernel, honoring the invariant that the kernel never
   reads a clock or calls random-uuid."
  (:require [kernel.log :as L]
            [kernel.transaction :as tx]
            [shell.view-state :as vs]))

(defonce !log (atom L/empty-log))

(defn- now-ms [] (.now js/Date))

(defn- mint-entry
  "Build a log entry from a dispatch result, minting identity."
  [intent ops session prev-op-id]
  (L/make-entry
   {:op-id (random-uuid)
    :prev-op-id prev-op-id
    :timestamp (now-ms)
    :intent intent
    :ops ops
    :session session}))

(defn append-and-advance!
  "Record a successful structural dispatch into the log.

   Takes the post-dispatch `db-after`, the intent, the kernel ops, and the
   pre-dispatch session snapshot. Updates !log (and returns the new log).

   `!db` is not touched here — the executor already has db-after in hand
   and resets !db itself. Keeping db-update out of this function avoids
   double reset and matches the current intent-apply shape."
  [intent ops session]
  (let [prev-head (:head @!log)
        prev-entry (L/entry-at-head @!log)
        prev-op-id (:op-id prev-entry)
        _ (assert (>= prev-head -1))
        entry (mint-entry intent ops session prev-op-id)]
    (swap! !log L/append entry)))

(defn undo!
  "Perform undo: rewrite !log and !db, restore session from the entry
   being stepped past.

   Returns true if an undo happened, false if nothing to undo."
  [!db]
  (let [log @!log]
    (if-let [new-log (L/undo log)]
      (let [entry-undone (L/entry-at-head log)   ; the entry we're stepping off of
            session-before (:session-before entry-undone)
            new-db (L/head-db new-log)]
        (clojure.core/reset! !log new-log)
        (clojure.core/reset! !db new-db)
        (when session-before
          (vs/merge-view-state-updates! session-before))
        true)
      false)))

(defn redo!
  "Perform redo. Returns true if a redo happened, false otherwise."
  [!db]
  (let [log @!log]
    (if-let [new-log (L/redo log)]
      (let [new-db (L/head-db new-log)
            ;; On redo, we have no natural session to restore: the
            ;; original dispatch didn't capture \"session after\" anywhere.
            ;; Cursor behavior mirrors current code: the DOM re-renders
            ;; from the new db, and the editing-block-id survives from
            ;; the pre-undo session.
            _ new-db]
        (clojure.core/reset! !log new-log)
        (clojure.core/reset! !db new-db)
        true)
      false)))

(defn can-undo? [] (L/can-undo? @!log))
(defn can-redo? [] (L/can-redo? @!log))
(defn undo-count [] (L/undo-count @!log))
(defn redo-count [] (L/redo-count @!log))

(defn reset-with-db!
  "Discard history and set :root-db to `db`. Used when loading a folder
   or resetting in test mode: the loaded state is the new baseline, not
   part of the undo stack."
  [db]
  (clojure.core/reset! !log (L/reset-root db (:limit @!log))))

(defn clear!
  "Discard ops, keep :root-db. Typically callers reset-with-db! instead."
  []
  (swap! !log L/clear))

(defn get-log
  "Read the current log value (for devtools/debug inspection)."
  []
  @!log)

;; ── Bulk ops (load-from-folder, fixtures) ────────────────────────────────────

(defn apply-ops!
  "Apply a batch of kernel ops to the current db, bypassing the log.

   Used by the storage layer on cold-start (loading the folder) and by
   test fixtures. These ops are baseline state, not user actions, so they
   do not enter the undo stack — they update :root-db instead.

   Returns the new db."
  [!db ops]
  (let [db-before @!db
        {new-db :db} (tx/interpret db-before ops)]
    (clojure.core/reset! !db new-db)
    (reset-with-db! new-db)
    new-db))
