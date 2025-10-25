(ns dev.core
  "Development REPL portal - single-stop debugging for every refactor step"
  (:require [kernel.api :as api]
            [kernel.db :as DB]
            [kernel.dbg :as dbg]
            [kernel.history :as H]
            [kernel.transaction :as tx]))

(defonce !db (atom (-> (DB/empty-db) tx/interpret :db H/record)))

(defn reset!
  "Reset the dev database to empty state"
  []
  (reset! !db (-> (DB/empty-db) tx/interpret :db H/record)))

(defn d!
  "Dispatch intent with trace to REPL"
  [intent]
  (let [{:keys [db issues trace]} (api/dispatch* @!db intent)]
    (reset! !db db)
    (when (seq issues) (println "ISSUES:" issues))
    (println (dbg/pp-trace trace))
    db))

(defn pp
  "Pretty-print current DB summary"
  []
  (println (dbg/pp-db-summary @!db)))

(defn can-undo?
  "Check if undo is available"
  []
  (H/can-undo? @!db))

(defn undo!
  "Undo last operation"
  []
  (swap! !db (fn [db] (or (H/undo db) db))))

(defn redo!
  "Redo last undone operation"
  []
  (swap! !db (fn [db] (or (H/redo db) db))))
