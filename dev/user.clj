(ns user
  "Development REPL harness for the new kernel.
   
   Provides convenient functions for interactive testing."
  (:require [core.db :as db]
            [core.interpret :as I]
            [core.schema :as S]
            [clojure.pprint :as pp]))

(defonce !db (atom (db/empty-db)))

(defn run!
  "Runs a transaction against the current database state."
  [txs]
  (let [result (I/interpret @!db txs)]
    (if (seq (:issues result))
      (do
        (println "Issues found:")
        (pp/pprint (:issues result))
        result)
      (do
        (reset! !db (:db result))
        (println "Transaction applied successfully")
        result))))

(defn state
  "Returns the current database state."
  []
  @!db)

(defn reset!
  "Resets the database to empty state."
  []
  (reset! !db (db/empty-db))
  (println "Database reset"))

(defn validate!
  "Validates the current database state."
  []
  (let [result (I/validate @!db)]
    (if (:ok? result)
      (println "Database is valid")
      (do
        (println "Validation errors:")
        (pp/pprint (:errors result))))
    result))

(defn show-children
  "Shows children of a parent in the current database."
  [parent]
  (get-in @!db [:children-by-parent parent]))

(defn show-node
  "Shows a node's data in the current database."
  [id]
  (get-in @!db [:nodes id]))

(defn show-derived
  "Shows derived data for current database."
  []
  (get @!db :derived))

(defn schemas
  "Shows available operation schemas."
  []
  (S/describe-ops))

;; Example usage functions
(defn example-basic
  "Runs the basic golden trace example."
  []
  (reset!)
  (run! [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
         {:op :create-node :id "a" :type :p :props {:t "A"} :under "root" :at :last}
         {:op :create-node :id "b" :type :p :props {:t "B"} :under "root" :at :last}
         {:op :place :id "b" :under "root" :at {:before "a"}}
         {:op :update-node :id "a" :props {:style {:bold true}}}]))

(defn example-reparent
  "Runs the reparent golden trace example."
  []
  (reset!)
  (run! [{:op :create-node :id "root" :type :doc :props {} :under :doc :at :last}
         {:op :create-node :id "h1" :type :h1 :props {:t "Header 1"} :under "root" :at :last}
         {:op :create-node :id "h2" :type :h2 :props {:t "Header 2"} :under "root" :at :last}
         {:op :create-node :id "subtree" :type :div :props {} :under "h1" :at :last}
         {:op :create-node :id "child1" :type :p :props {:t "Child 1"} :under "subtree" :at :last}
         {:op :create-node :id "child2" :type :p :props {:t "Child 2"} :under "subtree" :at :last}
         {:op :place :id "subtree" :under "h2" :at :last}
         {:op :place :id "child2" :under "subtree" :at {:before "child1"}}]))

(println "Kernel dev harness loaded!")
(println "Try: (example-basic), (example-reparent), (state), (validate!)")
(println "Available functions: run!, state, reset!, validate!, show-children, show-node, show-derived, schemas")