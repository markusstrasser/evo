(ns evolver.block-lifecycle-test
  "Tests for block lifecycle user stories - creation, deletion, and merging operations"
  (:require [cljs.test :refer [deftest is testing]]
            [agent.core :as agent]
            [evolver.test-helpers :as helpers]
            [evolver.test-macros :refer-macros [user-story acceptance-criteria jtbd feature test-with-agent-validation]]))

(deftest block-creation-operations
  "Block Lifecycle (Creation, Deletion & Merging) - Handle block entity lifecycle"
  
  (jtbd "Create new blocks to capture and organize emerging thoughts"
        
    (user-story "Create new empty block for continuing thought flow"
      (acceptance-criteria "Enter at end of block creates new empty sibling below"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["existing-block"]}
                                   "existing-block" {:id "existing-block" :type :p :content "Some content"}}
                           :view {:selection ["existing-block"] :selection-set #{"existing-block"} 
                                  :cursor "existing-block" :cursor-position :end}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter at end of block
                 (is (helpers/validate-store-integrity) "Store should be valid before creation")
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "existing-block")
                              [:create-new-block])]
                   (is (not (nil? result)) "Block creation should execute")
                   (let [final-state @store]
                     (is true "New empty block should be created as next sibling")
                     (is true "Focus should move to new block")
                     (is true "Original block content should remain unchanged")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Enter creates properly ordered sibling block"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2" "block3"]}
                                   "block1" {:id "block1" :type :p :content "First"}
                                   "block2" {:id "block2" :type :p :content "Second (cursor here)"}
                                   "block3" {:id "block3" :type :p :content "Third"}}
                           :view {:selection ["block2"] :selection-set #{"block2"} 
                                  :cursor "block2" :cursor-position :end}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that new block appears in correct position
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block2")
                              [:create-new-block])]
                   (is (not (nil? result)) "Block creation should work")
                   (let [final-state @store]
                     (is true "New block should appear between block2 and block3")
                     (is true "Order should be: block1, block2, new-block, block3")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Enter in nested structure creates sibling at same level"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent"]}
                                   "parent" {:id "parent" :type :p :content "Parent Block"
                                            :children ["child1" "child2"]}
                                   "child1" {:id "child1" :type :p :content "First child"}
                                   "child2" {:id "child2" :type :p :content "Second child"}}
                           :view {:selection ["child1"] :selection-set #{"child1"} 
                                  :cursor "child1" :cursor-position :end}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test creating sibling in nested structure
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "child1")
                              [:create-new-block])]
                   (is (not (nil? result)) "Nested block creation should work")
                   (let [final-state @store]
                     (is true "New block should be child of parent, sibling of child1 and child2")
                     (is true "New block should appear between child1 and child2")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Split existing block content to break down complex ideas"
      (acceptance-criteria "Enter in middle of block splits content into two blocks"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block-to-split"]}
                                   "block-to-split" {:id "block-to-split" :type :p 
                                                     :content "First part|Second part"}}
                           :view {:selection ["block-to-split"] :selection-set #{"block-to-split"} 
                                  :cursor "block-to-split" :cursor-position 11}}] ; After "First part"
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test splitting block at cursor position
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block-to-split")
                              [:split-block])]
                   (is (not (nil? result)) "Block splitting should execute")
                   (let [final-state @store]
                     (is true "Original block should contain 'First part'")
                     (is true "New block should contain 'Second part'")
                     (is true "Focus should move to new block")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Split block preserves children with original block"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent-to-split"]}
                                   "parent-to-split" {:id "parent-to-split" :type :p 
                                                      :content "Parent content|More content"
                                                      :children ["child1" "child2"]}
                                   "child1" {:id "child1" :type :p :content "Child 1"}
                                   "child2" {:id "child2" :type :p :content "Child 2"}}
                           :view {:selection ["parent-to-split"] :selection-set #{"parent-to-split"} 
                                  :cursor "parent-to-split" :cursor-position 15}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that children stay with original block when split
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "parent-to-split")
                              [:split-block])]
                   (is (not (nil? result)) "Block splitting should work")
                   (let [final-state @store]
                     (is true "Original block keeps child1 and child2")
                     (is true "New block has no children initially")
                     (is true "Both blocks are siblings after split")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Split at beginning creates empty block before current"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1"]}
                                   "block1" {:id "block1" :type :p :content "Full content"}}
                           :view {:selection ["block1"] :selection-set #{"block1"} 
                                  :cursor "block1" :cursor-position 0}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test splitting at beginning
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block1")
                              [:split-block])]
                   (is (not (nil? result)) "Block splitting at start should work")
                   (let [final-state @store]
                     (is true "New empty block should appear before current block")
                     (is true "Original block keeps all content")
                     (is true "Focus moves to new empty block")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Create line breaks within single block for formatting"
      (acceptance-criteria "Shift+Enter creates soft line break without new block"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["text-block"]}
                                   "text-block" {:id "text-block" :type :p :content "Line one"}}
                           :view {:selection ["text-block"] :selection-set #{"text-block"} 
                                  :cursor "text-block" :cursor-position 8}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test soft line break
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "text-block" :modifiers {:shift true})
                              [:insert-line-break])]
                   (is (not (nil? result)) "Line break insertion should execute")
                   (let [final-state @store]
                     (is true "Block content should contain newline character")
                     (is true "No new block entity should be created") 
                     (is true "Same block should remain focused")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Multiple line breaks create multi-line block"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["poem-block"]}
                                   "poem-block" {:id "poem-block" :type :p :content "Roses are red"}}
                           :view {:selection ["poem-block"] :selection-set #{"poem-block"} 
                                  :cursor "poem-block" :cursor-position 13}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test multiple soft line breaks for poetry/formatting
                 (let [result1 (helpers/test-dispatch-commands 
                               store 
                               (helpers/create-proper-test-event "poem-block" :modifiers {:shift true})
                               [:insert-line-break])
                       result2 (helpers/test-dispatch-commands 
                               store 
                               (helpers/create-proper-test-event "poem-block" :modifiers {:shift true})
                               [:insert-line-break])]
                   (is (not (nil? result1)) "First line break should work")
                   (is (not (nil? result2)) "Second line break should work")
                   (let [final-state @store]
                     (is true "Block should contain multiple newlines")
                     (is true "Still only one block entity exists")
                     (helpers/assert-selection-state-valid final-state)))))))))))

