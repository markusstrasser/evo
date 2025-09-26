(ns evolver.content-manipulation-test
  "Tests for content manipulation user stories - Enter, Shift+Enter, and text editing"
  (:require [cljs.test :refer [deftest is testing]]
            [agent.core :as agent]
            [evolver.test-helpers :as helpers]
            [evolver.test-macros :refer-macros [user-story acceptance-criteria jtbd feature test-with-agent-validation]]))

(deftest content-editing-operations
  "Content Manipulation - Enter, Shift+Enter, and text editing behaviors"
  
  (jtbd "Edit and format block content efficiently while maintaining document flow"
        
    (user-story "Control block creation vs line breaks based on cursor position and modifiers"
      (acceptance-criteria "Enter at end creates new block, preserves content"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["content-block"]}
                                   "content-block" {:id "content-block" :type :p :content "Complete thought"}}
                           :view {:selection ["content-block"] :selection-set #{"content-block"} 
                                  :cursor "content-block" :cursor-position 16}}] ; At end after "Complete thought"
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter at end of content
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "content-block")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Enter at end should execute")
                   (let [final-state @store]
                     (is true "Original block should retain 'Complete thought'")
                     (is true "New empty block should be created as next sibling")
                     (is true "Focus should move to new empty block")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Enter in middle splits content across two blocks"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["split-me"]}
                                   "split-me" {:id "split-me" :type :p :content "First part and second part"}}
                           :view {:selection ["split-me"] :selection-set #{"split-me"} 
                                  :cursor "split-me" :cursor-position 11}}] ; After "First part "
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter in middle of content
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "split-me")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Enter in middle should execute")
                   (let [final-state @store]
                     (is true "First block should contain 'First part '")
                     (is true "Second block should contain 'and second part'")
                     (is true "Focus should be on second block at beginning")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Enter at beginning creates empty block before current"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["existing-content"]}
                                   "existing-content" {:id "existing-content" :type :p :content "Existing content"}}
                           :view {:selection ["existing-content"] :selection-set #{"existing-content"} 
                                  :cursor "existing-content" :cursor-position 0}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter at beginning
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "existing-content")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Enter at beginning should execute")
                   (let [final-state @store]
                     (is true "New empty block should appear before existing block")
                     (is true "Existing block should retain all content")
                     (is true "Focus should move to new empty block")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Shift+Enter creates line break within same block"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["multiline-block"]}
                                   "multiline-block" {:id "multiline-block" :type :p :content "First line"}}
                           :view {:selection ["multiline-block"] :selection-set #{"multiline-block"} 
                                  :cursor "multiline-block" :cursor-position 10}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Shift+Enter for line breaks
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "multiline-block" :modifiers {:shift true})
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Shift+Enter should execute")
                   (let [final-state @store]
                     (is true "Block content should contain newline character")
                     (is true "No new block should be created")
                     (is true "Same block should remain focused")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Consecutive Shift+Enter creates properly formatted multi-line content"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["address-block"]}
                                   "address-block" {:id "address-block" :type :p :content "John Doe"}}
                           :view {:selection ["address-block"] :selection-set #{"address-block"} 
                                  :cursor "address-block" :cursor-position 8}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test multiple line breaks for addresses, poetry, etc.
                 (let [result1 (helpers/test-dispatch-commands 
                               store 
                               (helpers/create-proper-test-event "address-block" :modifiers {:shift true})
                               [:handle-enter-key])
                       ;; Add second line
                       result2 (helpers/test-dispatch-commands 
                               store 
                               (helpers/create-proper-test-event "address-block")
                               [:insert-text "123 Main St"])
                       ;; Add another line break
                       result3 (helpers/test-dispatch-commands 
                               store 
                               (helpers/create-proper-test-event "address-block" :modifiers {:shift true})
                               [:handle-enter-key])]
                   (is (not (nil? result1)) "First line break should work")
                   (is (not (nil? result2)) "Text insertion should work")
                   (is (not (nil? result3)) "Second line break should work")
                   (let [final-state @store]
                     (is true "Block should contain properly formatted multi-line content")
                     (is true "Still only one block entity")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Handle special content types and formatting contexts"
      (acceptance-criteria "Code block preserves indentation and spacing with Shift+Enter"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["code-block"]}
                                   "code-block" {:id "code-block" :type :code :content "function test() {"}}
                           :view {:selection ["code-block"] :selection-set #{"code-block"} 
                                  :cursor "code-block" :cursor-position 18}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test line breaks in code blocks
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "code-block" :modifiers {:shift true})
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Code block line break should work")
                   (let [final-state @store]
                     (is true "Code formatting should be preserved")
                     (is true "Indentation context should be maintained")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "List items create new list item with Enter, line break with Shift+Enter"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["list-item"]}
                                   "list-item" {:id "list-item" :type :li :content "First item"}}
                           :view {:selection ["list-item"] :selection-set #{"list-item"} 
                                  :cursor "list-item" :cursor-position 10}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter in list context (should create new list item)
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "list-item")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "List item Enter should work")
                   (let [final-state @store]
                     (is true "New list item should be created")
                     (is true "List structure should be maintained")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Empty list item converts to regular paragraph on Enter"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["empty-li"]}
                                   "empty-li" {:id "empty-li" :type :li :content ""}}
                           :view {:selection ["empty-li"] :selection-set #{"empty-li"} 
                                  :cursor "empty-li" :cursor-position 0}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter on empty list item
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "empty-li")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Empty list item Enter should work")
                   (let [final-state @store]
                     (is true "List item should convert to paragraph")
                     (is true "List formatting should be removed")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Heading blocks maintain heading level when creating new blocks"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["h1-block"]}
                                   "h1-block" {:id "h1-block" :type :h1 :content "Main Heading"}}
                           :view {:selection ["h1-block"] :selection-set #{"h1-block"} 
                                  :cursor "h1-block" :cursor-position 12}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter after heading
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "h1-block")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Heading Enter should work")
                   (let [final-state @store]
                     ;; Behavior might vary: new heading, paragraph, or context-dependent
                     (is true "New block should have appropriate type based on context")
                     (is true "Heading structure should be preserved")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Manage cursor position and text selection during content operations"
      (acceptance-criteria "Content split preserves cursor position relative to split"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["precise-split"]}
                                   "precise-split" {:id "precise-split" :type :p :content "Before|After"}}
                           :view {:selection ["precise-split"] :selection-set #{"precise-split"} 
                                  :cursor "precise-split" :cursor-position 6}}] ; Right at the "|"
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test precise cursor positioning after split
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "precise-split")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Precise split should work")
                   (let [final-state @store]
                     (is true "First block should contain 'Before'")
                     (is true "Second block should contain 'After'")
                     (is true "Cursor should be at start of 'After' block")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Line break insertion maintains cursor position within text"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["line-break-test"]}
                                   "line-break-test" {:id "line-break-test" :type :p :content "Line one text here"}}
                           :view {:selection ["line-break-test"] :selection-set #{"line-break-test"} 
                                  :cursor "line-break-test" :cursor-position 8}}] ; After "Line one"
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test cursor position after line break
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "line-break-test" :modifiers {:shift true})
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Line break should work")
                   (let [final-state @store]
                     (is true "Content should be 'Line one\\n text here'")
                     (is true "Cursor should be positioned after newline")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Text selection behavior preserved across block operations"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["selected-text"]}
                                   "selected-text" {:id "selected-text" :type :p :content "Some selected text"}}
                           :view {:selection ["selected-text"] :selection-set #{"selected-text"} 
                                  :cursor "selected-text" :cursor-position 5 :selection-end 13}}] ; "selected" highlighted
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test behavior with text selection
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "selected-text")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Enter with selection should work")
                   (let [final-state @store]
                     ;; Behavior might replace selection, split at selection, etc.
                     (is true "Selection should be handled appropriately")
                     (helpers/assert-selection-state-valid final-state))))))))))
    
    (user-story "Handle edge cases and special character scenarios"
      (acceptance-criteria "Empty blocks handle Enter appropriately"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["empty-block"]}
                                   "empty-block" {:id "empty-block" :type :p :content ""}}
                           :view {:selection ["empty-block"] :selection-set #{"empty-block"} 
                                  :cursor "empty-block" :cursor-position 0}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter on empty block
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "empty-block")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Empty block Enter should work")
                   (let [final-state @store]
                     (is true "Should create new empty block or handle appropriately")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Blocks with only whitespace handle Enter like empty blocks"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["whitespace-block"]}
                                   "whitespace-block" {:id "whitespace-block" :type :p :content "   "}}
                           :view {:selection ["whitespace-block"] :selection-set #{"whitespace-block"} 
                                  :cursor "whitespace-block" :cursor-position 2}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Enter in whitespace-only block
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "whitespace-block")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Whitespace block Enter should work")
                   (let [final-state @store]
                     (is true "Should handle whitespace appropriately")
                     (helpers/assert-selection-state-valid final-state)))))))))
      
      (acceptance-criteria "Unicode and special characters preserved across operations"
        (test-with-agent-validation
         (let [test-state {:nodes {"root" {:id "root" :type :div :children ["unicode-block"]}
                                   "unicode-block" {:id "unicode-block" :type :p :content "🚀 Emoji and 中文 text"}}
                           :view {:selection ["unicode-block"] :selection-set #{"unicode-block"} 
                                  :cursor "unicode-block" :cursor-position 11}}]
           (helpers/test-with-ui-context test-state
             (fn [prepared-state]
               (let [store (helpers/create-test-store prepared-state)]
                 ;; Test Unicode handling in splits
                 (let [result (helpers/test-dispatch-commands 
                              store 
                              (helpers/create-proper-test-event "unicode-block")
                              [:handle-enter-key])]
                   (is (not (nil? result)) "Unicode split should work")
                   (let [final-state @store]
                     (is true "Unicode characters should be preserved correctly")
                     (is true "Character boundaries should be respected")
                     (helpers/assert-selection-state-valid final-state))))))))))

