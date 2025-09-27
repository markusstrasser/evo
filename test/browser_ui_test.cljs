(ns browser-ui-test
  "Test actual browser DOM rendering - requires browser context"
  (:require [cljs.test :refer [deftest testing is async]]
            [evolver.core :as core]
            [evolver.intents :as intents]
            [evolver.dispatcher :as dispatcher]
            [evolver.renderer :as renderer]))

(deftest test-create-child-in-browser
  (testing "Create child and verify actual DOM content"
    (async done
           ;; Get the current store
           (let [store @core/store
                 initial-state (:present store)]
             
             (js/console.log "=== BEFORE CREATE CHILD ===")
             (js/console.log "Initial store:" store)
             (js/console.log "Initial nodes:" (:nodes initial-state))
             
             ;; Create a child on a known node
             (let [root-children (get-in initial-state [:children-by-parent "root"])
                   first-child (first root-children)]
               
               (when first-child
                 (js/console.log "Creating child for node:" first-child)
                 
                 ;; Dispatch the create-child intent
                 (dispatcher/dispatch-intent! core/store :create-child-block {:cursor first-child})
                 
                 ;; Check the updated state
                 (js/setTimeout 
                  (fn []
                    (let [new-store @core/store
                          new-state (:present new-store)]
                      
                      (js/console.log "=== AFTER CREATE CHILD ===")
                      (js/console.log "New store:" new-store)
                      (js/console.log "New nodes:" (:nodes new-state))
                      
                      ;; Check children of the target node
                      (let [target-children (get-in new-state [:children-by-parent first-child])]
                        (js/console.log "Children of" first-child ":" target-children)
                        
                        ;; Check each child's data
                        (doseq [child-id target-children]
                          (let [child-node (get-in new-state [:nodes child-id])]
                            (js/console.log "Child" child-id "node data:" child-node)
                            (js/console.log "Child" child-id "text:" (get-in child-node [:props :text]))))
                        
                        ;; Check actual DOM
                        (js/console.log "=== ACTUAL DOM CONTENT ===")
                        (let [dom-element (.querySelector js/document (str "[data-node-id=\"" first-child "\"]"))]
                          (if dom-element
                            (do
                              (js/console.log "Found DOM element for" first-child)
                              (js/console.log "Element innerHTML:" (.-innerHTML dom-element)))
                            (js/console.log "No DOM element found for" first-child)))
                        
                        ;; Check the whole app DOM
                        (let [app-element (.querySelector js/document ".app")]
                          (when app-element
                            (js/console.log "Full app innerHTML:" (.-innerHTML app-element))))
                        
                        (done)))
                  100))))))))) 

(deftest test-render-hiccup-vs-dom
  (testing "Compare hiccup output with actual DOM"
    (async done
           (let [store @core/store
                 current-state (:present store)]
             
             (js/console.log "=== HICCUP VS DOM COMPARISON ===")
             
             ;; Render hiccup
             (let [hiccup (renderer/render current-state)]
               (js/console.log "Generated hiccup:" hiccup)
               
               ;; Check actual DOM
               (let [app-dom (.querySelector js/document ".app")]
                 (if app-dom
                   (do
                     (js/console.log "Actual DOM structure:" (.-outerHTML app-dom))
                     
                     ;; Look for .node elements specifically
                     (let [node-elements (.querySelectorAll js/document ".node")]
                       (js/console.log "Found" (.-length node-elements) "node elements")
                       (.forEach node-elements 
                                 (fn [el idx]
                                   (js/console.log "Node" idx "innerHTML:" (.-innerHTML el))
                                   (js/console.log "Node" idx "textContent:" (.-textContent el))))))
                   (js/console.log "No .app element found in DOM")))
             
             (done)))))

;; Manual test function that can be called from browser console
(defn ^:export test-create-child-manual []
  (js/console.log "=== MANUAL CREATE CHILD TEST ===")
  
  ;; Get current state
  (let [store @core/store
        current-state (:present store)
        root-children (get-in current-state [:children-by-parent "root"])
        target-node (first root-children)]
    
    (js/console.log "Current nodes:" (keys (:nodes current-state)))
    (js/console.log "Root children:" root-children)
    (js/console.log "Target node:" target-node)
    
    (when target-node
      ;; Create child
      (dispatcher/dispatch-intent! core/store :create-child-block {:cursor target-node})
      
      ;; Log results
      (js/setTimeout
       (fn []
         (let [new-state (:present @core/store)
               target-children (get-in new-state [:children-by-parent target-node])]
           (js/console.log "After create-child:")
           (js/console.log "Target children:" target-children)
           
           (doseq [child-id target-children]
             (let [child-data (get-in new-state [:nodes child-id])]
               (js/console.log "Child" child-id ":" child-data)))
           
           ;; Check DOM
           (let [nodes (.querySelectorAll js/document ".node")]
             (js/console.log "DOM nodes found:" (.-length nodes))
             (.forEach nodes (fn [el i] 
                               (js/console.log "DOM node" i "content:" (.-textContent el)))))))
       50))))

(js/console.log "Browser UI tests loaded. Call (browser-ui-test/test-create-child-manual) to run manual test.")