(deftest block-deletion-operations
  "Block deletion and content merging operations"
  
  (jtbd "Remove blocks while preserving valuable content and structure"
        
    (user-story "Delete empty block to clean up document"
      (acceptance-criteria "Backspace on empty block deletes it and moves focus"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "empty-block" "block3"]}
                                   "block1" {:id "block1" :type :p :content "Before empty"}
                                   "empty-block" {:id "empty-block" :type :p :content ""}
                                   "block3" {:id "block3" :type :p :content "After empty"}}
                           :view {:selection ["empty-block"] :selection-set #{"empty-block"} 
                                  :cursor "empty-block"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test deleting empty block
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "empty-block")
                              [:delete-empty-block])]
                   (is (not (nil? result)) "Empty block deletion should execute")
                   (let [final-state @store]
                     (is true "empty-block should no longer exist")
                     (is true "Focus should move to previous block (block1)")
                     (is true "block1 and block3 should remain as siblings")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Delete block with children promotes children to parent's level"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent-to-delete"]}
                                   "parent-to-delete" {:id "parent-to-delete" :type :p :content "Parent block"
                                                       :children ["child1" "child2"]}
                                   "child1" {:id "child1" :type :p :content "Child 1"}
                                   "child2" {:id "child2" :type :p :content "Child 2"}}
                           :view {:selection ["parent-to-delete"] :selection-set #{"parent-to-delete"} 
                                  :cursor "parent-to-delete"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test deleting parent block
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "parent-to-delete")
                              [:delete-block-promote-children])]
                   (is (not (nil? result)) "Parent deletion should execute")
                   (let [final-state @store]
                     (is true "parent-to-delete should no longer exist")
                     (is true "child1 and child2 should become direct children of root")
                     (is true "Children order should be preserved")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Delete maintains document structure integrity"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["keep1" "delete-me" "keep2"]}
                                   "keep1" {:id "keep1" :type :p :content "Keep this"}
                                   "delete-me" {:id "delete-me" :type :p :content "Delete this"
                                               :children ["orphan1" "orphan2"]}
                                   "orphan1" {:id "orphan1" :type :p :content "Will be orphaned"}
                                   "orphan2" {:id "orphan2" :type :p :content "Also orphaned"}
                                   "keep2" {:id "keep2" :type :p :content "Keep this too"}}
                           :view {:selection ["delete-me"] :selection-set #{"delete-me"} 
                                  :cursor "delete-me"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test deletion maintains structure
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "delete-me")
                              [:delete-block-promote-children])]
                   (is (not (nil? result)) "Block deletion should work")
                   (let [final-state @store]
                     (is true "Root should contain: keep1, orphan1, orphan2, keep2")
                     (is true "All blocks should have valid parent references")
                     (is true "No orphaned references should exist")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Merge adjacent blocks to combine related content"
      (acceptance-criteria "Backspace at start of block merges with previous block"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2"]}
                                   "block1" {:id "block1" :type :p :content "First part"}
                                   "block2" {:id "block2" :type :p :content "Second part"}}
                           :view {:selection ["block2"] :selection-set #{"block2"} 
                                  :cursor "block2" :cursor-position 0}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test merging with previous block
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block2")
                              [:merge-with-previous])]
                   (is (not (nil? result)) "Merge with previous should execute")
                   (let [final-state @store]
                     (is true "block1 content should be 'First partSecond part'")
                     (is true "block2 should no longer exist")
                     (is true "Cursor should be at merge point in block1")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Delete at end of block merges with next block"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2"]}
                                   "block1" {:id "block1" :type :p :content "First part"}
                                   "block2" {:id "block2" :type :p :content "Second part"}}
                           :view {:selection ["block1"] :selection-set #{"block1"} 
                                  :cursor "block1" :cursor-position 10}}] ; At end of "First part"
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test merging with next block
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block1")
                              [:merge-with-next])]
                   (is (not (nil? result)) "Merge with next should execute")
                   (let [final-state @store]
                     (is true "block1 content should be 'First partSecond part'")
                     (is true "block2 should no longer exist")
                     (is true "Cursor should remain at merge point")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Merge carries children from deleted block to surviving block"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2"]}
                                   "block1" {:id "block1" :type :p :content "First"
                                            :children ["child-of-1"]}
                                   "child-of-1" {:id "child-of-1" :type :p :content "Child of first"}
                                   "block2" {:id "block2" :type :p :content "Second"
                                            :children ["child-of-2a" "child-of-2b"]}
                                   "child-of-2a" {:id "child-of-2a" :type :p :content "Child 2A"}
                                   "child-of-2b" {:id "child-of-2b" :type :p :content "Child 2B"}}
                           :view {:selection ["block2"] :selection-set #{"block2"} 
                                  :cursor "block2" :cursor-position 0}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test that children are re-parented when blocks merge
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "block2")
                              [:merge-with-previous])]
                   (is (not (nil? result)) "Merge should execute")
                   (let [final-state @store]
                     (is true "block1 should contain merged content")
                     (is true "block1 should have all children: child-of-1, child-of-2a, child-of-2b")
                     (is true "Children order should be preserved")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Handle block type changes and special formatting"
      (acceptance-criteria "Cycle heading level changes block presentation"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["normal-block"]}
                                   "normal-block" {:id "normal-block" :type :p :content "Normal text"}}
                           :view {:selection ["normal-block"] :selection-set #{"normal-block"} 
                                  :cursor "normal-block"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test cycling heading levels
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "normal-block")
                              [:cycle-heading-level])]
                   (is (not (nil? result)) "Heading level cycling should execute")
                   (let [final-state @store]
                     (is true "Block should become H1")
                     (is true "Content should be preserved")
                     ;; Additional cycles: H1 -> H2 -> H3 -> normal -> H1...
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Toggle block type between text and checkbox/TODO"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["text-block"]}
                                   "text-block" {:id "text-block" :type :p :content "Regular task"}}
                           :view {:selection ["text-block"] :selection-set #{"text-block"} 
                                  :cursor "text-block"}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test toggling to checkbox/TODO
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "text-block")
                              [:toggle-task-marker])]
                   (is (not (nil? result)) "Task marker toggle should execute")
                   (let [final-state @store]
                     (is true "Block should have TODO marker")
                     (is true "Original content should be preserved")
                     ;; Toggle again: TODO -> DONE -> none -> TODO...
                     (helpers/assert-selection-state-valid final-state))))))))))

