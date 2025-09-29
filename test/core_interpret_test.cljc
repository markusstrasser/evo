(ns core-interpret-test
  "Tests for core.interpret - transaction pipeline"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [core.db :as db]
            [core.interpret :as interp]))

;; Test Helpers

(defn create-op [id type] {:op :create-node :id id :type type :props {}})
(defn place-op [id under at] {:op :place :id id :under under :at at})
(defn update-op [id props] {:op :update-node :id id :props props})

(defn db-diff
  "Extract meaningful differences between two dbs for assertions."
  [before after]
  {:nodes-added (set/difference (set (keys (:nodes after)))
                                (set (keys (:nodes before))))
   :nodes-removed (set/difference (set (keys (:nodes before)))
                                  (set (keys (:nodes after))))
   :children-changed (into {}
                           (for [parent (keys (:children-by-parent after))
                                 :let [before-children (get (:children-by-parent before) parent [])
                                       after-children (get (:children-by-parent after) parent [])]
                                 :when (not= before-children after-children)]
                             [parent {:before before-children :after after-children}]))})

;;; Basic Operation Tests

(deftest test-create-node
  (testing "create-node adds node to database"
    (let [db (db/empty-db)
          result (interp/interpret db [(create-op "a" :div)])
          final-db (:db result)]

      (is (empty? (:issues result)) "No validation issues")
      (is (contains? (:nodes final-db) "a") "Node exists")
      (is (= :div (get-in final-db [:nodes "a" :type])) "Type is correct"))))

(deftest test-create-duplicate
  (testing "creating duplicate node is idempotent"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)])
                 :db)
          result (interp/interpret db [(create-op "a" :span)])]

      (is (seq (:issues result)) "Has validation issue")
      (is (= :duplicate-create (-> result :issues first :issue))
          "Issue is duplicate-create"))))

(deftest test-place-node
  (testing "place operation moves node to parent"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)])
                 :db)
          result (interp/interpret db [(place-op "a" :doc :first)])
          final-db (:db result)]

      (is (empty? (:issues result)) "No validation issues")
      (is (= ["a"] (get (:children-by-parent final-db) :doc))
          "Node placed under :doc")
      (is (= :doc (get-in final-db [:derived :parent-of "a"]))
          "Derived parent-of updated"))))

(deftest test-place-at-positions
  (testing "place at different positions"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)
                                    (create-op "b" :div)
                                    (create-op "c" :div)
                                    (place-op "a" :doc :first)
                                    (place-op "b" :doc :last)
                                    (place-op "c" :doc 1)])
                 :db)]

      (is (= ["a" "c" "b"] (get (:children-by-parent db) :doc))
          "Children in correct order"))))

(deftest test-update-node
  (testing "update-node merges properties"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)])
                 :db)
          result (interp/interpret db [(update-op "a" {:x 1 :y 2})])
          final-db (:db result)]

      (is (empty? (:issues result)) "No validation issues")
      (is (= {:x 1 :y 2} (get-in final-db [:nodes "a" :props]))
          "Properties merged"))))

;;; Normalization Tests

(deftest test-normalize-no-op-place
  (testing "no-op place operations are removed"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)
                                    (place-op "a" :doc :first)])
                 :db)
          ;; Place again at same position - should be no-op
          result (interp/interpret db [(place-op "a" :doc :first)])]

      (is (empty? (:issues result)) "No validation issues")
      (is (empty? (:trace result)) "No operations applied (normalized away)"))))

(deftest test-normalize-merge-updates
  (testing "adjacent update-node operations are merged"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)])
                 :db)
          result (interp/interpret db [(update-op "a" {:x 1})
                                       (update-op "a" {:y 2})])
          final-db (:db result)]

      (is (empty? (:issues result)) "No validation issues")
      (is (= 1 (count (:trace result))) "Only one operation applied (merged)")
      (is (= {:x 1 :y 2} (get-in final-db [:nodes "a" :props]))
          "Both updates applied"))))

;;; Validation Tests

(deftest test-validate-missing-node
  (testing "placing non-existent node fails"
    (let [db (db/empty-db)
          result (interp/interpret db [(place-op "missing" :doc :first)])]

      (is (seq (:issues result)) "Has validation issues")
      (is (= :node-not-found (-> result :issues first :issue))
          "Issue is node-not-found"))))

(deftest test-validate-missing-parent
  (testing "placing under non-existent parent fails"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)])
                 :db)
          result (interp/interpret db [(place-op "a" "missing-parent" :first)])]

      (is (seq (:issues result)) "Has validation issues")
      (is (= :parent-not-found (-> result :issues first :issue))
          "Issue is parent-not-found"))))

(deftest test-validate-cycle-self
  (testing "placing node under itself fails"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)])
                 :db)
          result (interp/interpret db [(place-op "a" "a" :first)])]

      (is (seq (:issues result)) "Has validation issues")
      (is (= :cycle-detected (-> result :issues first :issue))
          "Issue is cycle-detected"))))

