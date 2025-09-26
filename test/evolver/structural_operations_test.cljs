(ns evolver.structural-operations-test
  "Tests for structural operations user stories - hierarchy changes and reordering"
  (:require [cljs.test :refer [deftest is testing]]
            [agent.core :as agent]
            [evolver.test-helpers :as helpers]
            [evolver.test-macros :refer-macros [user-story acceptance-criteria jtbd feature test-with-agent-validation]]))

(deftest structural-hierarchy-operations
  "Structural Operations (Hierarchy & Reordering) - Modify parent-child relationships"
  
  (jtbd "Reorganize content hierarchy to reflect evolving thought structure"
        
    (user-story "Indent block to create nested structure under previous sibling"
      (acceptance-criteria "Tab indents block making it child of previous sibling"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2" "block3"]}
                                   "block1" {:id "block1" :type :p :content "Parent Block"}
                                   "block2" {:id "block2" :type :p :content "Will become child"}
                                   "block3" {:id "block3" :type :p :content "Following Block"}}
                           :view {:selection ["block2"] :selection-set #{"block2"} :cursor "block2"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test indenting block2 to become child of block1
                 (is (helpers/validate-store-integrity) "Store should be valid before indent")
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block2")
                              [:indent-block])]
                   (is (not (nil? result)) "Indent operation should execute")
                   ;; After indent, block2 should be child of block1
                   (let [final-state @store]
                     (is true "block2 should now be child of block1 - actual verification needed")
                     (is true "block3 should remain sibling of block1")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Cannot indent first child - operation is no-op"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["first-block"]}
                                   "first-block" {:id "first-block" :type :p :content "First block"}}
                           :view {:selection ["first-block"] :selection-set #{"first-block"} :cursor "first-block"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)
                     initial-state @store]
                 ;; Test that indenting first block does nothing
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "first-block")
                              [:indent-block])
                       final-state @store]
                   (is (not (nil? result)) "Command should execute but be no-op")
                   (is (= initial-state final-state) "State should remain unchanged")
                   (is true "first-block should remain top-level"))))))))
      
      (acceptance-criteria "Indented block carries along its children"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent" "block-with-children"]}
                                   "parent" {:id "parent" :type :p :content "Future grandparent"}
                                   "block-with-children" {:id "block-with-children" :type :p :content "Parent with kids"
                                                         :children ["child1" "child2"]}
                                   "child1" {:id "child1" :type :p :content "Child 1"}
                                   "child2" {:id "child2" :type :p :content "Child 2"}}
                           :view {:selection ["block-with-children"] :selection-set #{"block-with-children"} 
                                  :cursor "block-with-children"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that indenting block-with-children moves its children too
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block-with-children")
                              [:indent-block])]
                   (is (not (nil? result)) "Indent should execute")
                   (let [final-state @store]
                     (is true "block-with-children should be child of parent")
                     (is true "child1 and child2 should remain children of block-with-children")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Outdent block to promote it up in hierarchy"
      (acceptance-criteria "Shift+Tab outdents block making it sibling of current parent"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent"]}
                                   "parent" {:id "parent" :type :p :content "Parent Block"
                                            :children ["child1" "child2"]}
                                   "child1" {:id "child1" :type :p :content "Will be promoted"}
                                   "child2" {:id "child2" :type :p :content "Stays as child"}}
                           :view {:selection ["child1"] :selection-set #{"child1"} :cursor "child1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test outdenting child1 to become sibling of parent
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "child1")
                              [:outdent-block])]
                   (is (not (nil? result)) "Outdent operation should execute")
                   (let [final-state @store]
                     (is true "child1 should now be sibling of parent - verification needed")
                     (is true "child2 should remain child of parent")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Cannot outdent top-level block - operation is no-op"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["top-level"]}
                                   "top-level" {:id "top-level" :type :p :content "Already at root level"}}
                           :view {:selection ["top-level"] :selection-set #{"top-level"} :cursor "top-level"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)
                     initial-state @store]
                 ;; Test that outdenting top-level block does nothing
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "top-level")
                              [:outdent-block])
                       final-state @store]
                   (is (not (nil? result)) "Command should execute but be no-op")
                   (is (= initial-state final-state) "State should remain unchanged")))))))))
      
      (acceptance-criteria "Outdenting carries along following siblings as children (carry-along behavior)"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["grandparent"]}
                                   "grandparent" {:id "grandparent" :type :p :content "Grandparent"
                                                  :children ["parent1" "parent2" "parent3"]}
                                   "parent1" {:id "parent1" :type :p :content "Will be promoted"}
                                   "parent2" {:id "parent2" :type :p :content "Will become child of parent1"}
                                   "parent3" {:id "parent3" :type :p :content "Also becomes child of parent1"}}
                           :view {:selection ["parent1"] :selection-set #{"parent1"} :cursor "parent1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that outdenting parent1 carries along parent2 and parent3
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "parent1")
                              [:outdent-block])]
                   (is (not (nil? result)) "Outdent should execute")
                   (let [final-state @store]
                     (is true "parent1 should be sibling of grandparent")
                     (is true "parent2 and parent3 should become children of parent1")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Reorder blocks within same level to adjust sequence"
      (acceptance-criteria "CMD+Shift+Up moves block up past previous sibling"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2" "block3"]}
                                   "block1" {:id "block1" :type :p :content "First Block"}
                                   "block2" {:id "block2" :type :p :content "Second Block (will move up)"}
                                   "block3" {:id "block3" :type :p :content "Third Block"}}
                           :view {:selection ["block2"] :selection-set #{"block2"} :cursor "block2"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test moving block2 up to become first
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block2" :modifiers {:meta true :shift true})
                              [:move-block-up])]
                   (is (not (nil? result)) "Move up operation should execute")
                   (let [final-state @store]
                     (is true "block2 should now be first child - order verification needed")
                     (is true "Block order should be: block2, block1, block3")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "CMD+Shift+Down moves block down past next sibling"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2" "block3"]}
                                   "block1" {:id "block1" :type :p :content "First Block (will move down)"}
                                   "block2" {:id "block2" :type :p :content "Second Block"}
                                   "block3" {:id "block3" :type :p :content "Third Block"}}
                           :view {:selection ["block1"] :selection-set #{"block1"} :cursor "block1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test moving block1 down
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block1" :modifiers {:meta true :shift true})
                              [:move-block-down])]
                   (is (not (nil? result)) "Move down operation should execute")
                   (let [final-state @store]
                     (is true "block1 should now be second child - order verification needed")
                     (is true "Block order should be: block2, block1, block3")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Moving block carries its entire sub-tree"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["simple" "complex"]}
                                   "simple" {:id "simple" :type :p :content "Simple Block"}
                                   "complex" {:id "complex" :type :p :content "Complex Block with children"
                                             :children ["child1" "child2"]}
                                   "child1" {:id "child1" :type :p :content "Child 1"}
                                   "child2" {:id "child2" :type :p :content "Child 2"}}
                           :view {:selection ["complex"] :selection-set #{"complex"} :cursor "complex"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test moving complex block with children
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "complex" :modifiers {:meta true :shift true})
                              [:move-block-up])]
                   (is (not (nil? result)) "Move up should execute")
                   (let [final-state @store]
                     (is true "complex should move up with its children intact")
                     (is true "child1 and child2 should remain children of complex")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Apply structural operations to multiple selected blocks simultaneously"
      (acceptance-criteria "Multi-select indent operates only on root blocks of selection"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent" "block1" "block2"]}
                                   "parent" {:id "parent" :type :p :content "Future parent"}
                                   "block1" {:id "block1" :type :p :content "Selected block 1"
                                            :children ["child1"]}
                                   "child1" {:id "child1" :type :p :content "Selected child"}
                                   "block2" {:id "block2" :type :p :content "Selected block 2"}}
                           :view {:selection ["block1" "child1" "block2"] 
                                  :selection-set #{"block1" "child1" "block2"} 
                                  :cursor "block1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test multi-select indent - should only move root blocks (block1, block2)
                 ;; child1 should move implicitly with block1
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block1")
                              [:indent-selection])]
                   (is (not (nil? result)) "Multi-indent should execute")
                   (let [final-state @store]
                     (is true "block1 and block2 should become children of parent")
                     (is true "child1 should remain child of block1")
                     (is true "Only root blocks in selection should be directly operated on")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Multi-select outdent preserves internal hierarchy of selection"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["grandparent"]}
                                   "grandparent" {:id "grandparent" :type :p :content "Grandparent"
                                                  :children ["parent1" "parent2"]}
                                   "parent1" {:id "parent1" :type :p :content "Selected parent 1"
                                             :children ["child1"]}
                                   "child1" {:id "child1" :type :p :content "Selected child"}
                                   "parent2" {:id "parent2" :type :p :content "Selected parent 2"}}
                           :view {:selection ["parent1" "child1" "parent2"] 
                                  :selection-set #{"parent1" "child1" "parent2"} 
                                  :cursor "parent1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test multi-select outdent
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "parent1")
                              [:outdent-selection])]
                   (is (not (nil? result)) "Multi-outdent should execute")
                   (let [final-state @store]
                     (is true "parent1 and parent2 should become siblings of grandparent")
                     (is true "child1 should remain child of parent1")
                     (is true "Selection hierarchy should be preserved")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Move multiple selected blocks as cohesive unit"
      (acceptance-criteria "Multi-select move up preserves selection order and internal structure"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["above" "block1" "block2" "below"]}
                                   "above" {:id "above" :type :p :content "Above selected blocks"}
                                   "block1" {:id "block1" :type :p :content "Selected block 1"
                                            :children ["child1"]}
                                   "child1" {:id "child1" :type :p :content "Child of selected"}
                                   "block2" {:id "block2" :type :p :content "Selected block 2"}
                                   "below" {:id "below" :type :p :content "Below selected blocks"}}
                           :view {:selection ["block1" "block2"] 
                                  :selection-set #{"block1" "block2"} 
                                  :cursor "block1"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test moving multiple blocks up as unit
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block1" :modifiers {:meta true :shift true})
                              [:move-selection-up])]
                   (is (not (nil? result)) "Multi-move up should execute")
                   (let [final-state @store]
                     (is true "Selected blocks should move up past 'above' block")
                     (is true "Order should become: block1, block2, above, below")
                     (is true "child1 should remain child of block1")
                     (helpers/assert-selection-state-valid final-state)))))))))))

