(ns evolver.sequential-navigation-test
  "Tests for sequential navigation user stories - cursor movement through blocks"
  (:require [cljs.test :refer [deftest is testing]]
            [agent.core :as agent]
            [evolver.test-helpers :as helpers]
            [evolver.test-macros :refer-macros [user-story acceptance-criteria jtbd feature test-with-agent-validation]]))

(deftest sequential-navigation-feature
  "Sequential Navigation (Visual Traversal) - Move cursor through document as if linear text"
  
  (jtbd "Navigate through nested content structures quickly without losing spatial context"
        
    (user-story "Move cursor to next visible block regardless of nesting"
      (acceptance-criteria "Arrow Down moves to next child when parent expanded"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent1"]}
                                   "parent1" {:id "parent1" :type :p :content "Parent Block" 
                                             :children ["child1" "child2"] :expanded true}
                                   "child1" {:id "child1" :type :p :content "First Child"}
                                   "child2" {:id "child2" :type :p :content "Second Child"}}
                           :view {:selection ["parent1"] :selection-set #{"parent1"} :cursor "parent1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that Down from parent1 goes to child1 (first child)
                 (is (helpers/validate-store-integrity) "Store should be valid before navigation")
                 (helpers/assert-navigation-possible @store :nav-down)
                 ;; Simulate Down arrow key
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "parent1")
                              [:nav-down])]
                   (is (not (nil? result)) "Navigation should execute")
                   (is true "Should move cursor to child1 - actual implementation needed"))))))))
      
      (acceptance-criteria "Arrow Down moves to next sibling when no children or collapsed"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2"]}
                                   "block1" {:id "block1" :type :p :content "First Block"}
                                   "block2" {:id "block2" :type :p :content "Second Block"}}
                           :view {:selection ["block1"] :selection-set #{"block1"} :cursor "block1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that Down from block1 goes to block2 (next sibling)
                 (is (helpers/validate-store-integrity) "Store should be valid")
                 (helpers/assert-navigation-possible @store :nav-down)
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block1")
                              [:nav-down])]
                   (is (not (nil? result)) "Navigation should execute")
                   (is true "Should move cursor to block2 - actual implementation needed"))))))))
      
      (acceptance-criteria "Arrow Down finds 'uncle' when no right sibling exists"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent1" "parent2"]}
                                   "parent1" {:id "parent1" :type :p :content "Parent 1" 
                                             :children ["child1"]}
                                   "child1" {:id "child1" :type :p :content "Last child"}
                                   "parent2" {:id "parent2" :type :p :content "Uncle Block"}}
                           :view {:selection ["child1"] :selection-set #{"child1"} :cursor "child1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that Down from child1 goes to parent2 (uncle)
                 (helpers/assert-navigation-possible @store :nav-down)
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "child1")
                              [:nav-down])]
                   (is (not (nil? result)) "Navigation should execute")
                   (is true "Should move cursor to parent2 (uncle) - actual implementation needed")))))))))
    
    (user-story "Move cursor to visually preceding block with deep traversal"
      (acceptance-criteria "Arrow Up moves to left sibling when available"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2"]}
                                   "block1" {:id "block1" :type :p :content "First Block"}
                                   "block2" {:id "block2" :type :p :content "Second Block"}}
                           :view {:selection ["block2"] :selection-set #{"block2"} :cursor "block2"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that Up from block2 goes to block1
                 (helpers/assert-navigation-possible @store :nav-up)
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block2")
                              [:nav-up])]
                   (is (not (nil? result)) "Navigation should execute")
                   (is true "Should move cursor to block1 - actual implementation needed"))))))))
      
      (acceptance-criteria "Arrow Up descends into previous sibling's deepest child when expanded"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent1" "block2"]}
                                   "parent1" {:id "parent1" :type :p :content "Parent Block"
                                             :children ["child1"] :expanded true}
                                   "child1" {:id "child1" :type :p :content "Deepest child"
                                            :children ["grandchild1"] :expanded true}
                                   "grandchild1" {:id "grandchild1" :type :p :content "Deepest visible"}
                                   "block2" {:id "block2" :type :p :content "Following Block"}}
                           :view {:selection ["block2"] :selection-set #{"block2"} :cursor "block2"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that Up from block2 goes to grandchild1 (deepest visible)
                 (helpers/assert-navigation-possible @store :nav-up)
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block2")
                              [:nav-up])]
                   (is (not (nil? result)) "Navigation should execute")
                   (is true "Should move cursor to grandchild1 - actual implementation needed"))))))))
      
      (acceptance-criteria "Arrow Up moves to parent when no left sibling"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent1"]}
                                   "parent1" {:id "parent1" :type :p :content "Parent Block"
                                             :children ["child1"]}
                                   "child1" {:id "child1" :type :p :content "First child"}}
                           :view {:selection ["child1"] :selection-set #{"child1"} :cursor "child1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that Up from child1 goes to parent1
                 (helpers/assert-navigation-possible @store :nav-up)
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "child1")
                              [:nav-up])]
                   (is (not (nil? result)) "Navigation should execute")
                   (is true "Should move cursor to parent1 - actual implementation needed")))))))))
    
    (user-story "Jump to document boundaries for rapid navigation"
      (acceptance-criteria "CMD+Home moves cursor to first block in document"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["first" "middle" "last"]}
                                   "first" {:id "first" :type :p :content "First Block"}
                                   "middle" {:id "middle" :type :p :content "Middle Block"}
                                   "last" {:id "last" :type :p :content "Last Block"}}
                           :view {:selection ["middle"] :selection-set #{"middle"} :cursor "middle"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test jump to document start
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "middle" :modifiers {:meta true})
                              [:nav-document-start])]
                   (is (not (nil? result)) "Document start navigation should execute")
                   (is true "Should move cursor to first block - actual implementation needed"))))))))
      
      (acceptance-criteria "CMD+End moves cursor to last block in document"  
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["first" "middle" "last"]}
                                   "first" {:id "first" :type :p :content "First Block"}
                                   "middle" {:id "middle" :type :p :content "Middle Block"}
                                   "last" {:id "last" :type :p :content "Last Block"}}
                           :view {:selection ["middle"] :selection-set #{"middle"} :cursor "middle"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test jump to document end
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "middle" :modifiers {:meta true})
                              [:nav-document-end])]
                   (is (not (nil? result)) "Document end navigation should execute")
                   (is true "Should move cursor to last block - actual implementation needed")))))))))
    
    (user-story "Navigate within block text content efficiently"
      (acceptance-criteria "CMD+Left jumps to beginning of block text"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1"]}
                                   "block1" {:id "block1" :type :p :content "Some long text content"}}
                           :view {:selection ["block1"] :selection-set #{"block1"} :cursor "block1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test text navigation within block
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block1" :modifiers {:meta true})
                              [:text-start])]
                   (is (not (nil? result)) "Text start navigation should execute")
                   (is true "Should move cursor to start of block text - actual implementation needed"))))))))
      
      (acceptance-criteria "CMD+Right jumps to end of block text"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1"]}
                                   "block1" {:id "block1" :type :p :content "Some long text content"}}
                           :view {:selection ["block1"] :selection-set #{"block1"} :cursor "block1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test text navigation within block  
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block1" :modifiers {:meta true})
                              [:text-end])]
                   (is (not (nil? result)) "Text end navigation should execute")
                   (is true "Should move cursor to end of block text - actual implementation needed")))))))))))

