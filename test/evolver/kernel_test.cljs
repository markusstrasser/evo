(ns evolver.kernel-test
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.kernel :as kernel]
            [evolver.constants :as constants]))

(deftest test-basic-db-structure
  (testing "Database has expected initial structure"
    (let [db constants/initial-db-base]
      (is (= #{"p1-select"} (:selected (:view db))) "Should have initial selection")
      (is (= ["title" "p1-select" "p2-high" "p3-both" "div1"] (get (:children-by-parent db) "root")) "Root should have 5 children")
      (is (= ["p4-click"] (get (:children-by-parent db) "div1")) "div1 should have 1 child"))))

(deftest test-node-operations
  (testing "Node insertion"
    (let [db constants/initial-db-base
          new-db (kernel/insert-node db {:parent-id "root" :node-id "test-node"
                                         :node-data {:type :p :props {:text "Test"}} :position 0})
          children (get (:children-by-parent new-db) "root")]
      (is (= "test-node" (first children)) "Should insert at position 0")
      (is (= "Test" (:text (:props (get (:nodes new-db) "test-node")))) "Should have correct node data")))

  (testing "Node patching"
    (let [db constants/initial-db-base
          new-db (kernel/patch-node db {:node-id "title" :updates {:props {:text "Updated Title"}}})]
      (is (= "Updated Title" (:text (:props (get (:nodes new-db) "title")))) "Should update node properties")))

  (testing "Node deletion"
    (let [db constants/initial-db-base
          new-db (kernel/delete-node db {:node-id "p2-high" :recursive false})]
      (is (nil? (get (:nodes new-db) "p2-high")) "Should remove deleted node")
      (is (not (contains? (:selected (:view new-db)) "p2-high")) "Should remove from selection"))))

(deftest test-tree-metadata
  (testing "Tree metadata derivation"
    (let [db constants/initial-db-base
          metadata (kernel/derive-tree-metadata db)]
      (is (= 1 (get (:depth metadata) "title")) "Title should be at depth 1")
      (is (= 2 (get (:depth metadata) "p4-click")) "p4-click should be at depth 2")
      (is (= ["root"] (get (:paths metadata) "title")) "Title path should be [root]")
      (is (= ["root" "div1"] (get (:paths metadata) "p4-click")) "p4-click path should be [root div1]"))))

(deftest test-structural-operations
  (testing "Create child block"
    (let [db (assoc-in constants/initial-db-base [:view :selected] #{"p1-select"})
          db-with-child (kernel/create-child-block db)
          current (kernel/current-node-id db)
          children (get (:children-by-parent db-with-child) current)]
      (is (= 1 (count children)) "Should have one child")
      (is (string? (first children)) "Child id should be string")))

  (testing "Create sibling above"
    (let [db (assoc-in constants/initial-db-base [:view :selected] #{"p1-select"})
          db-with-sib (kernel/create-sibling-above db)
          parent "root"
          children (get (:children-by-parent db-with-sib) parent)
          current-idx (.indexOf children (kernel/current-node-id db))]
      (is (= 6 (count children)) "Should have 6 children")
      (is (= 2 current-idx) "Current should be at index 2")))

  (testing "Create sibling below"
    (let [db (assoc-in constants/initial-db-base [:view :selected] #{"p1-select"})
          db-with-sib (kernel/create-sibling-below db)
          parent "root"
          children (get (:children-by-parent db-with-sib) parent)
          current-idx (.indexOf children (kernel/current-node-id db))]
      (is (= 6 (count children)) "Should have 6 children")
      (is (= 1 current-idx) "Current should stay at original position")))

  (testing "Indent operation"
    (let [db (assoc-in constants/initial-db-base [:view :selected] #{"p1-select"})
          db-indented (kernel/indent db)
          current (kernel/current-node-id db)
          new-parent (kernel/find-parent (:children-by-parent db-indented) current)]
      (is (= "title" new-parent) "Should be child of title")))

  (testing "Outdent operation"
    (let [db (assoc-in constants/initial-db-base [:view :selected] #{"p4-click"})
          db-outdented (kernel/outdent db)
          current "p4-click"
          new-parent (kernel/find-parent (:children-by-parent db-outdented) current)]
      (is (= "root" new-parent) "Should be child of root"))))

(deftest test-edge-cases
  (testing "Indent with first child should do nothing"
    (let [db (assoc-in constants/initial-db-base [:view :selected] #{"title"})
          db-indented (kernel/indent db)
          current-parent (kernel/find-parent (:children-by-parent db-indented) "title")]
      (is (= "root" current-parent) "First child should not indent")))

  (testing "Outdent with root child should do nothing"
    (let [db (assoc-in constants/initial-db-base [:view :selected] #{"title"})
          db-outdented (kernel/outdent db)
          current-parent (kernel/find-parent (:children-by-parent db-outdented) "title")]
      (is (= "root" current-parent) "Root child should not outdent")))

  (testing "Current node with no selection"
    (let [db-no-selection (assoc-in constants/initial-db-base [:view :selected] #{})]
      (is (nil? (kernel/current-node-id db-no-selection)) "Should return nil when no selection")))

  (testing "Current node with multiple selection"
    (let [db-multi-selection (assoc-in constants/initial-db-base [:view :selected] #{"title" "p1-select"})]
      (is (= "title" (kernel/current-node-id db-multi-selection)) "Should return first selected node"))))

(deftest test-apply-command
  (testing "Apply command wrapper"
    (let [db constants/initial-db-base
          new-db (kernel/apply-command db {:op :patch :node-id "title"
                                           :updates {:props {:text "Command Updated"}}})]
      (is (= "Command Updated" (:text (:props (get (:nodes new-db) "title")))) "apply-command should work"))))

(deftest test-schema-validation
  (testing "Database schema validation"
    (is (true? (kernel/validate-db-state constants/initial-db-base)) "Initial db should be valid")
    (is (thrown? js/Error (kernel/validate-db-state {})) "Empty db should be invalid")))

(deftest test-transaction-logging
  (testing "Transaction logging with new history system"
    (let [db constants/initial-db-base
          db-with-log (kernel/log-operation db {:op :test :args {:data "test"}})]
      (is (= 1 (count (:history db-with-log))) "Should have one transaction in history")
      (is (= :test (:op (first (:history db-with-log)))) "Transaction should have correct op"))))

(deftest test-logging
  (testing "Log message functionality"
    (let [db constants/initial-db-base
          db-with-logs (-> db
                           (kernel/log-message :info "Test info message")
                           (kernel/log-message :error "Test error message" {:details "test"}))]
      (is (= 2 (count (:log-history db-with-logs))) "Should have two log entries")
      (is (= :info (:level (first (:log-history db-with-logs)))) "First log should be info")
      (is (= :error (:level (second (:log-history db-with-logs)))) "Second log should be error"))))

(deftest test-undo-redo
  (testing "Undo functionality with history index system"
    (let [db constants/initial-db-base
          ;; Simulate a db with history after an insert
          cmd {:op :insert :parent-id "root" :node-id "undo-test" :node-data {:type :p :props {:text "Undo Test"}} :position 0}
          db-with-history (assoc db :history [cmd] :history-index 1)
          ;; Apply undo - should just change index
          db-after-undo (kernel/apply-command db-with-history {:op :undo})
          ;; Reconstruct state from history
          undo-state (reduce kernel/apply-command constants/initial-db-base (take (:history-index db-after-undo) (:history db-after-undo)))]
      (is (= 0 (:history-index db-after-undo)) "Undo should set history index to 0")
      (is (nil? (get (:nodes undo-state) "undo-test")) "Node should not exist after undo reconstruction"))))