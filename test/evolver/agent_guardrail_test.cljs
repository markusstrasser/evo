(ns evolver.agent-guardrail-test
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [agent.core :as agent]
            [agent.schemas :as schemas]))

(deftest test-detect-environment
  (testing "Environment detection"
    (let [env (agent/detect-environment)]
      (is (map? env))
      (is (contains? env :browser?))
      (is (contains? env :node?))
      (is (contains? env :store-accessible?))
      (is (contains? env :cljs-repl?)))))

(deftest test-validate-environment-for-operation
  (testing "Environment validation for different operations"
    ;; Node environment should work for node-test
    (is (thrown? js/Error (agent/validate-environment-for-operation :dom-manipulation)))
    (is (thrown? js/Error (agent/validate-environment-for-operation :store-access)))
    ;; This should not throw in node environment
    (is (map? (agent/validate-environment-for-operation :node-test)))))

(deftest test-validate-file-namespace-alignment
  (testing "Namespace alignment validation"
    ;; Valid alignment
    (is (= nil (agent/validate-file-namespace-alignment "src/agent/core.cljc" '(ns agent.core))))
    ;; Invalid alignment
    (is (thrown? js/Error (agent/validate-file-namespace-alignment "src/agent/core.cljc" '(ns wrong.namespace))))))

(deftest test-validate-operation-schema
  (testing "Schema validation"
    ;; Valid data
    (is (= {:node-id "test"} (agent/validate-operation-schema {:node-id "test"} :select-node-params)))
    ;; Invalid data
    (is (thrown? js/Error (agent/validate-operation-schema {:node-id 123} :select-node-params)))))

(deftest test-validate-command-params
  (testing "Command parameter validation"
    ;; Valid params
    (is (= {:node-id "test"} (agent/validate-command-params :select-node {:node-id "test"})))
    ;; Invalid params
    (is (thrown? js/Error (agent/validate-command-params :select-node {:node-id 123})))))


(deftest test-safe-command-dispatch
  (testing "Safe command dispatch should fail in node environment"
    ;; Should throw because store access requires browser
    (is (thrown? js/Error (agent/safe-command-dispatch nil nil [:select-node {:node-id "test"}])))))

(deftest test-safe-schema-validated-dispatch
  (testing "Safe schema validated dispatch should fail in node environment"
    ;; Should throw because store access requires browser
    (is (thrown? js/Error (agent/safe-schema-validated-dispatch nil nil [:select-node {:node-id "test"}])))))

(deftest test-schema-functions
  (testing "Schema wrapper functions"
    (is (true? (schemas/validate [:map [:x int?]] {:x 1})))
    (is (false? (schemas/validate [:map [:x int?]] {:x "string"})))
    (is (some? (schemas/explain [:map [:x int?]] {:x "string"})))
    (is (map? (schemas/humanize (schemas/explain [:map [:x int?]] {:x "string"}))))))

;; Run tests
(run-tests)