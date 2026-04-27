(ns scripts.script-test
  (:require [clojure.test :refer [deftest is testing]]
            [scripts.script :as script]
            [kernel.transaction :as tx]
            [kernel.db :as db]
            [fixtures :as fix]))

;; ── Basic Step Types ───────────────────────────────────────────────────────────

(deftest test-step->ops-nil
  (testing "nil step returns empty vector"
    (let [db (fix/sample-db)
          result (script/step->ops db nil)]
      (is (= [] result)))))

(deftest test-step->ops-single-op
  (testing "Single op wrapped in vector"
    (let [db (fix/sample-db)
          op {:op :update-node :id "a" :props {:text "test"}}
          result (script/step->ops db op)]
      (is (= [op] result)))))

(deftest test-step->ops-vector-of-ops
  (testing "Vector of ops passed through"
    (let [db (fix/sample-db)
          ops [{:op :update-node :id "a" :props {:text "1"}}
               {:op :update-node :id "b" :props {:text "2"}}]
          result (script/step->ops db ops)]
      (is (= ops result)))))

(deftest test-step->ops-function
  (testing "Function called with db, result compiled"
    (let [db (fix/sample-db)
          step-fn (fn [_db] {:op :update-node :id "a" :props {:text "from-fn"}})
          result (script/step->ops db step-fn)]
      (is (= [{:op :update-node :id "a" :props {:text "from-fn"}}] result)))))

(deftest test-step->ops-function-returns-vector
  (testing "Function returning vector of steps"
    (let [db (fix/sample-db)
          step-fn (fn [_db]
                    [{:op :update-node :id "a" :props {:text "1"}}
                     {:op :update-node :id "b" :props {:text "2"}}])
          result (script/step->ops db step-fn)]
      (is (= [{:op :update-node :id "a" :props {:text "1"}}
              {:op :update-node :id "b" :props {:text "2"}}]
             result)))))

(deftest test-step->ops-rejects-intent-forms
  (testing "Scripts reject intent-shaped steps"
    (let [db (fix/sample-db)]
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error)
            #"Unknown step form"
            (script/step->ops db {:type :selection :mode :replace :ids ["a"]})))
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error)
            #"Unknown step form"
            (script/step->ops db [{:type :selection :mode :replace :ids ["a"]}]))))))

;; ── Macro Runner ───────────────────────────────────────────────────────────────

(deftest test-run-empty-steps
  (testing "Empty steps vector returns empty ops"
    (let [db (fix/sample-db)
          result (script/run db [])]
      (is (= [] (:ops result)))
      (is (= db (:db result)))  ; Scratch DB unchanged
      (is (= [] (:trace result))))))

(defn- strip-timestamps
  "Remove auto-generated timestamps from ops for comparison."
  [op]
  (update op :props dissoc :created-at :updated-at))

(deftest test-run-single-step
  (testing "Single step collected in ops"
    (let [db (fix/sample-db-with-roots)
          op {:op :create-node :id "new" :type :block :props {:text "test"}}
          result (script/run db [op])]

      ;; Compare ops without auto-added timestamps
      (is (= [op] (mapv strip-timestamps (:ops result))))
      (is (= 1 (count (:trace result))))

      ;; Verify trace entry
      (let [trace-entry (first (:trace result))]
        (is (= op (:step trace-entry)))
        (is (= 0 (:step-index trace-entry)))
        ;; Ops in trace have timestamps, compare without them
        (is (= [op] (mapv strip-timestamps (:ops trace-entry))))))))

(deftest test-run-multiple-steps
  (testing "Multiple steps accumulated"
    (let [db (-> (fix/sample-db-with-roots)
                 (assoc-in [:nodes "a"] {:type :block :props {:text "A"}}))
          steps [{:op :update-node :id "a" :props {:text "Step 1"}}
                 {:op :update-node :id "a" :props {:text "Step 2"}}]
          result (script/run db steps)]

      (is (= 2 (count (:ops result))))
      (is (= 2 (count (:trace result))))

      ;; Each step recorded in trace
      (is (= 0 (:step-index (nth (:trace result) 0))))
      (is (= 1 (:step-index (nth (:trace result) 1)))))))

(deftest test-run-passes-transaction-opts
  (testing "script/run forwards materialization opts into tx/dry-run"
    (let [db (fix/sample-db-with-roots)
          result (script/run db
                             [{:op :create-node :id "new" :type :block :props {:text "N"}}
                              {:op :update-node :id "new" :props {:text "N2"}}]
                             {:tx/now-ms 42})]
      (is (= 42 (get-in (:ops result) [0 :props :created-at])))
      (is (= 42 (get-in (:ops result) [0 :props :updated-at])))
      (is (= 42 (get-in (:ops result) [1 :props :updated-at]))))))

