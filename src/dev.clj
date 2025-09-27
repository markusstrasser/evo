(ns dev
  "Development utilities for ClojureScript REPL integration"
  (:require [nrepl.core :as nrepl]
            [shadow.cljs.devtools.api :as shadow]
            [clojure.pprint :refer [pprint]]))

;; Connection state
(def ^:dynamic *shadow-connection* nil)
(def ^:dynamic *shadow-session* nil)

(defn connect-to-shadow!
  "Connect to running shadow-cljs nREPL server"
  ([] (connect-to-shadow! 55449))
  ([port]
   (try
     (let [conn (nrepl/connect :port port)
           client (nrepl/client conn 1000)
           session (nrepl/new-session client)]
       (alter-var-root #'*shadow-connection* (constantly client))
       (alter-var-root #'*shadow-session* (constantly session))
       (println "✅ Connected to shadow-cljs nREPL at port" port)
       {:status :connected :port port})
     (catch Exception e
       (println "❌ Failed to connect to shadow-cljs nREPL:" (.getMessage e))
       {:status :error :message (.getMessage e)}))))

(defn shadow-eval
  "Evaluate ClojureScript code in connected shadow-cljs session"
  [code]
  (if (and *shadow-connection* *shadow-session*)
    (try
      (let [response (nrepl/message *shadow-connection*
                                    {:op "eval"
                                     :code code
                                     :session *shadow-session*})]
        (if-let [value (:value response)]
          (do (println "Result:" value)
              value)
          (if-let [error (:err response)]
            (do (println "Error:" error)
                {:error error})
            response)))
      (catch Exception e
        (println "❌ Evaluation failed:" (.getMessage e))
        {:error (.getMessage e)}))
    (do (println "❌ Not connected to shadow-cljs. Run (connect-to-shadow!) first")
        {:error "No connection"})))

(defn cljs!
  "Quick ClojureScript evaluation with automatic connection"
  [code]
  (when-not *shadow-connection*
    (connect-to-shadow!))
  (shadow-eval code))

(defn switch-to-frontend!
  "Switch shadow-cljs to frontend build for browser REPL"
  []
  (cljs! "(shadow.cljs.devtools.api/repl :frontend)"))

(defn inspect-store
  "Inspect the evolver app store state"
  []
  (cljs! "@evolver.core/store"))

(defn trigger-command!
  "Trigger a command in the evolver app"
  [cmd params]
  (cljs! (str "(evolver.dispatcher/dispatch! "
              (pr-str [cmd params]) ")")))

(defn disconnect-shadow!
  "Disconnect from shadow-cljs nREPL"
  []
  (when *shadow-connection*
    (.close *shadow-connection*)
    (alter-var-root #'*shadow-connection* (constantly nil))
    (alter-var-root #'*shadow-session* (constantly nil))
    (println "✅ Disconnected from shadow-cljs")))

(defn status
  "Show current connection status"
  []
  (if *shadow-connection*
    (println "✅ Connected to shadow-cljs nREPL")
    (println "❌ Not connected to shadow-cljs nREPL"))
  {:connected (boolean *shadow-connection*)})

;; Legacy functions for backward compatibility
(defn watch! []
  (shadow/watch :frontend)
  (println "Watching :frontend build"))

(defn cljs-repl []
  (println "Connecting to browser REPL... (type :cljs/quit to return)")
  (shadow/repl :frontend))

(defn dom-test! []
  (cljs! "js/document.title"))

(defn reload! []
  (cljs! "(evolver.core/main)"))

;; Convenience functions for development
(defn help
  "Show available development functions"
  []
  (println "
🔧 Shadow-cljs Bridge Functions:

Connection:
  (connect-to-shadow!)     - Connect to shadow-cljs nREPL
  (disconnect-shadow!)     - Disconnect from shadow-cljs
  (status)                 - Show connection status

Evaluation:
  (cljs! \"code\")           - Evaluate ClojureScript code
  (shadow-eval \"code\")     - Low-level evaluation
  (switch-to-frontend!)    - Switch to browser REPL

App Integration:
  (inspect-store)          - View evolver app state
  (trigger-command! :cmd {}) - Trigger app commands

Legacy Functions:
  (watch!)                 - Start auto-compilation  
  (cljs-repl)              - Direct browser REPL
  (dom-test!)              - Test DOM access
  (reload!)                - Reload app

Usage:
  1. Start shadow-cljs: npm run dev
  2. Connect: (connect-to-shadow!)
  3. Switch to browser: (switch-to-frontend!)
  4. Evaluate: (cljs! \"(js/console.log 'Hello!')\")
"))

(println "
🚀 DEV ENVIRONMENT READY for Evolver
Run (help) for complete function list
Quick start: (connect-to-shadow!) then (cljs! \"(js/console.log 'Hello!')\")
")

(comment
  ;; Usage examples
  (help)
  (connect-to-shadow!)
  (switch-to-frontend!)
  (cljs! "(js/console.log \"Hello from MCP!\")")
  (inspect-store)
  (trigger-command! :select-node {:id "node-1"})
  (status)
  (disconnect-shadow!))