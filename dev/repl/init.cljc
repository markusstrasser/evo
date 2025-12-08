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
       ;; Load core namespaces
       (require '[kernel.db :as db])
       (require '[kernel.ops :as ops])
       (require '[kernel.transaction :as tx])
       (require '[kernel.dbg :as dbg])
       (require '[kernel.api :as api])
       (require '[fixtures :as fix])

       ;; Install clojure-plus enhancements
       (require 'clojure+.hashp)
       (require 'clojure+.print)
       (require 'clojure+.error)
       (require 'clojure+.test)

       ((resolve 'clojure+.hashp/install!))
       ((resolve 'clojure+.print/install!))
       ((resolve 'clojure+.error/install!) {:reverse? true :color? true})
       ((resolve 'clojure+.test/install!))

       ;; Enable journaling by default in dev
       ((resolve 'kernel.api/set-journal!) true)

       (println "✅ Loaded: core.{db,ops,transaction,dbg,api}, fixtures")
       (println "✅ Installed: clojure+ (hashp, print, error, test)")
       (println "✅ Journaling enabled: .architect/ops.ednlog"))
     :cljs
     (do
       (require '[kernel.db :as db])
       (require '[kernel.ops :as ops])
       (require '[kernel.transaction :as tx])
       (require '[kernel.dbg :as dbg])
       (require '[kernel.api :as api])
       (println "✅ Loaded: core.{db,ops,transaction,dbg,api}"))))

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

(defn quick-health-check!
  "Quick REPL health check"
  []
  (println "\n🏥 REPL Health Check:")
  (println "  JVM:" (System/getProperty "java.version"))
  (println "  Clojure:" (clojure-version))
  (println "  Namespaces loaded:" (count (all-ns)))
  (println "  Current ns:" *ns*)
  (try
    (require 'kernel.db :reload)
    (let [db-ns (find-ns 'kernel.db)
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

;; Pretty-traced dispatch for dev/learning

(defn dispatch!
  "Dispatch intent with pretty-printed trace and DB summary for learning/debugging.

   Usage:
     (dispatch! db {:type :select :ids \"a\"})

   Shows:
   - Transaction trace (operations applied)
   - DB summary (node count, tree structure)
   - Returns: new db state"
  [db intent]
  #?(:clj
     (do
       (require '[kernel.api :as api])
       (require '[kernel.dbg :as dbg])
       (let [{:keys [db trace]} ((resolve 'kernel.api/dispatch) db intent)]
         (println "\n━━━ Transaction Trace ━━━")
         ((resolve 'kernel.dbg/pp-trace) trace)
         (println "\n━━━ DB Summary ━━━")
         ((resolve 'kernel.dbg/pp-db-summary) db)
         db))
     :cljs
     (do
       (require '[kernel.api :as api])
       (require '[kernel.dbg :as dbg])
       (let [{:keys [db trace]} (api/dispatch db intent)]
         (println "\n━━━ Transaction Trace ━━━")
         (dbg/pp-trace trace)
         (println "\n━━━ DB Summary ━━━")
         (dbg/pp-db-summary db)
         db))))

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

;; Component testing helpers

(defn test-component!
  "Test a component in the browser with sample props.
   Returns hiccup structure via console.log.

   Usage: (test-component! 'components.block/Block
                           {:db sample-db :block-id \"a\" :depth 0 :on-intent identity})"
  [component-sym props]
  #?(:clj
     (do
       (require '[shadow.cljs.devtools.api :as shadow])
       ((resolve 'shadow.cljs.devtools.api/repl-eval)
        :frontend
        `(let [component# (requiring-resolve '~component-sym)
               result# (component# ~props)]
           (js/console.log "Component output:" (pr-str result#))
           result#)))
     :cljs
     (println "⚠️ test-component! is a JVM-only function.")))

(defn inspect-db!
  "Inspect current DB state in browser.

   Usage: (inspect-db!)
          (inspect-db! '[:nodes])  ; Specific path"
  ([]
   #?(:clj (cljs! '(do (require '[shell.editor :as app])
                       (js/console.log "DB:" (pr-str @app/!db))
                       @app/!db))
      :cljs nil))
  ([path]
   #?(:clj (cljs! `(do (require '[shell.editor :as app])
                       (let [val# (get-in @app/!db ~path)]
                         (js/console.log ~(str "DB " path ":") (pr-str val#))
                         val#)))
      :cljs nil)))

(defn send-intent!
  "Send an intent from the REPL to test intent handling.

   Usage: (send-intent! {:type :select :ids \"a\"})"
  [intent]
  #?(:clj (cljs! `(do (require '[shell.editor :as app])
                      (app/handle-intent! ~intent)))
      :cljs nil))

(defn sample-db!
  "Get or create a sample DB fixture in the browser.

   Usage: (sample-db!)  ; Returns current db
          (sample-db! :reset)  ; Reset to empty
          (sample-db! :fixture)  ; Load test fixture"
  ([]
   #?(:clj (inspect-db!) :cljs nil))
  ([action]
   #?(:clj
      (case action
        :reset (cljs! '(do (require '[shell.editor :as app]
                                    '[kernel.db :as db])
                           (reset! app/!db (db/empty-db))
                           @app/!db))
        :fixture (cljs! '(do (require '[shell.editor :as app]
                                      '[kernel.transaction :as tx]
                                      '[kernel.db :as db])
                             (reset! app/!db
                                     (-> (db/empty-db)
                                         (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "First"}}
                                                       {:op :create-node :id "b" :type :block :props {:text "Second"}}
                                                       {:op :create-node :id "c" :type :block :props {:text "Third"}}
                                                       {:op :place :id "a" :under :doc :at :last}
                                                       {:op :place :id "b" :under :doc :at :last}
                                                       {:op :place :id "c" :under :doc :at :last}])
                                         :db))
                             @app/!db)))
      :cljs nil)))

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
  (rt! 'kernel.permutation-test)      ; Run specific test
  (rq! 'kernel.permutation-test)      ; Reload and run
  (rt!)                                ; Run all tests

  ;; Component testing:
  (sample-db! :fixture)                                 ; Load test data
  (inspect-db! [:nodes])                                ; Inspect nodes
  (send-intent! {:type :select :ids "a"})               ; Test intent
  (test-component! 'components.block/Block
                   {:db (sample-db!)
                    :block-id "a"
                    :depth 0
                    :on-intent identity})                ; Test component

  ;; Session persistence:
  (save-session!)
  (restore-session!)
  (quick-health-check!)
  )