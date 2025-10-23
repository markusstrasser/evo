(ns repl
  "REPL connection and evaluation helpers for shadow-cljs development."
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

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
   Usage: (rt! 'algebra.permutation-test)
          (rt! 'algebra.permutation-test 'struct.reorder-test)"
  [& test-nses]
  #?(:clj
     (if (seq test-nses)
       (apply t/run-tests test-nses)
       (t/run-all-tests #".*-test$"))
     :cljs
     (println "⚠️ rt! is a JVM-only function.")))

(defn rq!
  "Quick test - run a single test namespace after requiring it.
   Usage: (rq! 'algebra.permutation-test)"
  [test-ns]
  #?(:clj
     (do
       (require test-ns :reload)
       (t/run-tests test-ns))
     :cljs
     (println "⚠️ rq! is a JVM-only function.")))

(defn quick-health-check!
  "Quick REPL health check"
  []
  (println "\n🏥 REPL Health Check:")
  (println "  JVM:" (System/getProperty "java.version"))
  (println "  Clojure:" (clojure-version))
  (println "  Namespaces loaded:" (count (all-ns)))
  (println "  Current ns:" *ns*)
  (try
    (require 'core.db :reload)
    (let [db-ns (find-ns 'core.db)
          validate-fn (ns-resolve db-ns 'validate)
          empty-db-fn (ns-resolve db-ns 'empty-db)]
      (when (and validate-fn empty-db-fn)
        (let [result (validate-fn (empty-db-fn))]
          (assert (:ok? result)))))
    (println "  Core modules: ✓")
    (catch Exception e
      (println "  Core modules: ❌" (.getMessage e)))))

(defn go!
  "One-command REPL startup: connect, load namespaces, health check.
   Usage: (go!)"
  []
  #?(:clj
     (do
       (connect!)
       (init!)
       (quick-health-check!)
       (println "\n✅ REPL ready - use (rt!) to run tests"))
     :cljs
     (println "⚠️ go! is a JVM-only function. Use from Clojure REPL.")))

;; Session persistence (optional, less commonly used)

(defn save-session!
  "Save current REPL session state to dev/.repl-session.edn"
  []
  (let [session-data {:loaded-namespaces (map str (all-ns))
                      :current-ns (str *ns*)
                      :timestamp (java.util.Date.)
                      :jvm-props (into {} (System/getProperties))}]
    (io/make-parents "dev/.repl-session.edn")
    (spit "dev/.repl-session.edn" (pr-str session-data))
    (println "💾 Session saved to dev/.repl-session.edn")))

(defn restore-session!
  "Restore REPL session from saved state"
  []
  (when (.exists (io/file "dev/.repl-session.edn"))
    (try
      (let [{:keys [loaded-namespaces current-ns]} (edn/read-string (slurp "dev/.repl-session.edn"))]
        (println "🔄 Restoring session...")
        (doseq [ns-name loaded-namespaces]
          (try
            (require (symbol ns-name) :reload)
            (print ".")
            (catch Exception e
              (printf "⚠️  %s failed to reload: %s\n" ns-name (.getMessage e)))))
        (when current-ns
          (in-ns (symbol current-ns)))
        (println "\n✅ Session restored"))
      (catch Exception e
        (printf "❌ Failed to restore session: %s\n" (.getMessage e))))))

(comment
  ;; ⭐ Quick start (recommended):
  (go!)  ; Connect, load, health check

  ;; Or manual setup:
  (connect!)  ; Connect to shadow-cljs
  (init!)     ; Load kernel namespaces

  ;; Evaluate CLJS in browser:
  (cljs! '(js/console.log "Hello from browser"))

  ;; Evaluate CLJ on JVM:
  (clj! '(println "Hello from JVM"))

  ;; Run tests:
  (rt! 'algebra.permutation-test)      ; Run specific test
  (rq! 'algebra.permutation-test)      ; Reload and run
  (rt!)                                ; Run all tests

  ;; Session persistence:
  (save-session!)
  (restore-session!)
  (quick-health-check!)
  )