(deftest test-validate-cycle-descendant
  (testing "placing node under its own descendant fails"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)
                                    (create-op "b" :div)
                                    (place-op "a" :doc :first)
                                    (place-op "b" "a" :first)])
                 :db)
          ;; Try to place "a" under "b" (its child) - creates cycle
          result (interp/interpret db [(place-op "a" "b" :first)])]

      (is (seq (:issues result)) "Has validation issues")
      (is (= :cycle-detected (-> result :issues first :issue))
          "Issue is cycle-detected"))))

(deftest test-validate-anchor-not-sibling
  (testing "anchor :before/:after must be a sibling"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)
                                    (create-op "b" :div)
                                    (place-op "a" :doc :first)])
                 :db)
          ;; Try to place "b" :before "a", but "a" is not under :doc yet
          result (interp/interpret db [(place-op "b" :trash {:before "a"})])]

      (is (seq (:issues result)) "Has validation issues")
      (is (= :anchor-not-sibling (-> result :issues first :issue))
          "Issue is anchor-not-sibling"))))

;;; Full Pipeline Tests (ops -> db-diff)

(deftest test-pipeline-build-tree
  (testing "build simple tree structure"
    (let [before (db/empty-db)
          ops [(create-op "root" :div)
               (create-op "header" :header)
               (create-op "content" :div)
               (place-op "root" :doc :first)
               (place-op "header" "root" :first)
               (place-op "content" "root" :last)]
          result (interp/interpret before ops)
          after (:db result)
          diff (db-diff before after)]

      (is (empty? (:issues result)) "No validation issues")
      (is (= #{"root" "header" "content"} (:nodes-added diff))
          "Three nodes added")
      (is (= ["root"] (get (:children-by-parent after) :doc))
          ":doc has root child")
      (is (= ["header" "content"] (get (:children-by-parent after) "root"))
          "root has two children"))))

(deftest test-pipeline-move-subtree
  (testing "move node with children"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)
                                    (create-op "b" :div)
                                    (create-op "c" :div)
                                    (place-op "a" :doc :first)
                                    (place-op "b" "a" :first)
                                    (place-op "c" :doc :last)])
                 :db)
          ;; Move "a" (with child "b") to be child of "c"
          result (interp/interpret db [(place-op "a" "c" :first)])
          final-db (:db result)]

      (is (empty? (:issues result)) "No validation issues")
      (is (= ["c"] (get (:children-by-parent final-db) :doc))
          ":doc now only has c")
      (is (= ["a"] (get (:children-by-parent final-db) "c"))
          "c has a as child")
      (is (= ["b"] (get (:children-by-parent final-db) "a"))
          "a still has b as child"))))

(deftest test-pipeline-complex-operations
  (testing "complex sequence of operations"
    (let [before (db/empty-db)
          ops [(create-op "a" :div)
               (place-op "a" :doc :first)
               (update-op "a" {:class "container"})
               (create-op "b" :span)
               (place-op "b" "a" :first)
               (update-op "b" {:text "Hello"})
               (update-op "a" {:id "main"}) ; merge with previous update
               (create-op "c" :p)
               (place-op "c" "a" :last)]
          result (interp/interpret before ops)
          after (:db result)]

      (is (empty? (:issues result)) "No validation issues")
      (is (= {:class "container" :id "main"}
             (get-in after [:nodes "a" :props]))
          "Multiple updates merged correctly")
      (is (= {:text "Hello"}
             (get-in after [:nodes "b" :props]))
          "b updated correctly")
      (is (= ["b" "c"] (get (:children-by-parent after) "a"))
          "Children in correct order"))))

#_(deftest test-pipeline-with-fixtures
    (testing "integrate with fixtures for complex scenarios"
      (let [tree (fix/gen-balanced-tree 2 2)
            root-id (:root-id tree)
            ops [(create-op "new" :div)
                 (place-op "new" root-id :first)
                 (update-op "new" {:injected true})]
            result (interp/interpret (:db tree) ops)
            final-db (:db result)]

        (is (empty? (:issues result)) "No validation issues")
        (is (contains? (:nodes final-db) "new") "New node exists")
        (is (= "new" (first (get (:children-by-parent final-db) root-id)))
            "New node is first child"))))

;;; Invariant Tests

(deftest test-derived-indexes-valid
  (testing "derived indexes are correct after operations"
    (let [ops [(create-op "a" :div)
               (create-op "b" :div)
               (place-op "a" :doc :first)
               (place-op "b" "a" :first)]
          result (interp/interpret (db/empty-db) ops)
          final-db (:db result)
          validation (db/validate final-db)]

      (is (empty? (:issues result)) "No validation issues from interpret")
      (is (:ok? validation) "Database passes validation")
      (is (empty? (:errors validation)) "No validation errors"))))

(deftest test-partial-failure
  (testing "operations after failure are not applied"
    (let [db (-> (db/empty-db)
                 (interp/interpret [(create-op "a" :div)])
                 :db)
          ops [(update-op "a" {:x 1})          ; valid
               (place-op "missing" :doc :first) ; invalid - node doesn't exist
               (update-op "a" {:y 2})]          ; should not be applied
          result (interp/interpret db ops)
          final-db (:db result)]

      (is (seq (:issues result)) "Has validation issues")
      (is (= {:x 1} (get-in final-db [:nodes "a" :props]))
          "Only first update applied, third update not applied"))))