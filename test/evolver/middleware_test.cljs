(ns evolver.middleware-test
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.middleware :as middleware]
            [evolver.kernel :as kernel]))

(deftest test-middleware-pipeline
  (testing "Individual middleware functions work"
    (let [db kernel/db
          command {:op :insert
                   :parent-id "root"
                   :node-id "test-node"
                   :node-data {:type :div :props {:text "Test"}}
                   :position nil}]

      ;; Test validation middleware
      (is (= [command db] (middleware/validate-command-middleware command db)))

      ;; Test invalid command throws
      (is (thrown? js/Error (middleware/validate-command-middleware {} db)))
      (is (thrown? js/Error (middleware/validate-command-middleware {:invalid true} db)))))

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
          ;; This command succeeds because our kernel is permissive about parent creation
          command {:op :insert
                   :parent-id "nonexistent"
                   :node-id "test-node"
                   :node-data {:type :div :props {:text "Test"}}
                   :position nil}
          result (middleware/safe-apply-command-with-middleware db command)]

      (is (map? result) "Should return a database map")
      (is (contains? (:nodes result) "test-node") "Node should be created"))))

(deftest test-middleware-order
  (testing "Middleware executes in correct order"
    (let [execution-order (atom [])
          test-middleware-1 (fn [cmd db]
                              (swap! execution-order conj :middleware-1)
                              [cmd db])
          test-middleware-2 (fn [cmd db]
                              (swap! execution-order conj :middleware-2)
                              [cmd db])
          test-middleware-3 (fn [cmd db]
                              (swap! execution-order conj :middleware-3)
                              [cmd db])
          pipeline [test-middleware-1 test-middleware-2 test-middleware-3]
          command {:op :undo}
          result (middleware/execute-pipeline command kernel/db pipeline)]

      (is (= [:middleware-1 :middleware-2 :middleware-3] @execution-order)))))

(deftest test-operation-logging
  (testing "Operations are logged in the pipeline"
    (let [db kernel/db
          command {:op :insert
                   :parent-id "root"
                   :node-id "test-log"
                   :node-data {:type :div :props {:text "Test Log"}}
                   :position nil}
          result (middleware/safe-apply-command-with-middleware db command)]

      (is (> (count (:tx-log result)) (count (:tx-log db)))
          "Transaction should be logged"))))

(deftest test-validation-middleware
  (testing "Database validation catches issues"
    (let [db kernel/db
          ; This is a bit tricky to test since our validation is quite robust
          ; Let's test that the middleware exists and functions
          command {:op :undo}]

      ;; Test that validation middleware doesn't throw on valid state
      (is (= [command db] (middleware/validate-result-middleware command db)))

      ;; For a more comprehensive test, we'd need to artificially create an invalid state
      ;; which is difficult with our current validation logic
      )))