(deftest test-run-with-function-step
  (testing "Function sees intermediate state"
    (let [db (-> (fix/sample-db-with-roots)
                 (assoc-in [:nodes "a"] {:type :block :props {:text "initial"}}))

          ;; Step 1: Update text
          ;; Step 2: Function reads updated text
          steps [{:op :update-node :id "a" :props {:text "updated"}}

                 (fn [scratch-db]
                   ;; This should see the updated text
                   (let [text (get-in scratch-db [:nodes "a" :props :text])]
                     (is (= "updated" text))  ; Assert in test
                     {:op :update-node :id "a" :props {:tag text}}))]

          result (script/run db steps)]

      ;; Both ops accumulated
      (is (= 2 (count (:ops result))))

      ;; Verify second op has tag from updated text
      (is (= "updated" (get-in (second (:ops result)) [:props :tag]))))))

(deftest test-run-normalization-applied
  (testing "Ops are normalized during macro"
    (let [db (-> (fix/sample-db-with-roots)
                 (assoc-in [:nodes "a"] {:type :block :props {}})
                 (assoc-in [:children-by-parent :doc] ["a"]))

          ;; Place op with :at :last anchor (needs normalization)
          steps [{:op :place :id "a" :under :doc :at :last}]

          result (script/run db steps)]

      ;; Op was normalized (anchor resolved)
      ;; Note: normalize-ops canonicalizes :at-end → :last, etc.
      ;; The actual normalization keeps :at :last for this case
      (is (= 1 (count (:ops result)))))))

(deftest test-run-validation-failure
  (testing "Invalid op throws with trace"
    (let [db (fix/sample-db-with-roots)

          ;; Try to place non-existent node
          steps [{:op :place :id "nonexistent" :under :doc :at :last}]]

      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error)
            #"Script step failed validation"
            (script/run db steps))))))

(deftest test-run-max-steps-guard
  (testing "Max steps prevents infinite loops"
    (let [db (-> (fix/sample-db-with-roots)
                 (assoc-in [:nodes "test"] {:type :block :props {:counter 0}}))

          ;; Create 100 update steps to exceed max-steps limit
          many-steps (repeatedly 100 (constantly {:op :update-node :id "test" :props {:counter 1}}))]

      ;; Should throw when steps exceed max-steps
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error)
            #"exceeded max-steps"
            (script/run db many-steps {:max-steps 10}))))))

;; ── Integration: Simulate Then Commit ─────────────────────────────────────────

(deftest test-simulate-then-commit
  (testing "Macro simulation matches final commit"
    (let [db (-> (fix/sample-db-with-roots)
                 (assoc-in [:nodes "a"] {:type :block :props {:text "A"}})
                 (assoc-in [:nodes "b"] {:type :block :props {:text "B"}})
                 (assoc-in [:children-by-parent :doc] ["a" "b"])
                 db/derive-indexes)

          ;; Macro: Update both blocks
          steps [{:op :update-node :id "a" :props {:text "A-updated"}}
                 {:op :update-node :id "b" :props {:text "B-updated"}}]

          ;; Run macro (simulation)
          macro-result (script/run db steps)

          ;; Commit accumulated ops to real DB
          tx-result (tx/interpret db (:ops macro-result))]

      ;; No validation issues
      (is (empty? (:issues tx-result)))

      ;; Both updates applied
      (is (= "A-updated" (get-in (:db tx-result) [:nodes "a" :props :text])))
      (is (= "B-updated" (get-in (:db tx-result) [:nodes "b" :props :text])))

      ;; Scratch DB final state matches committed DB
      (is (= (get-in (:db macro-result) [:nodes "a" :props :text])
             (get-in (:db tx-result) [:nodes "a" :props :text])))
      (is (= (get-in (:db macro-result) [:nodes "b" :props :text])
             (get-in (:db tx-result) [:nodes "b" :props :text])))
      (is (= (:db macro-result) (:db tx-result))
          "Final scratch DB equals committing accumulated ops to the original DB"))))

;; ── run-ops Convenience ────────────────────────────────────────────────────────

(deftest test-run-ops-returns-ops-only
  (testing "run-ops returns just ops vector"
    (let [db (fix/sample-db-with-roots)
          steps [{:op :create-node :id "new" :type :block :props {}}]
          result (script/run-ops db steps)]

      ;; Result is ops vector, not map
      (is (vector? result))
      (is (= 1 (count result)))
      (is (= :create-node (:op (first result)))))))
