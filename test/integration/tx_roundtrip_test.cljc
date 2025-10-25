(ns integration.tx-roundtrip-test
  "Smoke tests for create → place → update → derive pipeline.

   Tests that lock down basic transaction behavior during refactor:
   - Schema validation
   - Derived index consistency
   - Operation roundtrip correctness"
  (:require [clojure.test :refer [deftest is testing]]
            [test-helper :as helper]
            [kernel.db :as DB]
            [kernel.transaction :as tx]
            [kernel.query :as q]))

(deftest create-place-update-roundtrip
  (testing "Create → place → update produces valid DB with correct derived state"
    (let [db0 (DB/empty-db)
          {:keys [db issues]} (tx/interpret db0
                                             [{:op :create-node :id "test" :type :block :props {:text "Initial"}}
                                              {:op :place :id "test" :under :doc :at :first}
                                              {:op :update-node :id "test" :props {:text "Updated"}}])]
      (is (empty? issues)
          "Transaction should complete without issues")
      (is (helper/valid-db? db)
          "Final DB should validate against schema")
      (is (helper/derive-consistent? db)
          "Derived indexes should match canonical state")
      (is (= "Updated" (get-in db [:nodes "test" :props :text]))
          "Update should modify props")
      (is (= :doc (q/parent-of db "test"))
          "Node should be placed under :doc root"))))

(deftest demo-db-fixture-valid
  (testing "Golden demo-db fixture is valid and consistent"
    (let [db (helper/demo-db)]
      (is (helper/valid-db? db)
          "demo-db should validate against schema")
      (is (helper/derive-consistent? db)
          "demo-db derived indexes should match canonical state")
      (is (= ["a" "b" "c" "d"] (q/children db "page"))
          "Page should have 4 direct children in correct order")
      (is (= ["d1"] (q/children db "d"))
          "Block d should have nested child d1"))))

(deftest derive-indexes-covers-all-canonical
  (testing "Derived indexes cover all relationships in canonical state"
    (let [db (helper/demo-db)]
      ;; All nodes (except roots) should have parent
      (doseq [id (-> db :children-by-parent vals flatten)]
        (is (contains? (-> db :derived :parent-of) id)
            (str "Node " id " should have parent in derived :parent-of")))

      ;; All children should have index
      (doseq [id (-> db :children-by-parent vals flatten)]
        (is (contains? (-> db :derived :index-of) id)
            (str "Node " id " should have index in derived :index-of")))

      ;; Prev/next should form valid chain
      (doseq [[parent children] (:children-by-parent db)
              :when (> (count children) 1)]
        (let [prev-map (-> db :derived :prev-id-of)
              next-map (-> db :derived :next-id-of)]
          (is (nil? (get prev-map (first children)))
              (str "First child should have nil prev: " (first children)))
          (is (nil? (get next-map (last children)))
              (str "Last child should have nil next: " (last children))))))))

(deftest multiple-ops-maintain-invariants
  (testing "Multiple operations maintain DB invariants"
    (let [db0 (helper/demo-db)
          db1 (helper/dispatch* db0
                                {:type :select :ids "b"})
          db2 (helper/dispatch* db1
                                {:type :update-content :block-id "b" :text "Modified B"})]
      (is (helper/valid-db? db2)
          "DB should remain valid after multiple operations")
      (is (helper/derive-consistent? db2)
          "Derived state should remain consistent")
      (is (= "Modified B" (get-in db2 [:nodes "b" :props :text]))
          "Props update should persist"))))
