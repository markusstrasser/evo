(ns repl
  "REPL connection and evaluation helpers for shadow-cljs development.")

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

(comment
  ;; Quick start workflow:
  (connect!)  ; Connect to shadow-cljs
  (init!)     ; Load kernel namespaces

  ;; Evaluate CLJS in browser:
  (cljs! '(js/console.log "Hello from browser"))

  ;; Evaluate CLJ on JVM:
  (clj! '(println "Hello from JVM"))
  )