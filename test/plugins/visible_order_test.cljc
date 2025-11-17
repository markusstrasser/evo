(ns plugins.visible-order-test
  "Tests for :visible-order derived index using matcher-combinators.

   Demonstrates the new testing approach from Architecture Refactoring Plan:
   - Use match? instead of = for structural assertions
   - Only assert what matters (ignore unrelated indexes)
   - Flexible matching with predicates and patterns"
  (:require [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test]  ; enables (is (match? ...))
            [matcher-combinators.matchers :as m]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.constants :as const]
            [plugins.visible-order :as vo]))

(defn apply-ops
  "Apply ops and return final db. Helper for tests."
  ([ops] (apply-ops (db/empty-db) ops))
  ([db ops]
   (let [result (tx/interpret db ops)]
     (:db result))))

(deftest visible-order-basic-tree
  (testing "Computes visible children for a simple tree"
    (let [db0 (db/empty-db)
          db1 (apply-ops db0
                [{:op :create-node :id "b1" :type :block :props {:text "Parent"}}
                 {:op :place :id "b1" :under :doc :at :last}
                 {:op :create-node :id "b2" :type :block :props {:text "Child"}}
                 {:op :place :id "b2" :under "b1" :at :last}
                 {:op :create-node :id "b3" :type :block :props {:text "Sibling"}}
                 {:op :place :id "b3" :under :doc :at :last}])]

      ;; Only assert :visible-order, ignore other derived indexes
      (is (match? {:derived {:visible-order {:by-parent {:doc ["b1" "b3"]
                                                         "b1" ["b2"]
                                                         "b3" []}}}}
                  db1)))))

(deftest visible-order-with-folding
  (testing "Hides children of folded nodes"
    (let [db0 (db/empty-db)
          db1 (apply-ops db0
                [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                 {:op :place :id "parent" :under :doc :at :last}
                 {:op :create-node :id "child1" :type :block :props {:text "Child 1"}}
                 {:op :place :id "child1" :under "parent" :at :last}
                 {:op :create-node :id "child2" :type :block :props {:text "Child 2"}}
                 {:op :place :id "child2" :under "parent" :at :last}
                 {:op :create-node :id "sibling" :type :block :props {:text "Sibling"}}
                 {:op :place :id "sibling" :under :doc :at :last}])

          ;; Initially, all children are visible
          _ (is (match? {:derived {:visible-order {:by-parent {"parent" ["child1" "child2"]}}}}
                        db1))

          ;; Fold "parent" to hide its children
          db2 (apply-ops db1
                [{:op :update-node
                  :id const/session-ui-id
                  :props {:folded #{"parent"}}}])]

      ;; Parent is folded → no visible children
      (is (match? {:derived {:visible-order {:by-parent {"parent" []}}}}
                  db2))

      ;; Sibling is unaffected
      (is (match? {:derived {:visible-order {:by-parent {:doc ["parent" "sibling"]}}}}
                  db2)))))

(deftest visible-order-with-nested-folding
  (testing "Nested folded blocks hide all descendants"
    (let [db0 (db/empty-db)
          db1 (apply-ops db0
                [{:op :create-node :id "gp" :type :block :props {:text "Grandparent"}}
                 {:op :place :id "gp" :under :doc :at :last}
                 {:op :create-node :id "p" :type :block :props {:text "Parent"}}
                 {:op :place :id "p" :under "gp" :at :last}
                 {:op :create-node :id "c" :type :block :props {:text "Child"}}
                 {:op :place :id "c" :under "p" :at :last}])

          ;; Fold grandparent
          db2 (apply-ops db1
                [{:op :update-node
                  :id const/session-ui-id
                  :props {:folded #{"gp"}}}])]

      ;; Grandparent is folded → parent and child are hidden
      (is (match? {:derived {:visible-order {:by-parent {"gp" []}}}}
                  db2)))))

(deftest visible-order-with-zoom
  (testing "Zoom filters visibility to subtree"
    (let [db0 (db/empty-db)
          db1 (apply-ops db0
                [{:op :create-node :id "b1" :type :block :props {:text "Top"}}
                 {:op :place :id "b1" :under :doc :at :last}
                 {:op :create-node :id "b2" :type :block :props {:text "Child of b1"}}
                 {:op :place :id "b2" :under "b1" :at :last}
                 {:op :create-node :id "b3" :type :block :props {:text "Sibling"}}
                 {:op :place :id "b3" :under :doc :at :last}
                 {:op :create-node :id "b4" :type :block :props {:text "Child of b3"}}
                 {:op :place :id "b4" :under "b3" :at :last}])

          ;; Initially, all nodes visible
          _ (is (match? {:derived {:visible-order {:by-parent {:doc ["b1" "b3"]
                                                               "b1" ["b2"]
                                                               "b3" ["b4"]}}}}
                        db1))

          ;; Zoom into b1
          db2 (apply-ops db1
                [{:op :update-node
                  :id const/session-ui-id
                  :props {:zoom-root "b1"}}])]

      ;; Only b1 and its descendants are visible
      ;; b3 and b4 are filtered out
      (is (match? {:derived {:visible-order {:by-parent {:doc ["b1"]
                                                         "b1" ["b2"]
                                                         "b3" []}}}}
                  db2)))))

(deftest visible-order-flexibility-demonstration
  (testing "Demonstrates matcher-combinators flexibility"
    (let [db0 (db/empty-db)
          db1 (apply-ops db0
                [{:op :create-node :id "b1" :type :block :props {:text "Block 1"}}
                 {:op :place :id "b1" :under :doc :at :last}
                 {:op :create-node :id "b2" :type :block :props {:text "Block 2"}}
                 {:op :place :id "b2" :under :doc :at :last}])]

      ;; Pattern 1: Use m/prefix to match start of list
      (is (match? {:derived {:visible-order {:by-parent {:doc (m/prefix ["b1"])}}}}
                  db1))

      ;; Pattern 2: Use m/in-any-order when order doesn't matter
      (is (match? {:derived {:visible-order {:by-parent {:doc (m/in-any-order ["b2" "b1"])}}}}
                  db1))

      ;; Pattern 3: Use predicates to assert properties
      (is (match? {:derived {:visible-order {:by-parent {:doc (fn [children]
                                                                 (and (vector? children)
                                                                      (= 2 (count children))))}}}}
                  db1))

      ;; Pattern 4: Combine matchers for complex assertions
      (is (match? {:derived {:visible-order {:by-parent (m/embeds {:doc (m/prefix ["b1"])})}}}
                  db1)))))

(comment
  ;; Run tests in REPL
  (require '[clojure.test :as t])
  (t/run-tests 'plugins.visible-order-test)

  ;; Run single test
  (visible-order-basic-tree)
  (visible-order-with-folding)
  (visible-order-with-zoom)
  )
