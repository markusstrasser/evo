(ns plugins.permute-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [plugins.structural :as struct]
            [kernel.db :as db]
            [kernel.transaction :as tx]))

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
          result (struct/planned-positions db intent)]
      (is (= ["A" "B" "D" "C"] result)
          "B and D should be placed after A"))))

(deftest test-planned-positions-at-start
  (testing "Move to start"
    (let [db (make-test-db)
          intent {:selection ["C" "D"]
                  :parent "P"
                  :anchor :at-start}
          result (struct/planned-positions db intent)]
      (is (= ["C" "D" "A" "B"] result)))))

(deftest test-planned-positions-at-end
  (testing "Move to end"
    (let [db (make-test-db)
          intent {:selection ["A" "B"]
                  :parent "P"
                  :anchor :at-end}
          result (struct/planned-positions db intent)]
      (is (= ["C" "D" "A" "B"] result)))))

(deftest test-planned-positions-cross-parent
  (testing "Move nodes across parents"
    (let [db (make-test-db)
          intent {:selection ["B" "C"]
                  :parent "Q"
                  :anchor :at-end}
          result (struct/planned-positions db intent)]
      (is (= ["X" "B" "C"] result)
          "B and C should be appended to Q's children"))))

(deftest test-lower-reorder-simple
  (testing "Lower simple reorder to ops"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B" "D"]
                  :parent "P"
                  :anchor {:after "A"}}
          {:keys [ops issues]} (struct/lower-move db intent)]
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
          {:keys [ops]} (struct/lower-move db intent)
          {:keys [db issues]} (tx/txret db ops)]
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
          {:keys [ops issues]} (struct/lower-move db intent)]
      (is (empty? issues))
      (is (= 2 (count ops)))

      ;; Execute and verify
      (let [{:keys [db issues]} (tx/txret db ops)]
        (is (empty? issues))
        (is (= ["X" "B" "C"] (get-in db [:children-by-parent "Q"]))
            "B and C should be in Q")
        (is (= ["A" "D"] (get-in db [:children-by-parent "P"]))
            "A and D should remain in P")))))

(deftest test-validate-move-intent-missing-node
  (testing "Validation catches missing node"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B" "MISSING"]
                  :parent "P"
                  :anchor :at-end}
          issue (struct/validate-move-intent db intent)]
      (is (some? issue))
      (is (= ::struct/node-not-found (:reason issue)))
      (is (= ["MISSING"] (:missing issue))))))

(deftest test-validate-move-intent-missing-parent
  (testing "Validation catches missing parent"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B"]
                  :parent "MISSING"
                  :anchor :at-end}
          issue (struct/validate-move-intent db intent)]
      (is (some? issue))
      (is (= ::struct/parent-not-found (:reason issue))))))

(deftest test-validate-move-intent-cycle
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
          issue (struct/validate-move-intent db intent)]
      (is (some? issue))
      (is (= ::struct/would-create-cycle (:reason issue))))))

(deftest test-idempotence
  (testing "Applying the same reorder twice is idempotent"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["B" "D"]
                  :parent "P"
                  :anchor {:after "A"}}
          {:keys [ops]} (struct/lower-move db intent)
          result1 (tx/txret db ops)
          db1 (:db result1)
          ;; Apply again
          {:keys [ops]} (struct/lower-move db1 intent)
          result2 (tx/txret db1 ops)
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
          {:keys [ops]} (struct/lower-move db intent)
          {:keys [db issues]} (tx/txret db ops)]
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
          {:keys [ops issues]} (struct/lower-move db intent)]
      (is (empty? issues))
      (is (empty? ops)))))

(deftest test-single-item-selection
  (testing "Single item selection works correctly"
    (let [db (make-test-db)
          intent {:intent :reorder
                  :selection ["D"]
                  :parent "P"
                  :anchor :at-start}
          {:keys [ops]} (struct/lower-move db intent)
          {:keys [db issues]} (tx/txret db ops)]
      (is (empty? issues))
      (is (= ["D" "A" "B" "C"]
             (get-in db [:children-by-parent "P"]))))))

;; ── Property-Based Tests ──────────────────────────────────────────────────────

(def gen-id
  "Generate simple string IDs for testing."
  (gen/fmap (fn [n] (str "node-" n)) (gen/choose 1 20)))

(def gen-parent-id
  (gen/fmap (fn [n] (str "parent-" n)) (gen/choose 1 3)))

(def gen-children-list
  "Generate a list of 3-8 unique child IDs under a parent."
  (gen/fmap (fn [ids] (vec (distinct ids)))
            (gen/vector gen-id 3 8)))

(defspec reorder-intent-matches-permutation-algebra
  100
  (prop/for-all [children gen-children-list
                 target-idx (gen/choose 0 7)
                 ;; Generate a deterministic selection by shuffling and taking a prefix
                 num-to-take (gen/choose 1 8)
                 shuffled-children (gen/shuffle children)]
    (let [parent "test-parent"
          ;; Take a deterministic subset
          selection (vec (take (min num-to-take (count shuffled-children)) shuffled-children))

          ;; Build DB with these children
          db (-> (db/empty-db)
                 (assoc :nodes (assoc (zipmap children (repeat {:type :p :props {}}))
                                     parent {:type :p :props {}}))
                 (assoc-in [:children-by-parent parent] (vec children))
                 db/derive-indexes)

          ;; Create reorder intent (move selection to target-idx)
          anchor-idx (min target-idx (dec (count children)))
          anchor (if (zero? anchor-idx)
                   :first
                   {:after (nth children (dec anchor-idx))})

          intent {:intent :reorder
                  :selection selection
                  :parent parent
                  :anchor anchor}

          ;; Method 1: Use intent lowering
          {:keys [ops]} (struct/lower-move db intent)
          {:keys [db issues]} (tx/txret db ops)

          ;; Method 2: Direct permutation application
          planned (struct/planned-positions db intent)

          ;; Both should yield same final order
          actual-children (get-in db [:children-by-parent parent])]

      (and (empty? issues)
           (= planned actual-children)))))
