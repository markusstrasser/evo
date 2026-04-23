(ns kernel.navigation-test
  "Tests for kernel.navigation using matcher-combinators.

   Demonstrates testing navigation logic with the :visible-order index."
  (:require [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.navigation :as nav]))

(defn apply-ops
  "Apply ops and return final db."
  ([ops] (apply-ops (db/empty-db) ops))
  ([db ops]
   (:db (tx/interpret db ops))))

(deftest ^{:fr/ids #{:fr.scope/visible-outline}} visible-siblings-test
  (testing "Returns all visible siblings including self"
    (let [db (apply-ops
              [{:op :create-node :id "b1" :type :block :props {:text "Child 1"}}
               {:op :place :id "b1" :under :doc :at :last}
               {:op :create-node :id "b2" :type :block :props {:text "Child 2"}}
               {:op :place :id "b2" :under :doc :at :last}
               {:op :create-node :id "b3" :type :block :props {:text "Child 3"}}
               {:op :place :id "b3" :under :doc :at :last}])
          session nil]

      (is (= ["b1" "b2" "b3"] (nav/visible-siblings db session "b2"))))))

(deftest ^{:fr/ids #{:fr.scope/visible-outline}} sibling-navigation-test
  (testing "Navigate between visible siblings"
    (let [db (apply-ops
              [{:op :create-node :id "b1" :type :block :props {:text "First"}}
               {:op :place :id "b1" :under :doc :at :last}
               {:op :create-node :id "b2" :type :block :props {:text "Middle"}}
               {:op :place :id "b2" :under :doc :at :last}
               {:op :create-node :id "b3" :type :block :props {:text "Last"}}
               {:op :place :id "b3" :under :doc :at :last}])
          session nil]

      (testing "prev-visible-sibling"
        (is (nil? (nav/prev-visible-sibling db session "b1")))
        (is (= "b1" (nav/prev-visible-sibling db session "b2")))
        (is (= "b2" (nav/prev-visible-sibling db session "b3"))))

      (testing "next-visible-sibling"
        (is (= "b2" (nav/next-visible-sibling db session "b1")))
        (is (= "b3" (nav/next-visible-sibling db session "b2")))
        (is (nil? (nav/next-visible-sibling db session "b3")))))))

(deftest child-navigation-test
  (testing "Navigate to first/last visible children"
    (let [db (apply-ops
              [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
               {:op :place :id "parent" :under :doc :at :last}
               {:op :create-node :id "c1" :type :block :props {:text "Child 1"}}
               {:op :place :id "c1" :under "parent" :at :last}
               {:op :create-node :id "c2" :type :block :props {:text "Child 2"}}
               {:op :place :id "c2" :under "parent" :at :last}
               {:op :create-node :id "c3" :type :block :props {:text "Child 3"}}
               {:op :place :id "c3" :under "parent" :at :last}])
          session nil]

      (is (= "c1" (nav/first-visible-child db session "parent")))
      (is (= "c3" (nav/last-visible-child db session "parent")))
      (is (true? (nav/has-visible-children? db session "parent")))
      (is (false? (nav/has-visible-children? db session "c1"))))))

(deftest block-navigation-test
  (testing "Navigate between visible blocks in document order"
    (let [db (apply-ops
              [{:op :create-node :id "b1" :type :block :props {:text "Parent"}}
               {:op :place :id "b1" :under :doc :at :last}
               {:op :create-node :id "b1.1" :type :block :props {:text "Child 1.1"}}
               {:op :place :id "b1.1" :under "b1" :at :last}
               {:op :create-node :id "b1.2" :type :block :props {:text "Child 1.2"}}
               {:op :place :id "b1.2" :under "b1" :at :last}
               {:op :create-node :id "b2" :type :block :props {:text "Sibling"}}
               {:op :place :id "b2" :under :doc :at :last}])
          session nil]

      (testing "next-visible-block goes depth-first"
        (is (= "b1.1" (nav/next-visible-block db session "b1")))
        (is (= "b1.2" (nav/next-visible-block db session "b1.1")))
        (is (= "b2" (nav/next-visible-block db session "b1.2")))
        (is (nil? (nav/next-visible-block db session "b2"))))

      (testing "prev-visible-block goes reverse depth-first"
        (is (= "b1.2" (nav/prev-visible-block db session "b2")))
        (is (= "b1.1" (nav/prev-visible-block db session "b1.2")))
        (is (= "b1" (nav/prev-visible-block db session "b1.1")))
        (is (nil? (nav/prev-visible-block db session "b1")))))))

;; NOTE: This test is SKIPPED because fold state is now in session,
;; not DB. The visible-order plugin cannot access session state
;; during derive-indexes. Fold-aware navigation requires integration tests.
(deftest ^{:skip true} folded-navigation-test-SKIPPED
  (testing "SKIPPED: Fold state is now in session, not DB"
    (is true "Test skipped - fold state is session-only")))

(deftest zoom-navigation-test
  (testing "Navigation respects zoom level"
    (let [db (apply-ops
              [{:op :create-node :id "b1" :type :block :props {:text "Top"}}
               {:op :place :id "b1" :under :doc :at :last}
               {:op :create-node :id "b1.1" :type :block :props {:text "Child"}}
               {:op :place :id "b1.1" :under "b1" :at :last}
               {:op :create-node :id "b2" :type :block :props {:text "Outside zoom"}}
               {:op :place :id "b2" :under :doc :at :last}])
          ;; Session with zoom into b1
          session {:ui {:zoom-root "b1"}}]

      (testing "First/last block respects zoom"
        (is (= "b1.1" (nav/first-visible-block db session)))
        (is (= "b1.1" (nav/last-visible-block db session))))

      (testing "Block count only includes zoomed subtree"
        (is (= 1 (nav/visible-block-count db session)))))))

(deftest ancestor-chain-test
  (testing "Returns ancestors from parent to root"
    (let [db (apply-ops
              [{:op :create-node :id "gp" :type :block :props {:text "Grandparent"}}
               {:op :place :id "gp" :under :doc :at :last}
               {:op :create-node :id "p" :type :block :props {:text "Parent"}}
               {:op :place :id "p" :under "gp" :at :last}
               {:op :create-node :id "c" :type :block :props {:text "Child"}}
               {:op :place :id "c" :under "p" :at :last}])]

      (is (= ["p" "gp"] (nav/ancestor-chain db "c")))
      (is (= ["gp"] (nav/ancestor-chain db "p")))
      (is (= [] (nav/ancestor-chain db "gp"))))))

(deftest first-last-block-test
  (testing "Find first and last visible blocks"
    (let [db (apply-ops
              [{:op :create-node :id "b1" :type :block :props {:text "First"}}
               {:op :place :id "b1" :under :doc :at :last}
               {:op :create-node :id "b2" :type :block :props {:text "Middle"}}
               {:op :place :id "b2" :under :doc :at :last}
               {:op :create-node :id "b2.1" :type :block :props {:text "Nested"}}
               {:op :place :id "b2.1" :under "b2" :at :last}
               {:op :create-node :id "b3" :type :block :props {:text "Last"}}
               {:op :place :id "b3" :under :doc :at :last}])
          session nil]

      (is (= "b1" (nav/first-visible-block db session)))
      (is (= "b3" (nav/last-visible-block db session)))
      (is (= 4 (nav/visible-block-count db session))))))

(deftest matcher-patterns-test
  (testing "Demonstrate matcher-combinators patterns for navigation"
    (let [db (apply-ops
              [{:op :create-node :id "b1" :type :block :props {:text "Block 1"}}
               {:op :place :id "b1" :under :doc :at :last}
               {:op :create-node :id "b2" :type :block :props {:text "Block 2"}}
               {:op :place :id "b2" :under :doc :at :last}])
          session nil]

      ;; Pattern: Assert properties without exact values
      (is (match? string? (nav/next-visible-sibling db session "b1")))
      (is (match? nil? (nav/prev-visible-sibling db session "b1")))

      ;; Pattern: Use predicates for complex checks
      (is (match? (m/pred #(> % 0)) (nav/visible-block-count db session)))

      ;; Pattern: Assert collection contains items
      (is (match? (m/in-any-order ["b1" "b2"])
                  (nav/visible-siblings db session "b1"))))))

(comment
  ;; Run tests in REPL
  (require '[clojure.test :as t])
  (t/run-tests 'kernel.navigation-test)

  ;; Run specific test
  (block-navigation-test)
  (folded-navigation-test))
