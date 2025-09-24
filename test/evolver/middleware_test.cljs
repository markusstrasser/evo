(ns evolver.middleware-test
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.middleware :as middleware]
            [evolver.kernel :as kernel]))

(deftest test-middleware-pipeline
  (testing "Individual middleware step functions work"
    (let [db kernel/db
          command {:op :insert
                   :parent-id "root"
                   :node-id "test-node"
                   :node-data {:type :div :props {:text "Test"}}
                   :position nil}]

      ;; Test validation step
      (let [ctx {:db db :cmd command :log [] :errors [] :effects []}
            result (middleware/validate-cmd-step ctx)]
        (is (empty? (:errors result)) "Valid command should not produce errors"))

      ;; Test invalid command produces errors
      (let [invalid-ctx {:db db :cmd {} :log [] :errors [] :effects []}
            result (middleware/validate-cmd-step invalid-ctx)]
        (is (seq (:errors result)) "Invalid command should produce errors"))))

  (testing "Pipeline execution works end-to-end"
    (let [db kernel/db
          command {:op :insert
                   :parent-id "root"
                   :node-id "test-node"
                   :node-data {:type :div :props {:text "Test"}}
                   :position nil}
          result (middleware/safe-apply-command-with-middleware db command)]

      (is (map? result) "Result should be a database map")
      (is (contains? (:nodes result) "test-node") "New node should be added")
      (is (= {:type :div :props {:text "Test"}} (get-in result [:nodes "test-node"])))))

  (testing "Pipeline handles commands that don't break structure"
    (let [db kernel/db
          command {:op :insert
                   :parent-id "nonexistent"
                   :node-id "test-node"
                   :node-data {:type :div :props {:text "Test"}}
                   :position nil}
          result (middleware/safe-apply-command-with-middleware db command)]

      (is (map? result) "Should return a database map")
      (is (contains? (:nodes result) "test-node") "Node should be created"))))

(deftest test-middleware-order
  (testing "Pipeline executes steps in correct order"
    (let [execution-order (atom [])
          test-step-1 (fn [ctx]
                        (swap! execution-order conj :step-1)
                        ctx)
          test-step-2 (fn [ctx]
                        (swap! execution-order conj :step-2)
                        ctx)
          test-step-3 (fn [ctx]
                        (swap! execution-order conj :step-3)
                        ctx)
          pipeline [test-step-1 test-step-2 test-step-3]
          initial-ctx {:db kernel/db :cmd {:op :undo} :log [] :errors [] :effects []}
          result (middleware/run-pipeline initial-ctx pipeline)]

      (is (= [:step-1 :step-2 :step-3] @execution-order)))))

(deftest test-operation-logging
  (testing "Operations are processed in the pipeline"
    (let [db kernel/db
          command {:op :insert
                   :parent-id "root"
                   :node-id "test-log"
                   :node-data {:type :div :props {:text "Test Log"}}
                   :position nil}
          result (middleware/safe-apply-command-with-middleware db command)]

      ;; Check that the result is different from input (indicating processing occurred)
      (is (not= db result) "Command should modify database"))))

(deftest test-validation-middleware
  (testing "Result validation step works"
    (let [db kernel/db
          command {:op :undo}
          ctx {:db db :cmd command :log [] :errors [] :effects []}]

      ;; Test that validation step doesn't produce errors on valid state
      (let [result (middleware/validate-result-step ctx)]
        (is (empty? (:errors result)) "Valid state should not produce errors")))))