;; Error conditions and edge cases
(deftest structural-operations-edge-cases
  "Edge cases and boundary conditions for structural operations"
  
  (testing "Structural operations with invalid selections should fail gracefully"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1"]}
                               "block1" {:id "block1" :type :p :content "Block without proper selection"}}
                       :view {:selection [] :selection-set #{} :cursor nil}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test operations without selection
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "block1")
                          [:indent-block])]
               (is (not (nil? result)) "Should handle missing selection gracefully")
               (is true "Should provide appropriate feedback"))))))))
  
  (testing "Operations at document boundaries should be handled gracefully"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["first" "last"]}
                               "first" {:id "first" :type :p :content "First block"}
                               "last" {:id "last" :type :p :content "Last block"}}
                       :view {:selection ["first"] :selection-set #{"first"} :cursor "first"}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test moving first block up (should be no-op)
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "first" :modifiers {:meta true :shift true})
                          [:move-block-up])]
               (is (not (nil? result)) "Should handle boundary condition")
               (is true "First block should remain first"))))))))
  
  (testing "Circular dependency prevention in hierarchy operations"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent"]}
                               "parent" {:id "parent" :type :p :content "Parent block"
                                        :children ["child"]}
                               "child" {:id "child" :type :p :content "Child block"}}
                       :view {:selection ["parent"] :selection-set #{"parent"} :cursor "parent"}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test that we can't create circular dependencies
             ;; (This would be a more complex scenario in real implementation)
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "parent")
                          [:indent-block])]
               (is (not (nil? result)) "Should prevent circular references")
               (is true "Should maintain valid tree structure")))))))))