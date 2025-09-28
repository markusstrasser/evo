(ns merge-debug-test
  (:require [cljs.test :refer [deftest testing is run-tests]]
            [evolver.kernel :as k]
            [evolver.intents :as intents]
            [evolver.dispatcher :as dispatcher]))

(def test-db
  (k/update-derived
   {:nodes {"root" {:type :div}
            "p1" {:type :p :props {:text "P1"}}
            "p2" {:type :p :props {:text "P2"}}}
    :children-by-parent {"root" ["p1" "p2"]
                         "p1" []
                         "p2" []}
    :view {:selection []}}))

(deftest debug-merge-functions
  (testing "Debug get-prev and get-next for merging"
    (let [prev-p2 (k/get-prev test-db "p2")
          next-p1 (k/get-next test-db "p1")]
      (println "Previous node of P2:" prev-p2)
      (println "Next node of P1:" next-p1)
      (is (= "p1" prev-p2) "P2's previous should be P1")
      (is (= "p2" next-p1) "P1's next should be P2"))))

(deftest debug-merge-up-intent
  (testing "Debug merge-block-up intent"
    (let [store (atom {:past [] :present test-db :future []})]
      
      ;; Try to merge P2 up (should merge with P1)
      (println "\n=== TESTING MERGE UP ===")
      (let [merge-tx (intents/merge-block-up test-db {:cursor "p2" :cursor-position 0})]
        (println "Merge up transaction:" (pr-str merge-tx))
        (is (some? merge-tx) "Should generate merge transaction"))
      
      ;; Apply via dispatcher
      (dispatcher/dispatch-intent! store :merge-block-up {:cursor "p2" :cursor-position 0})
      (let [new-state (:present @store)]
        (println "After merge up:")
        (println "Nodes:" (keys (:nodes new-state)))
        (println "P1 text:" (get-in new-state [:nodes "p1" :props :text]))
        (println "P2 exists?" (contains? (:nodes new-state) "p2"))))))

(deftest debug-merge-down-intent  
  (testing "Debug merge-block-down intent"
    (let [store (atom {:past [] :present test-db :future []})]
      
      ;; Try to merge P1 down (should merge with P2)  
      (println "\n=== TESTING MERGE DOWN ===")
      (let [merge-tx (intents/merge-block-down test-db {:cursor "p1" :cursor-position 2 :block-content "P1"})]
        (println "Merge down transaction:" (pr-str merge-tx))
        (is (some? merge-tx) "Should generate merge transaction"))
      
      ;; Apply via dispatcher
      (dispatcher/dispatch-intent! store :merge-block-down {:cursor "p1" :cursor-position 2 :block-content "P1"})
      (let [new-state (:present @store)]
        (println "After merge down:")
        (println "Nodes:" (keys (:nodes new-state)))
        (println "P1 text:" (get-in new-state [:nodes "p1" :props :text]))
        (println "P2 exists?" (contains? (:nodes new-state) "p2"))))))

(run-tests)