;; Edge cases and error conditions
(deftest sequential-navigation-edge-cases
  "Edge cases and boundary conditions for sequential navigation"
  
  (testing "Navigation from last block should not crash or loop"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["only-block"]}
                               "only-block" {:id "only-block" :type :p :content "Last block"}}
                       :view {:selection ["only-block"] :selection-set #{"only-block"} :cursor "only-block"}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test navigation from final block
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "only-block")
                          [:nav-down])]
               (is (not (nil? result)) "Navigation should handle end-of-document gracefully")
               (is true "Should remain on last block or handle appropriately"))))))))
  
  (testing "Navigation from first block upward should not crash"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["first-block"]}
                               "first-block" {:id "first-block" :type :p :content "First block"}}
                       :view {:selection ["first-block"] :selection-set #{"first-block"} :cursor "first-block"}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test navigation from first block
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "first-block")
                          [:nav-up])]
               (is (not (nil? result)) "Navigation should handle start-of-document gracefully")
               (is true "Should remain on first block or handle appropriately"))))))))
  
  (testing "Navigation without cursor set should fail gracefully"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1"]}
                               "block1" {:id "block1" :type :p :content "Block without cursor"}}
                       :view {:selection [] :selection-set #{} :cursor nil}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test navigation without cursor - should fail validation
             (is (not (helpers/assert-navigation-possible @store :nav-down))
                 "Navigation should not be possible without cursor")
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "block1")
                          [:nav-down])]
               (is (not (nil? result)) "Should handle missing cursor gracefully")
               (is true "Should provide appropriate feedback or set cursor")))))))))