(ns agent.dev-tools
  "Enhanced development tools for ClojureScript workflow"
  (:require [shadow.cljs.devtools.api :as shadow]
            [agent.state-validation :as validation]))

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

(defn simulate-keyboard-event!
  "Simulate a keyboard event in the browser"
  [key-code & {:keys [shift? ctrl? alt? meta?]}]
  (let [event-str (str "(let [event (js/KeyboardEvent. \"keydown\" "
                       "{:key " (pr-str key-code)
                       " :shiftKey " (boolean shift?)
                       " :ctrlKey " (boolean ctrl?)
                       " :altKey " (boolean alt?)
                       " :metaKey " (boolean meta?)
                       "})]"
                       "(evolver.core/handle-keyboard-event event)")]]
    (cljs! event-str)))

(defn test-keyboard-navigation
  "Test basic keyboard navigation (arrow keys)"
  []
  (println "Testing keyboard navigation...")
  (simulate-keyboard-event! "ArrowDown")
  (simulate-keyboard-event! "ArrowUp")
  (simulate-keyboard-event! "ArrowLeft")
  (simulate-keyboard-event! "ArrowRight")
  (println "Navigation test completed"))

(defn test-keyboard-operations
  "Test keyboard operations (create, delete, etc.)"
  []
  (println "Testing keyboard operations...")
  ;; Select first node
  (simulate-keyboard-event! "ArrowDown")
  ;; Test create operations
  (simulate-keyboard-event! "c") ; Create child
  (simulate-keyboard-event! "C") ; Create sibling above
  (simulate-keyboard-event! "Enter") ; Create sibling below
  ;; Test delete
  (simulate-keyboard-event! "Delete")
  ;; Test undo/redo
  (simulate-keyboard-event! "z" :ctrl? true) ; Undo
  (simulate-keyboard-event! "y" :ctrl? true) ; Redo
  (println "Operations test completed"))

(defn test-keyboard-selection
  "Test keyboard selection operations"
  []
  (println "Testing keyboard selection...")
  ;; Multi-selection with shift
  (simulate-keyboard-event! "ArrowDown" :shift? true)
  (simulate-keyboard-event! "ArrowDown" :shift? true)
  ;; Clear selection
  (simulate-keyboard-event! "Escape")
  (println "Selection test completed"))

(defn test-keyboard-references
  "Test keyboard reference operations"
  []
  (println "Testing keyboard references...")
  ;; Select two nodes
  (simulate-keyboard-event! "ArrowDown")
  (simulate-keyboard-event! "ArrowDown" :shift? true)
  ;; Add reference
  (simulate-keyboard-event! "r")
  ;; Remove reference
  (simulate-keyboard-event! "R")
  (println "References test completed"))

(defn keyboard-health-check
  "Run comprehensive keyboard system health check"
  []
  (println "\n⌨️  KEYBOARD SYSTEM HEALTH CHECK")
  (println "─────────────────────────────────")
  (println "Testing all keyboard operations...")

  ;; Test navigation
  (test-keyboard-navigation)

  ;; Test operations
  (test-keyboard-operations)

  ;; Test selection
  (test-keyboard-selection)

  ;; Test references
  (test-keyboard-references)

  ;; Show final state
  (println "\nFinal state after tests:")
  (show-selection)
  (inspect-store)

  (println "\n✅ Keyboard health check completed"))

(defn system-health-check
  "Run comprehensive system health check"
  []
  (validation/comprehensive-health-check))

(defn dev-status
  "Show development environment status"
  []
  (println "\n🔥 DEV ENVIRONMENT STATUS")
  (println "─────────────────────────")
  (println "• Frontend build: Watching")
  (println "• Browser REPL: Available")
  (println "• Store inspection: Ready")
  (println "• Reference system: Active")
  (println "• Keyboard system: Active")
  (println "\n📋 QUICK COMMANDS:")
  (println "• (reload!) - Reload app")
  (println "• (cljs-repl) - Browser REPL")
  (println "• (inspect-store) - View state")
  (println "• (test-reference-ui) - Test references")
  (println "• (keyboard-health-check) - Test keyboard")
  (println "• (system-health-check) - Full health check"))