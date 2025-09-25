(ns evolver.kernel-test
   (:require [cljs.test :refer [deftest is testing]]
             [evolver.kernel :as kernel]))

(deftest test-basic-db-structure
  (testing "Database has expected initial structure"
    (is (= #{"p1-select"} (:selected (:view kernel/db))) "Should have initial selection")
    (is (= ["title" "p1-select" "p2-high" "p3-both" "div1"] (get (:children-by-parent kernel/db) "root")) "Root should have 5 children")
    (is (= ["p4-click"] (get (:children-by-parent kernel/db) "div1")) "div1 should have 1 child")))

(deftest test-node-operations
  (testing "Node insertion"
    (let [new-db (kernel/insert-node kernel/db {:parent-id "root" :node-id "test-node"
                                               :node-data {:type :p :props {:text "Test"}} :position 0})
          children (get (:children-by-parent new-db) "root")]
      (is (= "test-node" (first children)) "Should insert at position 0")
      (is (= "Test" (:text (:props (get (:nodes new-db) "test-node")))) "Should have correct node data")))

  (testing "Node patching"
    (let [new-db (kernel/patch-node kernel/db {:node-id "title" :updates {:props {:text "Updated Title"}}})]
      (is (= "Updated Title" (:text (:props (get (:nodes new-db) "title")))) "Should update node properties")))

  (testing "Node deletion"
    (let [new-db (kernel/delete-node kernel/db {:node-id "p2-high" :recursive false})]
      (is (nil? (get (:nodes new-db) "p2-high")) "Should remove deleted node")
      (is (not (contains? (:selected (:view new-db)) "p2-high")) "Should remove from selection"))))

(deftest test-tree-metadata
  (testing "Tree metadata derivation"
    (let [metadata (kernel/derive-tree-metadata kernel/db)]
      (is (= 1 (get (:depth metadata) "title")) "Title should be at depth 1")
      (is (= 2 (get (:depth metadata) "p4-click")) "p4-click should be at depth 2")
      (is (= ["root"] (get (:paths metadata) "title")) "Title path should be [root]")
      (is (= ["root" "div1"] (get (:paths metadata) "p4-click")) "p4-click path should be [root div1]"))))

(deftest test-structural-operations
  (testing "Create child block"
    (let [db-with-child (kernel/create-child-block kernel/db)
          current (kernel/current-node-id kernel/db)
          children (get (:children-by-parent db-with-child) current)]
      (is (= 1 (count children)) "Should have one child")
      (is (string? (first children)) "Child id should be string")))

  (testing "Create sibling above"
    (let [db-with-sib (kernel/create-sibling-above kernel/db)
          parent "root"
          children (get (:children-by-parent db-with-sib) parent)
          current-idx (.indexOf children (kernel/current-node-id kernel/db))]
      (is (= 6 (count children)) "Should have 6 children")
      (is (= 2 current-idx) "Current should be at index 2")))

  (testing "Create sibling below"
    (let [db-with-sib (kernel/create-sibling-below kernel/db)
          parent "root"
          children (get (:children-by-parent db-with-sib) parent)
          current-idx (.indexOf children (kernel/current-node-id kernel/db))]
      (is (= 6 (count children)) "Should have 6 children")
      (is (= 1 current-idx) "Current should stay at original position")))

  (testing "Indent operation"
    (let [db-indented (kernel/indent kernel/db)
          current (kernel/current-node-id kernel/db)
          new-parent (kernel/find-parent (:children-by-parent db-indented) current)]
      (is (= "title" new-parent) "Should be child of title")))

  (testing "Outdent operation"
    (let [db-outdented (-> kernel/db
                           (assoc-in [:view :selected] #{"p4-click"})
                           kernel/outdent)
          current "p4-click"
          new-parent (kernel/find-parent (:children-by-parent db-outdented) current)]
      (is (= "root" new-parent) "Should be child of root"))))

(deftest test-edge-cases
  (testing "Indent with first child should do nothing"
    (let [db-indented (-> kernel/db
                          (assoc-in [:view :selected] #{"title"})
                          kernel/indent)
          current-parent (kernel/find-parent (:children-by-parent db-indented) "title")]
      (is (= "root" current-parent) "First child should not indent")))

  (testing "Outdent with root child should do nothing"
    (let [db-outdented (-> kernel/db
                           (assoc-in [:view :selected] #{"title"})
                           kernel/outdent)
          current-parent (kernel/find-parent (:children-by-parent db-outdented) "title")]
      (is (= "root" current-parent) "Root child should not outdent")))

  (testing "Current node with no selection"
    (let [db-no-selection (assoc-in kernel/db [:view :selected] #{})]
      (is (nil? (kernel/current-node-id db-no-selection)) "Should return nil when no selection")))

  (testing "Current node with multiple selection"
    (let [db-multi-selection (assoc-in kernel/db [:view :selected] #{"title" "p1-select"})]
      (is (= "title" (kernel/current-node-id db-multi-selection)) "Should return first selected node"))))

(deftest test-apply-command
  (testing "Apply command wrapper"
    (let [new-db (kernel/apply-command kernel/db {:op :patch :node-id "title"
                                                  :updates {:props {:text "Command Updated"}}})]
      (is (= "Command Updated" (:text (:props (get (:nodes new-db) "title")))) "apply-command should work"))))

(deftest test-schema-validation
  (testing "Database schema validation"
    (is (true? (kernel/validate-db-state kernel/db)) "Initial db should be valid")
    (is (thrown? js/Error (kernel/validate-db-state {})) "Empty db should be invalid")))

(deftest test-transaction-logging
  (testing "Transaction logging"
    (let [db-with-log (kernel/log-operation kernel/db {:op :test :args {:data "test"}})]
      (is (= 1 (count (:tx-log db-with-log))) "Should have one transaction")
      (is (= :test (:op (first (:tx-log db-with-log)))) "Transaction should have correct op"))))

(deftest test-logging
  (testing "Log message functionality"
    (let [db-with-logs (-> kernel/db
                           (kernel/log-message :info "Test info message")
                           (kernel/log-message :error "Test error message" {:details "test"}))]
      (is (= 2 (count (:log-history db-with-logs))) "Should have two log entries")
      (is (= :info (:level (first (:log-history db-with-logs)))) "First log should be info")
      (is (= :error (:level (second (:log-history db-with-logs)))) "Second log should be error"))))

(deftest test-undo-redo
  (testing "Undo functionality"
    (let [db-after-insert (kernel/apply-command kernel/db {:op :insert
                                                           :parent-id "root"
                                                           :node-id "undo-test"
                                                           :node-data {:type :p :props {:text "Undo Test"}}
                                                           :position 0})
          db-after-undo (kernel/apply-command db-after-insert {:op :undo})]
      (is (some? (get (:nodes db-after-insert) "undo-test")) "Node should exist after insert")
      (is (nil? (get (:nodes db-after-undo) "undo-test")) "Node should be removed after undo"))))