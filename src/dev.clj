(ns dev
  "Unified development environment for Evolver - combines REPL, testing, and agent utilities"
  (:require [nrepl.core :as nrepl]
            [shadow.cljs.devtools.api :as shadow]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [malli.core :as m]))

;; =============================================================================
;; ClojureScript REPL Bridge (from successful implementation)
;; =============================================================================

;; Connection state
(def ^:dynamic *shadow-connection* nil)
(def ^:dynamic *shadow-session* nil)
(def ^:dynamic *context* :clojure) ; Track current REPL context

(defn detect-shadow-port
  "Auto-detect shadow-cljs nREPL port from common locations"
  []
  (let [common-ports [55449 9000 7888 7889]]
    (first (filter #(try
                      (with-open [socket (java.net.Socket. "localhost" %)]
                        true)
                      (catch Exception _ false))
                   common-ports))))

(defn connect-to-shadow!
  "Connect to running shadow-cljs nREPL server with auto-detection"
  ([]
   (if-let [port (detect-shadow-port)]
     (connect-to-shadow! port)
     (do (println "❌ No shadow-cljs nREPL found. Run: npm run dev")
         {:status :error :message "No shadow-cljs server detected"})))
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
  "Evaluate code in connected shadow-cljs session with smart result extraction"
  [code]
  (if (and *shadow-connection* *shadow-session*)
    (try
      (let [response (nrepl/message *shadow-connection*
                                    {:op "eval"
                                     :code code
                                     :session *shadow-session*})
            result (first response)]
        (cond
          (:value result)
          (do (println "✅" (:value result))
              (:value result))

          (:err result)
          (do (println "❌ Error:" (:err result))
              {:error (:err result)})

          (:out result)
          (do (println "📤" (:out result))
              (:out result))

          :else
          (do (println "📋 Response:" result)
              result)))
      (catch Exception e
        (println "❌ Evaluation failed:" (.getMessage e))
        {:error (.getMessage e)}))
    (do (println "❌ Not connected to shadow-cljs. Run (connect!) first")
        {:error "No connection"})))

