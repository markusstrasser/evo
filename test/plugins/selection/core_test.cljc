(ns plugins.selection.core-test
  "Tests for boolean-based selection plugin."
  (:require [clojure.test :refer [deftest testing is]]
            [core.db :as db]
            [core.interpret :as interp]
            [plugins.selection.core :as sel]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn interpret-ops
  "Helper to interpret ops and return resulting db."
  [db ops]
  (:db (interp/interpret db ops)))

(defn create-base-db
  "Create a base db with doc nodes."
  []
  (-> (db/empty-db)
      (interpret-ops
       [{:op :create-node :id "doc-a" :type :paragraph :props {}}
        {:op :create-node :id "doc-b" :type :paragraph :props {}}
        {:op :create-node :id "doc-c" :type :paragraph :props {}}
        {:op :place :id "doc-a" :under :doc :at :last}
        {:op :place :id "doc-b" :under :doc :at :last}
        {:op :place :id "doc-c" :under :doc :at :last}])))

;; =============================================================================
;; Derived Index Tests
;; =============================================================================

(deftest derived-index-empty-test
  (testing "Empty selection when no nodes selected"
    (let [db (create-base-db)]
      (is (= #{} (get-in db [:derived :selection/active]))))))

(deftest derived-index-single-selection-test
  (testing "Single selected node appears in derived index"
    (let [db0 (create-base-db)
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:selected? true}}])]
      (is (= #{"doc-a"} (get-in db1 [:derived :selection/active]))))))

(deftest derived-index-multiple-selections-test
  (testing "Multiple selected nodes appear in derived index"
    (let [db0 (create-base-db)
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:selected? true}}
                                  {:op :update-node
                                   :id "doc-c"
                                   :props {:selected? true}}])]
      (is (= #{"doc-a" "doc-c"} (get-in db1 [:derived :selection/active]))))))

;; =============================================================================
;; Intent Compiler Tests
;; =============================================================================

(deftest toggle-selection-test
  (testing "Toggle adds selection when not selected"
    (let [db0 (create-base-db)
          op (sel/toggle-selection-op db0 "doc-a")
          db1 (interpret-ops db0 [op])]
      (is (= true (get-in db1 [:nodes "doc-a" :props :selected?])))
      (is (= #{"doc-a"} (get-in db1 [:derived :selection/active])))))

  (testing "Toggle removes selection when selected"
    (let [db0 (create-base-db)
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:selected? true}}])
          op (sel/toggle-selection-op db1 "doc-a")
          db2 (interpret-ops db1 [op])]
      (is (= false (get-in db2 [:nodes "doc-a" :props :selected?])))
      (is (= #{} (get-in db2 [:derived :selection/active]))))))

(deftest toggle-idempotence-test
  (testing "Toggle twice returns to original state"
    (let [db0 (create-base-db)
          op1 (sel/toggle-selection-op db0 "doc-a")
          db1 (interpret-ops db0 [op1])
          op2 (sel/toggle-selection-op db1 "doc-a")
          db2 (interpret-ops db1 [op2])]
      ;; After two toggles, both should be falsey (nil or false)
      (is (not (get-in db0 [:nodes "doc-a" :props :selected?])))
      (is (not (get-in db2 [:nodes "doc-a" :props :selected?])))
      ;; And neither should be in active selections
      (is (not (contains? (get-in db0 [:derived :selection/active] #{}) "doc-a")))
      (is (not (contains? (get-in db2 [:derived :selection/active] #{}) "doc-a"))))))

(deftest select-op-idempotent-test
  (testing "select-op is idempotent"
    (let [db0 (create-base-db)
          op1 (sel/select-op db0 "doc-a")
          db1 (interpret-ops db0 [op1])
          op2 (sel/select-op db1 "doc-a")
          db2 (interpret-ops db1 [op2])]
      (is (= true (get-in db1 [:nodes "doc-a" :props :selected?])))
      (is (= true (get-in db2 [:nodes "doc-a" :props :selected?]))))))

(deftest deselect-op-idempotent-test
  (testing "deselect-op is idempotent"
    (let [db0 (create-base-db)
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:selected? true}}])
          op1 (sel/deselect-op db1 "doc-a")
          db2 (interpret-ops db1 [op1])
          op2 (sel/deselect-op db2 "doc-a")
          db3 (interpret-ops db2 [op2])]
      (is (= false (get-in db2 [:nodes "doc-a" :props :selected?])))
      (is (= false (get-in db3 [:nodes "doc-a" :props :selected?]))))))

(deftest clear-all-selections-test
  (testing "clear-all-selections-ops clears all selected nodes"
    (let [db0 (create-base-db)
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:selected? true}}
                                  {:op :update-node
                                   :id "doc-b"
                                   :props {:selected? true}}])
          clear-ops (sel/clear-all-selections-ops db1)
          db2 (interpret-ops db1 clear-ops)]
      (is (= 2 (count (get-in db1 [:derived :selection/active]))))
      (is (= #{} (get-in db2 [:derived :selection/active]))))))

;; =============================================================================
;; Query Helper Tests
;; =============================================================================

(deftest selected?-test
  (testing "selected? returns correct boolean"
    (let [db0 (create-base-db)
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:selected? true}}])]
      (is (false? (sel/selected? db0 "doc-a")))
      (is (true? (sel/selected? db1 "doc-a")))
      (is (false? (sel/selected? db1 "doc-b"))))))

(deftest get-selected-nodes-test
  (testing "get-selected-nodes returns active selections"
    (let [db0 (create-base-db)
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:selected? true}}
                                  {:op :update-node
                                   :id "doc-c"
                                   :props {:selected? true}}])]
      (is (= #{} (sel/get-selected-nodes db0)))
      (is (= #{"doc-a" "doc-c"} (sel/get-selected-nodes db1))))))

;; =============================================================================
;; Structural Invariance Tests
;; =============================================================================

(deftest structural-invariance-test
  (testing "Selection does not affect structural indexes"
    (let [db0 (create-base-db)
          struct0 (select-keys (:derived db0) [:parent-of :index-of :pre :post])
          db1 (interpret-ops db0 [(sel/select-op db0 "doc-a")
                                  (sel/select-op db0 "doc-b")])
          struct1 (select-keys (:derived db1) [:parent-of :index-of :pre :post])]
      (is (= struct0 struct1)))))
