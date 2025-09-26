#!/usr/bin/env bb
;; Simple validation test for the new guardrails
;; Run with: bb test-guardrails.clj

(ns test-guardrails
  (:require [agent.core :as agent]
            [agent.schemas :as schemas]))

(defn test-environment-detection []
  (println "Testing environment detection...")
  (let [env (agent/detect-environment)]
    (println "Environment:" env)
    (assert (map? env) "Environment should be a map")
    (println "✓ Environment detection works")))

(defn test-schema-validation []
  (println "Testing schema validation...")
  ;; Test valid data
  (let [valid-params {:node-id "test-node"}]
    (agent/validate-operation-schema valid-params :select-node-params)
    (println "✓ Valid schema passes"))

  ;; Test invalid data
  (try
    (agent/validate-operation-schema {:node-id 123} :select-node-params)
    (println "✗ Should have failed validation")
    (catch Exception e
      (println "✓ Invalid schema correctly rejected:" (-> e ex-data :errors)))))

(defn test-namespace-alignment []
  (println "Testing namespace alignment...")
  ;; Test valid alignment
  (agent/validate-file-namespace-alignment
   "src/agent/core.cljc"
   '(ns agent.core))
  (println "✓ Valid namespace alignment passes")

  ;; Test invalid alignment
  (try
    (agent/validate-file-namespace-alignment
     "src/agent/core.cljc"
     '(ns wrong.namespace))
    (println "✗ Should have failed alignment check")
    (catch Exception e
      (println "✓ Invalid alignment correctly rejected:" (-> e ex-data :fix)))))

(defn test-data-access-patterns []
  (println "Testing data access pattern validation...")
  ;; Test valid CLJS pattern
  (agent/validate-data-access-pattern "(:state store)")
  (println "✓ Valid CLJS data access passes")

  ;; Test invalid JS pattern
  (try
    (agent/validate-data-access-pattern "store.state.view")
    (println "✗ Should have failed JS pattern check")
    (catch Exception e
      (println "✓ JS-style access correctly rejected:" (-> e ex-data :suggestion)))))

(defn test-nil-safety []
  (println "Testing nil-safe functions...")
  ;; Test safe-name
  (assert (= "test" (agent/safe-name :test)))
  (assert (nil? (agent/safe-name nil)))
  (println "✓ safe-name works correctly")

  ;; Test safe-first
  (assert (= 1 (agent/safe-first [1 2 3])))
  (assert (nil? (agent/safe-first [])))
  (assert (nil? (agent/safe-first nil)))
  (println "✓ safe-first works correctly"))

(defn test-event-handler-validation []
  (println "Testing event handler validation...")
  ;; Test valid action vector
  (agent/validate-replicant-action-vector [:select-node {:node-id "test"}])
  (println "✓ Valid action vector passes")

  ;; Test invalid action vector - not a vector
  (try
    (agent/validate-replicant-action-vector "not-a-vector")
    (println "✗ Should have failed non-vector check")
    (catch Exception e
      (println "✓ Non-vector correctly rejected")))

  ;; Test invalid action vector - no command
  (try
    (agent/validate-replicant-action-vector [])
    (println "✗ Should have failed empty vector check")
    (catch Exception e
      (println "✓ Empty vector correctly rejected")))

  ;; Test invalid action vector - non-keyword command
  (try
    (agent/validate-replicant-action-vector ["string-command" {}])
    (println "✗ Should have failed non-keyword command check")
    (catch Exception e
      (println "✓ Non-keyword command correctly rejected"))))

(defn run-tests []
  (println "Running guardrails validation tests...\n")
  (test-environment-detection)
  (println)
  (test-schema-validation)
  (println)
  (test-namespace-alignment)
  (println)
  (test-data-access-patterns)
  (println)
  (test-nil-safety)
  (println)
  (test-event-handler-validation)
  (println "\n✓ All guardrails tests passed!"))

;; Run tests when loaded
(run-tests)