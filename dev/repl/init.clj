(ns repl
  "REPL connection and evaluation helpers for shadow-cljs development."
  (:require [clojure.test :as t]))

;; NOTE: This namespace is intentionally minimal. It only provides
;; connection helpers for any ClojureScript project, with no assumptions
;; about application structure.

(defn connect!
  "Connect to shadow-cljs REPL for the :frontend build.
   Usage: (connect!)"
  []
  #?(:clj
     (do
       (require '[shadow.cljs.devtools.api :as shadow])
       ((resolve 'shadow.cljs.devtools.api/repl) :frontend)
       (println "✅ Connected to shadow-cljs :frontend REPL"))
     :cljs
     (println "⚠️ connect! is a JVM-only function. Use from Clojure REPL.")))

(defn cljs!
  "Evaluate ClojureScript code in the browser context.
   Usage: (cljs! '(js/alert \"Hello\"))"
  [code]
  #?(:clj
     (do
       (require '[shadow.cljs.devtools.api :as shadow])
       ((resolve 'shadow.cljs.devtools.api/repl-eval) :frontend code))
     :cljs
     (eval code)))

(defn clj!
  "Evaluate Clojure code in the JVM context.
   Usage: (clj! '(println \"Hello\"))"
  [code]
  #?(:clj (eval code)
     :cljs (println "⚠️ clj! only works in Clojure/JVM context")))

(defn init!
  "Initialize REPL environment with common requires.
   Usage: (init!)"
  []
  #?(:clj
     (do
       (require '[core.db :as db])
       (require '[core.ops :as ops])
       (require '[core.interpret :as interpret])
       (require '[fixtures :as fix])
       (println "✅ Loaded: core.{db,ops,interpret}, fixtures"))
     :cljs
     (do
       (require '[core.db :as db])
       (require '[core.ops :as ops])
       (require '[core.interpret :as interpret])
       (println "✅ Loaded: core.{db,ops,interpret}"))))

(defn rt!
  "Run tests in specified namespaces. If none provided, runs all tests.
   Usage: (rt! 'kernel.permutation-test)
          (rt! 'kernel.permutation-test 'struct.reorder-test)"
  [& test-nses]
  #?(:clj
     (if (seq test-nses)
       (apply t/run-tests test-nses)
       (t/run-all-tests #".*-test$"))
     :cljs
     (println "⚠️ rt! is a JVM-only function.")))

(defn rq!
  "Quick test - run a single test namespace after requiring it.
   Usage: (rq! 'kernel.permutation-test)"
  [test-ns]
  #?(:clj
     (do
       (require test-ns :reload)
       (t/run-tests test-ns))
     :cljs
     (println "⚠️ rq! is a JVM-only function.")))

(comment
  ;; Quick start workflow:
  (connect!)  ; Connect to shadow-cljs
  (init!)     ; Load kernel namespaces

  ;; Evaluate CLJS in browser:
  (cljs! '(js/console.log "Hello from browser"))

  ;; Evaluate CLJ on JVM:
  (clj! '(println "Hello from JVM"))

  ;; Run tests:
  (rt! 'kernel.permutation-test)      ; Run specific test
  (rq! 'kernel.permutation-test)      ; Reload and run
  (rt!)                                ; Run all tests
  )