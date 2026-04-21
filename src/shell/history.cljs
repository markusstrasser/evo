(ns shell.history
  "Shell-level undo/redo storage.

   Owns the `!history` atom. The kernel has no reference to this atom:
   `kernel.history` exposes pure functions that operate on history values,
   and the shell is responsible for threading state through.

   This namespace exists because undo/redo is a UI concern (\"put the user
   back where they were\"), not a kernel concern. Pulling history out of
   the kernel db also unblocks Phase B of the kernel refactor, which
   replaces snapshot history with an append-only op log."
  (:require [kernel.history :as H]
            [shell.view-state :as vs]))

(defonce !history (atom H/empty-history))

(defn record!
  "Record the pre-dispatch (db, session) into history.

   Call this *before* applying ops — the recorded snapshot is the state
   we undo back to."
  [db session]
  (swap! !history H/record db session))

(defn undo!
  "Perform undo: rewrite !db and !history, restore session if captured.

   Returns true if an undo happened, false if nothing to undo."
  [!db]
  (if-let [result (H/undo @!history @!db (vs/get-view-state))]
    (do
      (clojure.core/reset! !history (:history result))
      (clojure.core/reset! !db (:db result))
      (when-let [s (:session result)]
        (vs/merge-view-state-updates! s))
      true)
    false))

(defn redo!
  "Perform redo. Returns true if a redo happened, false otherwise."
  [!db]
  (if-let [result (H/redo @!history @!db (vs/get-view-state))]
    (do
      (clojure.core/reset! !history (:history result))
      (clojure.core/reset! !db (:db result))
      (when-let [s (:session result)]
        (vs/merge-view-state-updates! s))
      true)
    false))

(defn can-undo? [] (H/can-undo? @!history))
(defn can-redo? [] (H/can-redo? @!history))
(defn undo-count [] (H/undo-count @!history))
(defn redo-count [] (H/redo-count @!history))

(defn clear!
  "Clear history (both past and future). Used on folder switch / test reset."
  []
  (clojure.core/reset! !history H/empty-history))

(defn get-history
  "Read the current history value (for devtools/debug inspection)."
  []
  @!history)
