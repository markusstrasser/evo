(ns dev-mcp
  "MCP-specific utilities for seamless ClojureScript REPL integration"
  (:require [dev :as dev]
            [clojure.pprint :refer [pprint]]))

(defn init!
  "Initialize MCP → Shadow-cljs bridge"
  []
  (println "🔗 Initializing MCP → Shadow-cljs bridge...")
  (let [result (dev/connect-to-shadow!)]
    (if (= :connected (:status result))
      (do
        (dev/switch-to-frontend!)
        (dev/cljs! "(js/console.log \"✅ MCP ClojureScript REPL Ready!\")")
        (println "🎉 SUCCESS: MCP can now evaluate ClojureScript!")
        (println "Try: (cljs! \"(js/console.log 'Hello from MCP!')\")")
        true)
      (do
        (println "❌ Failed to connect. Make sure shadow-cljs is running:")
        (println "   npm run dev")
        false))))

(defn cljs!
  "Convenient ClojureScript evaluation from MCP"
  [code]
  (dev/cljs! code))

(defn help!
  "Show MCP-specific ClojureScript integration help"
  []
  (println "
🚀 MCP → ClojureScript Integration

Setup:
  (init!)                     - Connect to shadow-cljs & switch to browser REPL

Evaluation:
  (cljs! \"code\")              - Evaluate ClojureScript
  (cljs! \"(+ 1 2 3)\")          - Basic computation → 6
  (cljs! \"js/document.title\")  - DOM access → \"Evolver\"
  (cljs! \"(js/console.log 'Hi!)\") - Console output

App Integration:
  (cljs! \"(keys @@evolver.core/store)\") - View app state keys
  (cljs! \"(:view @@evolver.core/store)\") - Get current view state

Status:
  (dev/status)                - Check connection status
  (dev/disconnect-shadow!)    - Disconnect from shadow-cljs

Requirements:
  1. shadow-cljs running: npm run dev
  2. Browser open at: http://localhost:8080
  3. MCP initialized: (init!)
"))

(comment
  ;; Quick start sequence
  (help!)
  (init!)
  (cljs! "(js/console.log \"Hello from MCP!\")")
  (cljs! "(keys @@evolver.core/store)"))

(println "
🔧 MCP ClojureScript Integration Ready
Run (help!) for instructions
Quick start: (init!)
")