(defn ensure-cljs-context!
  "Ensure we're in ClojureScript context, switching if needed"
  []
  (when (not= *context* :clojurescript)
    (println "🔄 Switching to ClojureScript context...")
    (shadow-eval "(shadow.cljs.devtools.api/repl :frontend)")
    (alter-var-root #'*context* (constantly :clojurescript))
    (println "✅ Now in ClojureScript context")))

(defn ensure-clj-context!
  "Ensure we're in Clojure context, switching if needed"
  []
  (when (not= *context* :clojure)
    (println "🔄 Switching to Clojure context...")
    (shadow-eval ":cljs/quit")
    (alter-var-root #'*context* (constantly :clojure))
    (println "✅ Now in Clojure context")))

(defn smart-eval!
  "Intelligently evaluate code in correct context (ClojureScript vs Clojure)"
  [code]
  (let [requires-browser? (or (str/includes? code "js/")
                              (str/includes? code "js.")
                              (str/includes? code "document")
                              (str/includes? code "window")
                              (str/includes? code "@@evolver.core/store"))]
    (if requires-browser?
      (do (ensure-cljs-context!)
          (shadow-eval code))
      (shadow-eval code))))

(defn cljs!
  "Quick ClojureScript evaluation with auto-connection and context"
  [code]
  (when-not *shadow-connection*
    (connect-to-shadow!))
  (ensure-cljs-context!)
  (shadow-eval code))

(defn clj!
  "Quick Clojure evaluation"
  [code]
  (when-not *shadow-connection*
    (connect-to-shadow!))
  (ensure-clj-context!)
  (shadow-eval code))

;; =============================================================================
;; Environment Health & Validation
;; =============================================================================

(defn check-browser-connection
  "Verify browser is connected to shadow-cljs"
  []
  (try
    (let [result (cljs! "js/document.title")]
      (if (and result (not (:error result)))
        {:status :connected :title result}
        {:status :disconnected :error "No browser response"}))
    (catch Exception e
      {:status :error :message (.getMessage e)})))

(defn check-app-state
  "Verify evolver app is loaded and accessible"
  []
  (try
    (let [result (cljs! "(keys @@evolver.core/store)")]
      (if (and result (not (:error result)))
        {:status :loaded :store-keys result}
        {:status :not-loaded :error "Store not accessible"}))
    (catch Exception e
      {:status :error :message (.getMessage e)})))

(defn preflight-check!
  "Complete environment health check"
  []
  (println "🔍 Running preflight checks...")

  ;; Check shadow-cljs process
  (let [shadow-port (detect-shadow-port)]
    (if shadow-port
      (println "✅ Shadow-cljs detected on port" shadow-port)
      (println "❌ Shadow-cljs not running - run: npm run dev")))

  ;; Check connection
  (let [conn-result (connect-to-shadow!)]
    (if (= :connected (:status conn-result))
      (println "✅ nREPL connection established")
      (println "❌ nREPL connection failed")))

  ;; Check browser
  (let [browser-result (check-browser-connection)]
    (if (= :connected (:status browser-result))
      (println "✅ Browser connected:" (:title browser-result))
      (println "❌ Browser not connected - open: http://localhost:8080")))

  ;; Check app
  (let [app-result (check-app-state)]
    (if (= :loaded (:status app-result))
      (println "✅ Evolver app loaded")
      (println "❌ Evolver app not accessible")))

  (println "\n🎯 Preflight complete. Run (init!) to initialize development environment."))

;; =============================================================================
;; App Integration & Testing Utilities
;; =============================================================================

(defn inspect-store
  "Inspect evolver app store with optional filtering"
  [& {:keys [path]}]
  (if path
    (cljs! (str "(get-in @@evolver.core/store " (pr-str path) ")"))
    (cljs! "@@evolver.core/store")))

(defn trigger-command!
  "Trigger a command in the evolver app"
  [cmd params]
  (cljs! (str "(evolver.dispatcher/dispatch! "
              (pr-str [cmd params]) ")")))

(defn set-test-state!
  "Set up specific app state for testing"
  [state-map]
  (cljs! (str "(reset! evolver.core/store " (pr-str state-map) ")")))

(defn simulate-keypress!
  "Simulate keyboard input for testing"
  [key-code & {:keys [shift ctrl alt] :or {shift false ctrl false alt false}}]
  (cljs! (str "(let [event (js/KeyboardEvent. \"keydown\" 
                  #js {:key \"" key-code "\"
                       :shiftKey " shift "
                       :ctrlKey " ctrl "
                       :altKey " alt "})]
                (.dispatchEvent js/document event))")))

(defn get-dom-element
  "Get DOM element for inspection"
  [selector]
  (cljs! (str "js/document.querySelector(\"" selector "\")")))

(defn count-nodes
  "Count rendered nodes in the UI"
  []
  (cljs! "js/document.querySelectorAll('.node').length"))

;; =============================================================================
;; Development Shortcuts
;; =============================================================================

(defn reload-app!
  "Reload the evolver application"
  []
  (cljs! "(evolver.core/main)"))

(defn clear-console!
  "Clear browser console"
  []
  (cljs! "js/console.clear()"))

(defn dom-snapshot!
  "Take a snapshot of current DOM state"
  []
  (cljs! "js/document.body.innerHTML"))

(defn performance-now!
  "Get current performance timestamp"
  []
  (cljs! "js/performance.now()"))

;; =============================================================================
;; Testing Framework Integration
;; =============================================================================

(defn with-fresh-app-state
  "Execute function with clean app state, restore after"
  [test-fn]
  (let [original-state (inspect-store)]
    (try
      (set-test-state! {:nodes {} :view {:selection [] :cursor nil}})
      (test-fn)
      (finally
        (cljs! (str "(reset! evolver.core/store " (pr-str original-state) ")"))))))

(defn assert-dom-count
  "Assert specific count of DOM elements"
  [selector expected-count]
  (let [actual (cljs! (str "js/document.querySelectorAll(\"" selector "\").length"))]
    (if (= (str expected-count) actual)
      (println "✅ DOM count assertion passed:" selector "=" expected-count)
      (println "❌ DOM count assertion failed:" selector "expected" expected-count "got" actual))))

;; =============================================================================
;; Error-Safe Execution
;; =============================================================================

(defn safe-eval
  "Evaluate with automatic error recovery"
  [code & {:keys [context] :or {context :auto}}]
  (try
    (case context
      :cljs (cljs! code)
      :clj (clj! code)
      :auto (smart-eval! code))
    (catch Exception e
      (println "🔧 Recovering from error, checking connection...")
      (preflight-check!)
      {:error (.getMessage e) :recovery "Preflight check completed"})))

;; =============================================================================
;; Unified Initialization
;; =============================================================================

(defn init!
  "One-command initialization of complete development environment"
  []
  (println "🚀 Initializing unified development environment...")

  (preflight-check!)

  (when *shadow-connection*
    (ensure-cljs-context!)
    (clear-console!)
    (cljs! "(js/console.log \"🎯 Development environment ready!\")")
    (cljs! "(js/console.log \"Store keys:\" (clj->js (keys @@evolver.core/store)))")

    (println "\n🎉 Environment ready! Available commands:")
    (println "  (cljs! \"code\")        - Evaluate ClojureScript")
    (println "  (clj! \"code\")         - Evaluate Clojure")
    (println "  (smart-eval! \"code\")  - Auto-detect context")
    (println "  (inspect-store)       - View app state")
    (println "  (trigger-command! :cmd {}) - Execute app command")
    (println "  (reload-app!)         - Reload application")
    (println "  (preflight-check!)    - Re-run health checks")
    (println "  (help-dev!)           - Show all available functions")
    true))

(defn disconnect!
  "Clean disconnection from shadow-cljs"
  []
  (when *shadow-connection*
    (.close *shadow-connection*)
    (alter-var-root #'*shadow-connection* (constantly nil))
    (alter-var-root #'*shadow-session* (constantly nil))
    (alter-var-root #'*context* (constantly :clojure))
    (println "✅ Disconnected from shadow-cljs")))

(defn status
  "Show current development environment status"
  []
  {:connected (boolean *shadow-connection*)
   :context *context*
   :shadow-port (detect-shadow-port)
   :browser (if *shadow-connection* (check-browser-connection) {:status :not-connected})})

(defn help-dev!
  "Show complete development environment help"
  []
  (println "
🔧 Unified Development Environment

== INITIALIZATION ==
  (init!)                   - Complete environment setup
  (preflight-check!)        - Health check all components
  (disconnect!)             - Clean shutdown
  (status)                  - Current environment status

== EVALUATION ==  
  (cljs! \"code\")            - ClojureScript in browser
  (clj! \"code\")             - Clojure evaluation
  (smart-eval! \"code\")      - Auto-detect context
  (safe-eval \"code\")        - With error recovery

== APP INTEGRATION ==
  (inspect-store)           - View app state
  (inspect-store :path [:view]) - Specific state path
  (trigger-command! :cmd {}) - Execute app command
  (set-test-state! {})      - Set specific state
  (reload-app!)             - Reload application

== TESTING UTILITIES ==
  (simulate-keypress! \"ArrowDown\") - Keyboard input
  (get-dom-element \".node\")  - DOM element access
  (count-nodes)             - Count rendered nodes
  (assert-dom-count \".node\" 5) - DOM assertions
  (with-fresh-app-state fn) - Isolated test execution

== DEVELOPMENT TOOLS ==
  (clear-console!)          - Clear browser console  
  (dom-snapshot!)           - Current DOM state
  (performance-now!)        - Performance timestamp

Prerequisites:
  1. npm run dev            - Start shadow-cljs
  2. http://localhost:8080  - Open browser
  3. (init!)                - Initialize environment
"))

;; Auto-initialize message
(println "
🔧 Unified Development Environment Loaded
Run (help-dev!) for complete documentation
Quick start: (init!)
")

(comment
  ;; Usage examples
  (init!)
  (cljs! "(js/console.log \"Hello browser!\")")
  (inspect-store)
  (trigger-command! :select-node {:id "node-1"})
  (simulate-keypress! "ArrowDown")
  (count-nodes)
  (preflight-check!)
  (status))