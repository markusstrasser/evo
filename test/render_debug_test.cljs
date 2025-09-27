(ns render-debug-test
  "Debug why created nodes render without content"
  (:require [cljs.test :refer [deftest testing is run-tests]]
            [evolver.kernel :as k]
            [evolver.intents :as intents]
            [evolver.dispatcher :as dispatcher]
            [evolver.renderer :as renderer]))

(def test-db
  (k/update-derived
   {:nodes {"root" {:type :div}
            "p1" {:type :p :props {:text "Parent block"}}}
    :children-by-parent {"root" ["p1"]
                         "p1" []}
    :view {:selection ["p1"]}}))

(deftest debug-node-creation-and-rendering
  (testing "Create child and check node data vs rendering"
    (let [store (atom {:past [] :present test-db :future []})]
      
      ;; Create a child
      (dispatcher/dispatch-intent! store :create-child-block {:cursor "p1"})
      
      (let [new-state (:present @store)
            p1-children (get-in new-state [:children-by-parent "p1"])
            child-id (first p1-children)]
        
        (println "=== AFTER CREATE CHILD ===")
        (println "P1 children:" p1-children)
        (println "Child ID:" child-id)
        
        (when child-id
          (let [child-node (get-in new-state [:nodes child-id])]
            (println "Child node data:" (pr-str child-node))
            (println "Child type:" (:type child-node))
            (println "Child props:" (:props child-node))
            (println "Child text:" (get-in child-node [:props :text]))
            
            ;; Test rendering the child
            (let [child-hiccup (renderer/render-node new-state child-id)]
              (println "Child hiccup:" (pr-str child-hiccup))
              (is (some? child-hiccup) "Child should render")
              
              ;; Check if text content is in hiccup
              (let [hiccup-str (pr-str child-hiccup)]
                (is (clojure.string/includes? hiccup-str "New child") 
                    "Hiccup should contain 'New child' text")))
            
            ;; Test rendering the parent with child
            (let [parent-hiccup (renderer/render-node new-state "p1")]
              (println "Parent hiccup:" (pr-str parent-hiccup))
              (let [hiccup-str (pr-str parent-hiccup)]
                (is (clojure.string/includes? hiccup-str "New child")
                    "Parent hiccup should include child content")))))))))

(deftest debug-multiple-creates  
  (testing "What happens when we create multiple children"
    (let [store (atom {:past [] :present test-db :future []})]
      
      ;; Create 4 children like in the HTML output
      (dotimes [i 4]
        (dispatcher/dispatch-intent! store :create-child-block {:cursor "p1"}))
      
      (let [new-state (:present @store)
            p1-children (get-in new-state [:children-by-parent "p1"])
            all-nodes (:nodes new-state)]
        
        (println "=== AFTER 4 CREATE-CHILD CALLS ===")
        (println "P1 children:" p1-children)
        (println "Total nodes:" (count all-nodes))
        
        ;; Check each child
        (doseq [[i child-id] (map-indexed vector p1-children)]
          (let [child-node (get-in new-state [:nodes child-id])]
            (println (str "Child " i " (" child-id "): " (pr-str child-node)))))
        
        ;; Render the parent
        (let [parent-hiccup (renderer/render-node new-state "p1")]
          (println "Parent with 4 children hiccup:")
          (println (pr-str parent-hiccup)))))))

(deftest debug-empty-node-creation
  (testing "Check if some other intent is creating empty nodes"
    (let [store (atom {:past [] :present test-db :future []})]
      
      ;; Try different intents that might create empty nodes
      (println "=== TESTING DIFFERENT NODE CREATION INTENTS ===")
      
      ;; Test create-sibling-below
      (dispatcher/dispatch-intent! store :create-sibling-below {:cursor "p1"})
      (let [state1 (:present @store)
            new-nodes (keys (apply dissoc (:nodes state1) (keys (:nodes test-db))))]
        (println "After create-sibling-below, new nodes:" new-nodes)
        (doseq [node-id new-nodes]
          (println "Node" node-id ":" (pr-str (get-in state1 [:nodes node-id])))))
      
      ;; Reset and test enter-new-block
      (reset! store {:past [] :present test-db :future []})
      (dispatcher/dispatch-intent! store :enter-new-block {:cursor "p1" :cursor-position nil})
      (let [state2 (:present @store)
            new-nodes (keys (apply dissoc (:nodes state2) (keys (:nodes test-db))))]
        (println "After enter-new-block, new nodes:" new-nodes)
        (doseq [node-id new-nodes]
          (println "Node" node-id ":" (pr-str (get-in state2 [:nodes node-id]))))))))

(run-tests)