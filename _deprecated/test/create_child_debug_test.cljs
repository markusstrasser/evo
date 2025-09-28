(ns create-child-debug-test
  "Debug test to understand why create-child doesn't work"
  (:require [cljs.test :refer [deftest testing is are run-tests]]
            [evolver.kernel :as k]
            [evolver.intents :as intents]
            [evolver.dispatcher :as dispatcher]
            [evolver.renderer :as renderer]))

;; Simple test DB
(def test-db
  (k/update-derived
   {:nodes {"root" {:type :div}
            "p1" {:type :p :props {:text "Parent block"}}}
    :children-by-parent {"root" ["p1"]
                         "p1" []}
    :view {:selection ["p1"]}}))

(deftest debug-create-child-intent
  (testing "Debug create-child-block intent step by step"
    (let [store (atom {:past [] :present test-db :future []})]
      
      ;; 1. Check the intent function exists
      (let [create-child-fn (get intents/intents :create-child-block)]
        (is (some? create-child-fn) "create-child-block intent should exist")
        
        ;; 2. Call the intent function directly to see what transaction it generates
        (let [transaction (create-child-fn test-db {:cursor "p1"})]
          (println "Generated transaction:" (pr-str transaction))
          (is (some? transaction) "Intent should generate a transaction")
          (is (vector? transaction) "Transaction should be a vector")
          (is (= 1 (count transaction)) "Should generate one command")
          
          (let [command (first transaction)]
            (println "Command details:" (pr-str command))
            (is (= :insert (:op command)) "Should be an insert operation")
            (is (= "p1" (:parent-id command)) "Should insert into p1")
            (is (some? (:node-id command)) "Should have a node ID")
            (is (some? (:node-data command)) "Should have node data")
            
            ;; Check the node data
            (let [node-data (:node-data command)]
              (is (= :div (:type node-data)) "New node should be a div")
              (is (= "New child" (get-in node-data [:props :text])) "Should have content"))
            
            ;; Check position - this is likely the problem
            (println "Position value:" (pr-str (:position command)))
            (println "At-position value:" (pr-str (:at-position command)))
            
            ;; 3. Try to apply the transaction
            (try
              (let [new-state (k/apply-transaction test-db [command])]
                (println "Transaction applied successfully!")
                (println "New children of p1:" (get-in new-state [:children-by-parent "p1"]))
                (println "New nodes:" (keys (:nodes new-state))))
              (catch :default e
                (println "Transaction failed with error:" (.-message e))))))))))

(deftest debug-via-dispatcher  
  (testing "Debug create-child via dispatcher"
    (let [store (atom {:past [] :present test-db :future []})]
      
      ;; Use dispatcher like the UI would
      (try
        (dispatcher/dispatch-intent! store :create-child-block {:cursor "p1"})
        (let [new-state (:present @store)
              p1-children (get-in new-state [:children-by-parent "p1"])
              node-count (count (:nodes new-state))]
          (println "After dispatcher:")
          (println "P1 children:" p1-children) 
          (println "Node count:" node-count "(was" (count (:nodes test-db)) ")")
          (println "New nodes:" (keys (:nodes new-state)))
          
          (is (> node-count (count (:nodes test-db))) "Should create a new node")
          (is (seq p1-children) "P1 should have children"))
        (catch :default e
          (println "Dispatcher failed:" (.-message e)))))))

(deftest debug-rendering
  (testing "Check if created nodes render properly"
    (let [store (atom {:past [] :present test-db :future []})]
      
      ;; Create a child
      (dispatcher/dispatch-intent! store :create-child-block {:cursor "p1"})
      
      ;; Try to render
      (let [new-state (:present @store)]
        (try
          (let [hiccup (renderer/render-node new-state "p1")]
            (println "Rendered hiccup for p1:" (pr-str hiccup))
            (is (some? hiccup) "Should render hiccup")
            ;; Check if children are included
            (is (>= (count hiccup) 3) "Should have tag, attributes, and content/children"))
          (catch :default e
            (println "Rendering failed:" (.-message e))))))))

;; Run the debug tests
(run-tests)