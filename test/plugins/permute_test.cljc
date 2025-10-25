(ns plugins.permute-test
  (:require [clojure.test :refer [deftest is testing]]
            [plugins.permute :as permute]
            [core.db :as db]
            [core.interpret :as interp]))

(defn make-test-db
  "Create a test DB with a tree structure for testing."
  []
  (-> (db/empty-db)
      (assoc :nodes {"A" {:type :p :props {}}
                     "B" {:type :p :props {}}
                     "C" {:type :p :props {}}
                     "D" {:type :p :props {}}
                     "X" {:type :p :props {}}
                     "P" {:type :p :props {}}
                     "Q" {:type :p :props {}}})
      (assoc :children-by-parent {"P" ["A" "B" "C" "D"]
                                  "Q" ["X"]})
      db/derive-indexes))

(deftest test-planned-positions-same-parent
  (testing "Planned positions for reorder within same parent"
    (let [db (make-test-db)
          intent {:selection ["B" "D"]
                  :parent "P"
                  :anchor {:after "A"}}
          result (permute/planned-positions db intent)]
      (is (= ["A" "B" "D" "C"] result)
          "B and D should be placed after A"))))

(deftest test-planned-positions-at-start
  (testing "Move to start"
    (let [db (make-test-db)
          intent {:selection ["C" "D"]
                  :parent "P"
                  :anchor :at-start}
          result (permute/planned-positions db intent)]
      (is (= ["C" "D" "A" "B"] result)))))

(deftest test-planned-positions-at-end
  (testing "Move to end"
    (let [db (make-test-db)
          intent {:selection ["A" "B"]
                  :parent "P"
                  :anchor :at-end}
          result (permute/planned-positions db intent)]
      (is (= ["C" "D" "A" "B"] result)))))

(deftest test-planned-positions-cross-parent
  (testing "Move nodes across parents"
    (let [db (make-test-db)
          intent {:selection ["B" "C"]
                  :parent "Q"
                  :anchor :at-end}
          result (permute/planned-positions db intent)]
      (is (= ["X" "B" "C"] result)
          "B and C should be appended to Q's children"))))

(deftest test-lower-reorder-simple
  (testing "Lower simple reorder to ops"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B" "D"]
                  :parent "P"
                  :anchor {:after "A"}}
          {:keys [ops issues]} (permute/lower db intent)]
      (is (empty? issues) "Should have no issues")
      (is (= 2 (count ops)) "Should emit one op per selected node")

      ;; First op places B after A
      (is (= {:op :place
              :id "B"
              :under "P"
              :at {:after "A"}}
             (first ops)))

      ;; Second op places D after B (building up the sequence)
      (is (= {:op :place
              :id "D"
              :under "P"
              :at {:after "B"}}
             (second ops))))))

(deftest test-lower-reorder-execution
  (testing "Executing lowered ops produces planned order"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B" "D"]
                  :parent "P"
                  :anchor {:after "A"}}
          {:keys [ops]} (permute/lower db intent)
          {:keys [db issues]} (interp/interpret db ops)]
      (is (empty? issues) "All ops should execute successfully")
      (is (= ["A" "B" "D" "C"]
             (get-in db [:children-by-parent "P"]))
          "Final order should match planned positions"))))

(deftest test-lower-move-cross-parent
  (testing "Move nodes across parents"
    (let [db (make-test-db)
          intent {:intent :move
                  :selection ["B" "C"]
                  :parent "Q"
                  :anchor :at-end}
          {:keys [ops issues]} (permute/lower db intent)]
      (is (empty? issues))
      (is (= 2 (count ops)))

      ;; Execute and verify
      (let [{:keys [db issues]} (interp/interpret db ops)]
        (is (empty? issues))
        (is (= ["X" "B" "C"] (get-in db [:children-by-parent "Q"]))
            "B and C should be in Q")
        (is (= ["A" "D"] (get-in db [:children-by-parent "P"]))
            "A and D should remain in P")))))

(deftest test-validate-intent-missing-node
  (testing "Validation catches missing node"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B" "MISSING"]
                  :parent "P"
                  :anchor :at-end}
          issue (permute/validate-intent db intent)]
      (is (some? issue))
      (is (= ::permute/node-not-found (:reason issue)))
      (is (= ["MISSING"] (:missing issue))))))

(deftest test-validate-intent-missing-parent
  (testing "Validation catches missing parent"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B"]
                  :parent "MISSING"
                  :anchor :at-end}
          issue (permute/validate-intent db intent)]
      (is (some? issue))
      (is (= ::permute/parent-not-found (:reason issue))))))

(deftest test-validate-intent-cycle
  (testing "Validation catches would-be cycles"
    ;; Create a nested structure: P -> A -> B
    (let [db (-> (make-test-db)
                 (assoc-in [:children-by-parent "A"] ["B"])
                 db/derive-indexes)
          ;; Try to move P under B (its grandchild)
          intent {:intent :move
                  :selection ["P"]
                  :parent "B"
                  :anchor :at-end}
          issue (permute/validate-intent db intent)]
      (is (some? issue))
      (is (= ::permute/would-create-cycle (:reason issue))))))

(deftest test-idempotence
  (testing "Applying the same reorder twice is idempotent"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B" "D"]
                  :parent "P"
                  :anchor {:after "A"}}
          {:keys [ops]} (permute/lower db intent)
          result1 (interp/interpret db ops)
          db1 (:db result1)
          ;; Apply again
          {:keys [ops]} (permute/lower db1 intent)
          result2 (interp/interpret db1 ops)
          db2 (:db result2)]
      (is (empty? (:issues result1)))
      (is (empty? (:issues result2)))
      (is (= (get-in db1 [:children-by-parent "P"])
             (get-in db2 [:children-by-parent "P"]))
          "Applying twice should produce same result"))))

(deftest test-non-contiguous-selection
  (testing "Non-contiguous selection maintains relative order"
    (let [db (make-test-db)
          ;; Select B and D (skip C)
          intent {:intent :reorder
                  :selection ["B" "D"]
                  :parent "P"
                  :anchor :at-start}
          {:keys [ops]} (permute/lower db intent)
          {:keys [db issues]} (interp/interpret db ops)]
      (is (empty? issues))
      (is (= ["B" "D" "A" "C"]
             (get-in db [:children-by-parent "P"]))
          "B and D should maintain their relative order"))))

(deftest test-empty-selection
  (testing "Empty selection produces no ops"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection []
                  :parent "P"
                  :anchor :at-end}
          {:keys [ops issues]} (permute/lower db intent)]
      (is (empty? issues))
      (is (empty? ops)))))

(deftest test-single-item-selection
  (testing "Single item selection works correctly"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["D"]
                  :parent "P"
                  :anchor :at-start}
          {:keys [ops]} (permute/lower db intent)
          {:keys [db issues]} (interp/interpret db ops)]
      (is (empty? issues))
      (is (= ["D" "A" "B" "C"]
             (get-in db [:children-by-parent "P"]))))))
