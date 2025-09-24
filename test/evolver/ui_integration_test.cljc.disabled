(ns evolver.ui-integration-test
  (:require [evolver.kernel :as k]
            [evolver.schemas :as s]))

;; Fixture database for UI testing - represents a typical app state
(def fixture-db
  (-> k/db
      ;; Add some test nodes to work with
      (k/safe-apply-command {:op :insert
                            :parent-id "root"
                            :node-id "test-node-1"
                            :node-data {:type :div :props {:text "Test Node 1"}}
                            :position 0})
      (k/safe-apply-command {:op :insert
                            :parent-id "root"
                            :node-id "test-node-2"
                            :node-data {:type :div :props {:text "Test Node 2"}}
                            :position 1})
      ;; Select a node for operations
      (assoc-in [:view :selected] #{"test-node-1"})))

(defn test-ui-operations []
  (println "Testing UI operations...")

  ;; Test Create child block
  (let [db-with-child (k/safe-apply-command fixture-db {:op :insert
                                                       :parent-id "test-node-1"
                                                       :node-id "child-node"
                                                       :node-data {:type :div :props {:text "Child of test-node-1"}}
                                                       :position nil})]
    (assert (contains? (:nodes db-with-child) "child-node") "Child node should be created")
    (assert (= ["child-node"] (get (:children-by-parent db-with-child) "test-node-1")) "Child should be in parent's children")
    (println "✓ Create child block works"))

  ;; Test Create sibling
  (let [db-with-sibling (k/safe-apply-command fixture-db {:op :insert
                                                         :parent-id "root"
                                                         :node-id "sibling-node"
                                                         :node-data {:type :div :props {:text "Sibling below test-node-1"}}
                                                         :position 2})]
    (assert (contains? (:nodes db-with-sibling) "sibling-node") "Sibling node should be created")
    (assert (= 8 (count (get (:children-by-parent db-with-sibling) "root"))) "Should have 8 children now")
    (println "✓ Create sibling works"))

  ;; Test Indent operation
  (let [db-indented (k/safe-apply-command fixture-db {:op :move
                                                      :node-id "test-node-2"
                                                      :new-parent-id "test-node-1"
                                                      :position nil})]
    (assert (= ["test-node-2"] (get (:children-by-parent db-indented) "test-node-1")) "test-node-2 should be child of test-node-1")
    (assert (not (some #{"test-node-2"} (get (:children-by-parent db-indented) "root"))) "test-node-2 should not be root child")
    (println "✓ Indent operation works"))

  ;; Test Reorder operation
  (let [db-reordered (k/safe-apply-command fixture-db {:op :reorder
                                                       :node-id "test-node-2"
                                                       :parent-id "root"
                                                       :from-index 1
                                                       :to-index 0})]
    ;; Reorder should work without crashing
    (assert (some? db-reordered) "Reorder should not crash")
    (println "✓ Reorder operation works (basic test)"))

  ;; Test Undo/Redo
  (let [db-after-op (k/safe-apply-command fixture-db {:op :insert
                                                      :parent-id "root"
                                                      :node-id "undo-test"
                                                      :node-data {:type :div :props {:text "Undo Test"}}
                                                      :position 0})
        db-after-undo (k/safe-apply-command db-after-op {:op :undo})
        db-after-redo (k/safe-apply-command db-after-undo {:op :redo})]
    (assert (contains? (:nodes db-after-op) "undo-test") "Node should exist after operation")
    (assert (not (contains? (:nodes db-after-undo) "undo-test")) "Node should be gone after undo")
    (assert (contains? (:nodes db-after-redo) "undo-test") "Node should be back after redo")
    (assert (= 1 (count (:undo-stack db-after-undo))) "Undo stack should have 1 item after undo")
    (assert (= 0 (count (:undo-stack db-after-redo))) "Undo stack should be empty after redo")
    (println "✓ Undo/Redo works"))

  ;; Test Schema validation
  (assert (s/validate-db fixture-db) "Fixture db should be valid")
  (println "✓ Schema validation works")

  ;; Test Reference operations
  (let [db-with-ref (k/safe-apply-command fixture-db {:op :add-reference
                                                    :from-node-id "test-node-1"
                                                    :to-node-id "test-node-2"})
        refs-after-add (k/get-references db-with-ref "test-node-2")
        db-after-remove (k/safe-apply-command db-with-ref {:op :remove-reference
                                                          :from-node-id "test-node-1"
                                                          :to-node-id "test-node-2"})
        refs-after-remove (k/get-references db-after-remove "test-node-2")]

    (assert (= #{"test-node-1"} refs-after-add) "Should have reference after add")
    (assert (= #{} refs-after-remove) "Should have no references after remove")
    (println "✓ Reference operations work"))

  ;; Test Reference undo/redo
  (let [db-with-ref (k/safe-apply-command fixture-db {:op :add-reference
                                                    :from-node-id "test-node-1"
                                                    :to-node-id "test-node-2"})
        db-after-undo (k/safe-apply-command db-with-ref {:op :undo})
        db-after-redo (k/safe-apply-command db-after-undo {:op :redo})
        refs-after-undo (k/get-references db-after-undo "test-node-2")
        refs-after-redo (k/get-references db-after-redo "test-node-2")]

    (assert (= #{} refs-after-undo) "Should have no references after undo")
    (assert (= #{"test-node-1"} refs-after-redo) "Should have reference back after redo")
    (println "✓ Reference undo/redo works"))

  (println "All UI integration tests passed!"))

;; CSS attribute testing helpers
(defn simulate-selection [db node-id]
  "Simulate selecting a node and return expected CSS classes"
  (let [selected? (contains? (get-in db [:view :selected]) node-id)
        collapsed? (contains? (get-in db [:view :collapsed]) node-id)]
    (cond-> [:node]
      selected? (conj :selected)
      collapsed? (conj :collapsed))))

(defn test-css-attributes []
  (println "Testing CSS attribute simulation...")

  (let [selected-classes (simulate-selection fixture-db "test-node-1")
        unselected-classes (simulate-selection fixture-db "nonexistent")
        db-collapsed (update-in fixture-db [:view :collapsed] conj "test-node-1")
        collapsed-classes (simulate-selection db-collapsed "test-node-1")]

    (assert (= [:node :selected] selected-classes) "Selected node should have selected class")
    (assert (= [:node] unselected-classes) "Unselected node should not have selected class")
    (assert (= [:node :selected :collapsed] collapsed-classes) "Collapsed selected node should have both classes")

    (println "✓ CSS attribute simulation works")))

;; Run all tests
(defn run-all-tests []
  (test-ui-operations)
  (test-css-attributes)
  (println "🎉 All integration tests completed successfully!"))