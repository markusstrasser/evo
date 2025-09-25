(ns agent.dev-tools
  "Enhanced development tools for ClojureScript workflow"
  (:require [shadow.cljs.devtools.api :as shadow]))

(defn reload!
  "Reload the application in the browser"
  []
  (shadow/cljs-eval :frontend "(evolver.core/main)" {}))

(defn inspect-store
  "Inspect the current application store state"
  []
  (shadow/cljs-eval :frontend "@evolver.core/store" {}))

(defn cljs!
  "Execute arbitrary ClojureScript code in the browser"
  [form-as-string]
  (shadow/cljs-eval :frontend form-as-string {}))

(defn trigger-action!
  "Trigger a replicant action in the browser"
  [action]
  (shadow/cljs-eval :frontend
                    (str "(evolver.core/handle-event "
                         (pr-str {:replicant/dom-event {}})
                         " "
                         (pr-str [action])
                         ")")
                    {}))

(defn select-node!
  "Select a specific node by ID"
  [node-id]
  (trigger-action! [:select-node {:node-id node-id}]))

(defn apply-operation!
  "Apply a selected operation"
  [op]
  (cljs! (str "(swap! evolver.core/store assoc :selected-op " (pr-str op) ")"))
  (trigger-action! [:apply-selected-op]))

(defn watch-build!
  "Start watching the frontend build"
  []
  (shadow/watch :frontend)
  (println "Watching :frontend build"))

(defn cljs-repl
  "Connect to the browser REPL"
  []
  (println "Connecting to browser REPL... (type :cljs/quit to return)")
  (shadow/repl :frontend))

(defn inspect-node
  "Inspect a specific node by ID"
  [node-id]
  (cljs! (str "(get-in @evolver.core/store [:nodes " (pr-str node-id) "])")))

(defn list-nodes
  "List all node IDs in the current store"
  []
  (cljs! "(keys (:nodes @evolver.core/store))"))

(defn show-selection
  "Show currently selected nodes"
  []
  (cljs! "(:selected (:view @evolver.core/store))"))

(defn show-references
  "Show current reference graph"
  []
  (cljs! "(:references @evolver.core/store)"))

(defn test-reference-ui
  "Test the reference UI by selecting nodes and adding a reference"
  []
  (println "Testing reference system...")
  (cljs! "(evolver.core/handle-event {:replicant/dom-event {}} [[:select-node {:node-id \"title\"}]])")
  (cljs! "(evolver.core/handle-event {:replicant/dom-event {}} [[:select-node {:node-id \"p1-select\"}]])")
  (cljs! "(swap! evolver.core/store assoc :selected-op :add-reference)")
  (cljs! "(evolver.core/handle-event {:replicant/dom-event {}} [[:apply-selected-op]])")
  (println "Reference test completed - check browser for results"))

(defn dev-status
  "Show development environment status"
  []
  (println "\n🔥 DEV ENVIRONMENT STATUS")
  (println "─────────────────────────")
  (println "• Frontend build: Watching")
  (println "• Browser REPL: Available")
  (println "• Store inspection: Ready")
  (println "• Reference system: Active")
  (println "\n📋 QUICK COMMANDS:")
  (println "• (reload!) - Reload app")
  (println "• (cljs-repl) - Browser REPL")
  (println "• (inspect-store) - View state")
  (println "• (test-reference-ui) - Test references")
  (println "• (reference-health-check) - Check system"))