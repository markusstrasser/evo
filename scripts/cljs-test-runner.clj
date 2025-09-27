#!/usr/bin/env clojure

(ns cljs-test-runner
  "ClojureScript test runner using existing REPL bridge - no browser automation needed"
  (:require [dev :as dev]))

(defn run-browser-tests!
  "Run ClojureScript tests in browser context via REPL bridge"
  []
  (println "🧪 Running ClojureScript tests via REPL bridge...")

  ;; Ensure connection
  (dev/init!)

  ;; Load and run browser tests
  (println "📦 Loading test namespaces...")
  (dev/cljs! "(require '[browser-ui :as bt] :reload)")
  (dev/cljs! "(require '[evolver-core-test :as ect] :reload)")
  (dev/cljs! "(require '[data-transform-test :as dtt] :reload)")
  (dev/cljs! "(require '[pure-logic-test :as plt] :reload)")

  ;; Run tests and capture results
  (println "🚀 Running tests...")
  (let [results (dev/cljs! "(cljs.test/run-tests 'browser-ui)")]
    (println "📊 Browser tests completed")
    (println results))

  (println "✅ ClojureScript test run complete"))

(defn get-test-status
  "Get current test status from browser"
  []
  (dev/cljs! "(if (exists? js/window.testResults) 
               (count js/window.testResults)
               \"No results available\")"))

(defn inspect-browser-state
  "Inspect current browser state for debugging"
  []
  (println "🔍 Browser state inspection:")
  (println "Document title:" (dev/cljs! "js/document.title"))
  (println "Window size:" (dev/cljs! "[js/window.innerWidth js/window.innerHeight]"))
  (println "Local storage keys:" (dev/cljs! "(js/Object.keys js/localStorage)"))
  (println "DOM ready:" (dev/cljs! "(.-readyState js/document)")))

(defn simulate-user-interactions
  "Simulate user interactions for testing"
  []
  (println "🎯 Simulating user interactions...")

  ;; Use existing dev.clj utilities
  (dev/simulate-keypress! "ArrowDown")
  (dev/simulate-keypress! "Enter")
  (dev/trigger-command! :toggle-selection {:target-id "test-node"})

  (println "✅ User interactions simulated"))

;; If run as script
(when (= *file* (first *command-line-args*))
  (run-browser-tests!))