(ns spec.scenario-test
  "Test integration for spec scenarios.
   
   Generates clojure.test tests from executable scenarios in specs.edn.
   Preserves FR citations in metadata for coverage tracking.
   
   Usage:
     bb test:specs           ; Run all scenario tests
     bb test -n spec.scenario-test  ; Run via kaocha"
  (:require [clojure.test :refer [deftest testing is]]
            [spec.runner :as runner]
            [spec.registry :as fr]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Generation
;; ══════════════════════════════════════════════════════════════════════════════

(defn passes?
  "Check if a scenario passes. For use in test assertions.
   
   Example:
     (is (passes? :fr.edit/backspace-merge :MERGE-01))"
  [fr-id scenario-id]
  (let [result (runner/run-scenario fr-id scenario-id)]
    (when-not (:pass? result)
      (println "\nScenario failed:" fr-id scenario-id)
      (println "  Expected:" (:expected result))
      (println "  Actual:" (:actual result))
      (when (:diff result)
        (println "  Diff:" (:diff result))))
    (:pass? result)))

(defn scenario-test-name
  "Generate test name from FR and scenario IDs."
  [fr-id scenario-id]
  (symbol (str (name fr-id) "--" (name scenario-id))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dynamic Test Registration
;; ══════════════════════════════════════════════════════════════════════════════

(defmacro defscenario-test
  "Define a test for a specific scenario.
   Preserves FR citation in metadata for coverage tracking."
  [fr-id scenario-id]
  (let [test-name (scenario-test-name fr-id scenario-id)]
    `(deftest ~(with-meta test-name {:fr/ids #{fr-id}})
       (is (passes? ~fr-id ~scenario-id)
           (str "Scenario " ~scenario-id " should pass")))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Aggregate Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest all-executable-scenarios
  (testing "All executable scenarios from specs.edn"
    (let [scenarios (fr/all-executable-scenarios)]
      (if (empty? scenarios)
        (println "No executable scenarios found in specs.edn")
        (doseq [[fr-id scenario-id _scenario] scenarios]
          (testing (str fr-id "/" scenario-id)
            (is (passes? fr-id scenario-id))))))))

(deftest scenario-count-sanity
  (testing "Scenario registry is accessible"
    (let [count (fr/scenario-count)]
      (is (number? count) "Should return a number")
      (is (>= count 0) "Should have non-negative count"))))

;; ══════════════════════════════════════════════════════════════════════════════
;; REPL Helpers
;; ══════════════════════════════════════════════════════════════════════════════

(defn run-and-report
  "Run all scenarios and print report. For REPL use."
  []
  (runner/print-results (runner/run-all-scenarios)))

(defn run-fr
  "Run all scenarios for a single FR. For REPL use."
  [fr-id]
  (let [results (runner/run-fr-scenarios fr-id)]
    (doseq [r results]
      (println (runner/format-result r)))
    results))

(comment
  ;; REPL usage
  (run-and-report)
  (run-fr :fr.edit/backspace-merge)

  ;; Check a specific scenario
  (passes? :fr.edit/backspace-merge :MERGE-01)

  ;; See all executable scenarios
  (fr/all-executable-scenarios)

  ;; Scenario stats
  (fr/scenario-count))
