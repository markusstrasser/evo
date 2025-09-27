#!/usr/bin/env clojure

(ns run-ui-tests
  "UI test runner script using unified development environment"
  (:require [dev-unified :as dev]))

(defn run-ui-test-suite []
  (println "🚀 Starting UI Test Suite...")

  ;; Initialize development environment
  (println "\n📋 Step 1: Initialize Environment")
  (dev/init!)

  ;; Wait for environment to be ready
  (Thread/sleep 1000)

  ;; Test 1: Environment Health Check
  (println "\n🧪 Test 1: Environment Health Check")
  (let [status (dev/status)]
    (if (:connected status)
      (println "✅ Environment health check PASSED")
      (do (println "❌ Environment health check FAILED")
          (System/exit 1))))

  ;; Test 2: Browser Connectivity
  (println "\n🧪 Test 2: Browser Connectivity")
  (try
    (let [title (dev/cljs! "js/document.title")]
      (if (and title (not (:error title)))
        (println "✅ Browser connectivity PASSED - Title:" title)
        (do (println "❌ Browser connectivity FAILED")
            (System/exit 1))))
    (catch Exception e
      (println "❌ Browser connectivity FAILED:" (.getMessage e))
      (System/exit 1)))

  ;; Test 3: App State Access
  (println "\n🧪 Test 3: App State Access")
  (try
    (let [store-keys (dev/cljs! "(keys @@evolver.core/store)")]
      (if (and store-keys (not (:error store-keys)))
        (println "✅ App state access PASSED - Keys:" store-keys)
        (do (println "❌ App state access FAILED")
            (System/exit 1))))
    (catch Exception e
      (println "❌ App state access FAILED:" (.getMessage e))
      (System/exit 1)))

  ;; Test 4: DOM Node Count
  (println "\n🧪 Test 4: DOM Node Count")
  (try
    (let [node-count (dev/count-nodes)]
      (if (and node-count (> (Integer/parseInt node-count) 0))
        (println "✅ DOM node count PASSED - Count:" node-count)
        (do (println "❌ DOM node count FAILED")
            (System/exit 1))))
    (catch Exception e
      (println "❌ DOM node count FAILED:" (.getMessage e))
      (System/exit 1)))

  ;; Test 5: Console Clear (side effect test)
  (println "\n🧪 Test 5: Console Operations")
  (try
    (dev/clear-console!)
    (dev/cljs! "(js/console.log \"🧪 Test message from UI test script\")")
    (println "✅ Console operations PASSED")
    (catch Exception e
      (println "❌ Console operations FAILED:" (.getMessage e))
      (System/exit 1)))

  ;; Test 6: Performance Measurement
  (println "\n🧪 Test 6: Performance Measurement")
  (try
    (let [perf-time (dev/performance-now!)]
      (if (and perf-time (not (:error perf-time)))
        (println "✅ Performance measurement PASSED - Time:" perf-time)
        (do (println "❌ Performance measurement FAILED")
            (System/exit 1))))
    (catch Exception e
      (println "❌ Performance measurement FAILED:" (.getMessage e))
      (System/exit 1)))

  (println "\n🎉 All UI tests PASSED!")
  (println "✅ Unified development environment is fully functional")
  (System/exit 0))

;; Run tests if script is executed directly
(when (= *file* (first *command-line-args*))
  (run-ui-test-suite))