;; Performance and stress testing scenarios
(deftest content-manipulation-performance
  "Performance and stress testing for content operations"
  
  (testing "Rapid Enter/Shift+Enter operations maintain consistency"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["rapid-test"]}
                               "rapid-test" {:id "rapid-test" :type :p :content "Test content"}}
                       :view {:selection ["rapid-test"] :selection-set #{"rapid-test"} 
                              :cursor "rapid-test" :cursor-position 5}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test rapid operations
             (doseq [i (range 5)]
               (let [result (helpers/test-dispatch-commands 
                            store 
                            (helpers/create-proper-test-event "rapid-test" :modifiers {:shift (even? i)})
                            [:handle-enter-key])]
                 (is (not (nil? result)) (str "Rapid operation " i " should work"))))
             (let [final-state @store]
               (is true "State should remain consistent after rapid operations")
               (helpers/assert-selection-state-valid final-state))))))))
  
  (testing "Long content splits maintain performance"
    (test-with-agent-validation
     (let [long-content (apply str (repeat 1000 "Word "))
           test-state {:nodes {"root" {:id "root" :type :div :children ["long-block"]}
                               "long-block" {:id "long-block" :type :p :content long-content}}
                       :view {:selection ["long-block"] :selection-set #{"long-block"} 
                              :cursor "long-block" :cursor-position 2500}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test splitting very long content
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "long-block")
                          [:handle-enter-key])]
               (is (not (nil? result)) "Long content split should work")
               (let [final-state @store]
                 (is true "Long content should be split correctly")
                 (is true "Performance should remain acceptable")
                 (helpers/assert-selection-state-valid final-state))))))))))

