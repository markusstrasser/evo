(ns ui.test-runner
  "Browser-based test runner for UI tests"
  (:require [cljs.test :as test]
            [evolver-core-test]
            [data-transform-test]
            [pure-logic-test]
            [browser-ui]))

(defn ^:export run-all-tests []
  "Run all tests and capture results for AI agent access"
  (println "🧪 Starting browser-based test suite...")

  ;; Clear previous results
  (when-let [results-el (.getElementById js/document "test-results")]
    (set! (.-innerHTML results-el) ""))

  ;; Create results container if it doesn't exist
  (when-not (.getElementById js/document "test-results")
    (let [container (.createElement js/document "div")]
      (set! (.-id container) "test-results")
      (set! (.-style container) "white-space: pre-wrap; font-family: monospace; padding: 20px;")
      (.appendChild (.-body js/document) container)))

  ;; Capture test output
  (let [results-el (.getElementById js/document "test-results")
        original-log js/console.log
        test-output (atom [])]

    ;; Override console.log to capture output
    (set! js/console.log
          (fn [& args]
            (let [msg (clojure.string/join " " args)]
              (swap! test-output conj msg)
              (set! (.-innerHTML results-el)
                    (clojure.string/join "\n" @test-output))
              (.apply original-log js/console (to-array args)))))

    ;; Override test reporting to capture results
    (let [original-report test/report]
      (set! test/report
            (fn [m]
              (let [result-str (case (:type m)
                                 :pass (str "✅ PASS: " (or (:message m) ""))
                                 :fail (str "❌ FAIL: " (:message m) "\n   Expected: " (pr-str (:expected m)) "\n   Actual: " (pr-str (:actual m)))
                                 :error (str "💥 ERROR: " (:message m) "\n   " (pr-str (:actual m)))
                                 :begin-test-ns (str "\n📋 Testing " (:ns m))
                                 :end-test-ns (str "✅ Completed " (:ns m))
                                 :summary (str "\n📊 Summary: " (:test m) " tests, " (:pass m) " passed, " (:fail m) " failed, " (:error m) " errors")
                                 (str "ℹ️  " (:type m) ": " (pr-str m)))]
                (when (not= result-str "ℹ️  :begin-test-var: nil")
                  (swap! test-output conj result-str)
                  (set! (.-innerHTML results-el)
                        (clojure.string/join "\n" @test-output))))
              (original-report m)))

      ;; Run the tests
      (println "🚀 Running all test namespaces...")
      (test/run-tests 'evolver-core-test 'data-transform-test 'pure-logic-test 'browser-ui)

      ;; Restore original functions
      (set! js/console.log original-log)
      (set! test/report original-report)

      ;; Store results globally for agent access
      (set! js/window.testResults @test-output)

      (println "🎯 Tests completed! Results available in #test-results and window.testResults"))))

(defn ^:export get-test-results-as-json []
  "Get test results as JSON for easy agent consumption"
  (js/JSON.stringify (clj->js js/window.testResults)))

(defn ^:export get-test-summary []
  "Get a simple test summary"
  (let [results js/window.testResults
        summary-line (last (filter #(clojure.string/includes? % "Summary:") results))]
    (or summary-line "No summary available")))

;; Don't auto-run - let the HTML page control when tests run