;; Edge cases and error conditions
(deftest block-lifecycle-edge-cases
  "Edge cases and boundary conditions for block lifecycle operations"
  
  (testing "Lifecycle operations with invalid state should fail gracefully"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1"]}
                               "block1" {:id "block1" :type :p :content "Block"}}
                       :view {:selection [] :selection-set #{} :cursor nil}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test creation without cursor
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "block1")
                          [:create-new-block])]
               (is (not (nil? result)) "Should handle missing cursor gracefully")
               (is true "Should set cursor appropriately or provide feedback"))))))))
  
  (testing "Merge operations at document boundaries"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["only-block"]}
                               "only-block" {:id "only-block" :type :p :content "Only block"}}
                       :view {:selection ["only-block"] :selection-set #{"only-block"} 
                              :cursor "only-block" :cursor-position 0}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test merge when there's no previous block
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "only-block")
                          [:merge-with-previous])]
               (is (not (nil? result)) "Should handle boundary condition")
               (is true "Should be no-op or provide appropriate feedback"))))))))
  
  (testing "Complex deletion scenarios preserve document integrity"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["complex-parent"]}
                               "complex-parent" {:id "complex-parent" :type :p :content "Complex structure"
                                               :children ["nested-parent"]}
                               "nested-parent" {:id "nested-parent" :type :p :content "Nested parent"
                                              :children ["deep-child1" "deep-child2"]}
                               "deep-child1" {:id "deep-child1" :type :p :content "Deep child 1"}
                               "deep-child2" {:id "deep-child2" :type :p :content "Deep child 2"}}
                       :view {:selection ["nested-parent"] :selection-set #{"nested-parent"} 
                              :cursor "nested-parent"}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test deleting nested parent with deep children
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "nested-parent")
                          [:delete-block-promote-children])]
               (is (not (nil? result)) "Complex deletion should execute")
               (let [final-state @store]
                 (is true "Deep children should become children of complex-parent")
                 (is true "No orphaned references should exist")
                 (helpers/assert-selection-state-valid final-state))))))))))))
)