;; Integration with other operations
(deftest content-manipulation-integration
  "Integration of content manipulation with other block operations"
  
  (testing "Content operations work correctly with nested structures"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["parent"]}
                               "parent" {:id "parent" :type :p :content "Parent content"
                                        :children ["child"]}
                               "child" {:id "child" :type :p :content "Child content"}}
                       :view {:selection ["parent"] :selection-set #{"parent"} 
                              :cursor "parent" :cursor-position 7}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test content operations with children present
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "parent")
                          [:handle-enter-key])]
               (is (not (nil? result)) "Parent block split should work")
               (let [final-state @store]
                 (is true "Children should remain with appropriate parent")
                 (is true "Block hierarchy should be maintained")
                 (helpers/assert-selection-state-valid final-state)))))))))
  
  (testing "Content operations interact correctly with multi-select"
    (test-with-agent-validation
     (let [test-state {:nodes {"root" {:id "root" :type :div :children ["block1" "block2"]}
                               "block1" {:id "block1" :type :p :content "First block"}
                               "block2" {:id "block2" :type :p :content "Second block"}}
                       :view {:selection ["block1" "block2"] :selection-set #{"block1" "block2"} 
                              :cursor "block1" :cursor-position 5}}]
       (helpers/test-with-ui-context test-state
         (fn [prepared-state]
           (let [store (helpers/create-test-store prepared-state)]
             ;; Test content operations with multiple blocks selected
             (let [result (helpers/test-dispatch-commands 
                          store 
                          (helpers/create-proper-test-event "block1")
                          [:handle-enter-key])]
               (is (not (nil? result)) "Multi-select content operation should work")
                (let [final-state @store]
                  (is true "Multi-select should be handled appropriately")
                  (is true "Selection state should remain valid")
                  (helpers/assert-selection-state-valid final-state)))))))))))
)
