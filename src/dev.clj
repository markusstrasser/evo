(ns dev
  (:require
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as server]))

(defn watch! []
  (shadow/watch :frontend)
  (println "Watching :frontend build"))

(defn cljs-repl []
  (println "Connecting to browser REPL... (type :cljs/quit to return)")
  (shadow/repl :frontend))

(defn cljs! [form-as-string]
  (shadow/cljs-eval :frontend form-as-string {}))

(defn inspect-store []
  (shadow/cljs-eval :frontend "@evolver.core/store" {}))

(defn trigger-command! [cmd-name params]
  (shadow/cljs-eval :frontend
                    (str "(evolver.dispatcher/dispatch-intent! @evolver.core/store :"
                         (name cmd-name)
                         " "
                         (pr-str params)
                         ")")
                    {}))

(defn dom-test! []
  (shadow/cljs-eval :frontend "js/document.title" {}))

(defn reload! []
  (shadow/cljs-eval :frontend "(evolver.core/main)" {}))

(println "
🚀 DEV ENVIRONMENT READY for Evolver
- (watch!)                    Start auto-compilation  
- (cljs-repl)                 Connect to browser REPL
- (dom-test!)                 Test DOM access
- (inspect-store)             Examine app store
- (trigger-command! :cmd {})  Trigger app command
- (reload!)                   Reload app
")