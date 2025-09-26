(ns debug-selection
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.commands :as commands]
            [evolver.kernel :as kernel]
            [evolver.constants :as constants]))

(defn create-debug-store []
  (let [initial-state constants/initial-db-base]
    (println "Initial state view:" (:view initial-state))
    (atom initial-state)))

(deftest debug-select-node
  (testing "Debug select node step by step"
    (let [store (create-debug-store)]
      (println "Before selection:" (get-in @store [:view :selection-set]))
      
      ;; Call select-node directly
      (commands/select-node store {} {:node-id "p2-high"})
      
      (println "After selection:" (get-in @store [:view :selection-set]))
      (println "Full view state:" (:view @store))
      
      (is (= #{"p2-high"} (get-in @store [:view :selection-set]))))))

(debug-select-node)
