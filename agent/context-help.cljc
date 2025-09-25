;; Context-Aware Help and Tool Suggestion System
;; Provides intelligent suggestions based on current system state and user context

(ns agent.context-help
  "Context-aware help and tool suggestion system.

  Features:
  - Intelligent tool suggestions based on system state
  - Context-aware help topics
  - Usage pattern analysis
  - Interactive guidance
  - Learning from user behavior"
  (:require [agent.enhanced-explorer :as explorer]
            [agent.configuration :as config]
            [clojure.string :as str]))

;; Context analysis
(defn- analyze-system-context
  "Analyze current system state to determine context"
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)
        node-count (count (:nodes db))
        tx-count (count (:tx-log db))
        error-count (count (filter #(= (:level %) :error) (:log-history db)))
        ref-count (count (:references db))
        selected-count (count (:selected (:view db)))
        has-undo (seq (:undo-stack db))
        has-redo (seq (:redo-stack db))]

    (cond-> {:node-count node-count
             :tx-count tx-count
             :error-count error-count
             :ref-count ref-count
             :selected-count selected-count
             :has-undo has-undo
             :has-redo has-redo}

      ;; Fresh system
      (and (= node-count 1) (= tx-count 0))
      (assoc :context :fresh-system)

      ;; Error state
      (> error-count 0)
      (assoc :context :error-state)

      ;; Selection context
      (= selected-count 0)
      (assoc :context :no-selection)

      (= selected-count 1)
      (assoc :context :single-selection)

      (= selected-count 2)
      (assoc :context :multi-selection)

      ;; Reference context
      (> ref-count 0)
      (assoc :context :has-references)

      ;; Performance context
      (> tx-count 100)
      (assoc :context :high-activity)

      ;; Development context
      (and has-undo has-redo)
      (assoc :context :active-development))))

(defn- get-context-suggestions
  "Get tool suggestions based on system context"
  [context]
  (let [base-suggestions ["analyze-store-comprehensive" "interactive-diagnostics"]]
    (case (:context context)
      :fresh-system
      (concat base-suggestions
              ["dev-tools/test-reference-ui" "dev-tools/keyboard-health-check"])

      :error-state
      (concat ["debug-helpers/scan-for-errors" "debug-helpers/check-consistency"]
              base-suggestions)

      :no-selection
      ["dev-tools/inspect-store" "analyze-store-comprehensive"
       "dev-tools/list-nodes" "dev-tools/show-selection"]

      :single-selection
      ["dev-tools/apply-operation!" "dev-tools/select-node!"
       "reference-tools/simulate-reference-hover"]

      :multi-selection
      ["dev-tools/apply-operation!" "reference-tools/test-reference-operations"]

      :has-references
      ["reference-tools/inspect-references" "reference-tools/validate-reference-integrity"
       "reference-tools/reference-stats"]

      :high-activity
      ["analyze-performance-trends" "repl-workflow/test-operations-batch"
       "store-inspector/performance-metrics"]

      :active-development
      ["dev-tools/cljs-repl" "dev-tools/test-keyboard-navigation"
       "debug-helpers/trace-keyboard-operation"]

      ;; Default suggestions
      base-suggestions)))

;; Intelligent help system
(defn contextual-help
  "Provide context-aware help and suggestions"
  []
  (let [context (analyze-system-context)]
    (println (explorer/format-header "🧠 CONTEXT-AWARE HELP" :level 1 :color :cyan))

    ;; Current context
    (println (explorer/format-header "📍 CURRENT CONTEXT" :level 2 :color :blue))
    (println (explorer/format-key-value "System State" (name (:context context))))
    (println (explorer/format-key-value "Nodes" (:node-count context)))
    (println (explorer/format-key-value "Transactions" (:tx-count context)))
    (println (explorer/format-key-value "Errors" (:error-count context)))
    (println (explorer/format-key-value "References" (:ref-count context)))
    (println (explorer/format-key-value "Selected" (:selected-count context)))

    ;; Context-specific insights
    (println "\n" (explorer/format-header "💡 INSIGHTS" :level 2 :color :yellow))
    (cond
      (= (:context context) :fresh-system)
      (println (explorer/ansi-color :green "🎯 Fresh system detected - great time to explore features!"))

      (= (:context context) :error-state)
      (println (explorer/ansi-color :red "⚠️  Errors detected - focus on debugging and health checks"))

      (= (:context context) :no-selection)
      (println (explorer/ansi-color :blue "ℹ️  No nodes selected - try selecting nodes to enable more operations"))

      (= (:context context) :single-selection)
      (println (explorer/ansi-color :green "✅ Single node selected - ready for node-specific operations"))

      (= (:context context) :multi-selection)
      (println (explorer/ansi-color :green "✅ Multiple nodes selected - reference operations available"))

      (= (:context context) :has-references)
      (println (explorer/ansi-color :blue "🔗 Reference system active - explore reference tools"))

      (= (:context context) :high-activity)
      (println (explorer/ansi-color :yellow "⚡ High activity detected - monitor performance"))

      (= (:context context) :active-development)
      (println (explorer/ansi-color :green "🚀 Active development - full feature set available")))

    ;; Suggested tools
    (let [suggestions (get-context-suggestions context)]
      (println "\n" (explorer/format-header "🔧 SUGGESTED TOOLS" :level 2 :color :green))
      (println "Based on your current context, try these tools:")
      (println (explorer/format-list suggestions :numbered true)))

    ;; Quick actions
    (println "\n" (explorer/format-header "⚡ QUICK ACTIONS" :level 2 :color :magenta))
    (println "• (contextual-help) - This help (context updates automatically)")
    (println "• (analyze-store-comprehensive) - Full system analysis")
    (println "• (interactive-diagnostics) - Guided troubleshooting")

    ;; Interactive mode
    (when (config/get-config :interactive-mode)
      (println "\n" (explorer/format-header "🎮 INTERACTIVE MODE" :level 2 :color :cyan))
      (println "Choose an option:")
      (println "1. Run suggested analysis")
      (println "2. Show detailed context info")
      (println "3. Get tool help")
      (println "4. Exit")

      (when-let [choice (explorer/prompt-user "Enter choice (1-4)" nil)]
        (case choice
          "1" (do
                (println (explorer/ansi-color :blue "Running comprehensive analysis..."))
                (explorer/analyze-store-comprehensive))
          "2" (do
                (println (explorer/ansi-color :blue "Detailed context information:"))
                (println (explorer/format-key-value "Full Context" context)))
          "3" (do
                (println (explorer/ansi-color :blue "Available help topics:"))
                (println (explorer/format-list ["analysis" "monitoring" "debugging" "development"] :bullet true)))
          "4" (println (explorer/ansi-color :green "Goodbye!"))
          (println (explorer/ansi-color :red "Invalid choice")))))))

;; Tool discovery and usage patterns
(def ^:private tool-usage-history (atom {}))

(defn- record-tool-usage
  "Record tool usage for learning patterns"
  [tool-name]
  (swap! tool-usage-history update tool-name
         (fnil (fn [data]
                 (-> data
                     (update :count inc)
                     (assoc :last-used (System/currentTimeMillis))))
               {:count 0 :first-used (System/currentTimeMillis)})))

(defn- get-popular-tools
  "Get most popular tools based on usage history"
  [limit]
  (->> @tool-usage-history
       (sort-by #(-> % val :count) >)
       (take limit)
       (map first)))

(defn- get-recent-tools
  "Get recently used tools"
  [limit]
  (->> @tool-usage-history
       (sort-by #(-> % val :last-used) >)
       (take limit)
       (map first)))

;; Enhanced help topics
(defn help-topic
  "Get detailed help on a specific topic"
  [topic]
  (case topic
    :analysis
    (do
      (println (explorer/format-header "📊 ANALYSIS TOOLS" :level 1 :color :cyan))
      (println "Comprehensive analysis and inspection tools:")
      (println "• analyze-store-comprehensive - Full system state analysis")
      (println "• analyze-performance-trends - Performance metrics and trends")
      (println "• interactive-diagnostics - Guided troubleshooting")
      (println "• reference-tools/* - Reference system analysis")
      (println "• store-inspector/* - Store state inspection")
      (println "• debug-helpers/* - Debugging utilities"))

    :monitoring
    (do
      (println (explorer/format-header "📊 MONITORING SYSTEM" :level 1 :color :cyan))
      (println "Automated health monitoring and alerting:")
      (println "• monitoring/create - Create monitors")
      (println "• monitoring/start - Start monitoring")
      (println "• monitoring/status - View monitor status")
      (println "• monitoring/report - Comprehensive report")
      (println "• monitoring/setup - Quick setup"))

    :debugging
    (do
      (println (explorer/format-header "🐛 DEBUGGING TOOLS" :level 1 :color :cyan))
      (println "Tools for troubleshooting and issue resolution:")
      (println "• debug-helpers/scan-for-errors - Error detection")
      (println "• debug-helpers/check-consistency - Data consistency")
      (println "• debug-helpers/db-diff - Change analysis")
      (println "• dev-tools/cljs-repl - Interactive debugging")
      (println "• dev-tools/test-* - Automated testing"))

    :development
    (do
      (println (explorer/format-header "🚀 DEVELOPMENT TOOLS" :level 1 :color :cyan))
      (println "Tools for development workflow:")
      (println "• dev-tools/reload! - Hot reload")
      (println "• dev-tools/cljs-repl - REPL access")
      (println "• dev-tools/test-* - Feature testing")
      (println "• tool-composer/* - Pipeline composition")
      (println "• configuration/* - Config management"))

    :pipelines
    (do
      (println (explorer/format-header "🔧 TOOL PIPELINES" :level 1 :color :cyan))
      (println "Composing and chaining analysis tools:")
      (println "• tool-composer/pipeline - Create pipelines")
      (println "• tool-composer/execute - Run pipelines")
      (println "• tool-composer/workflows - List workflows")
      (println "• tool-composer/create-custom-pipeline - Custom workflows"))

    :configuration
    (do
      (println (explorer/format-header "⚙️ CONFIGURATION" :level 1 :color :cyan))
      (println "Customizing tool behavior:")
      (println "• config/set! - Set configuration")
      (println "• config/profile! - Switch profiles")
      (println "• config/show - View current config")
      (println "• config/save! - Persist configuration"))

    ;; Default
    (println (explorer/ansi-color :red (str "❌ Unknown help topic: " topic)))
    (println (explorer/ansi-color :yellow "Available topics: analysis, monitoring, debugging, development, pipelines, configuration"))))

;; Learning and adaptation
(defn- analyze-usage-patterns
  "Analyze usage patterns to provide better suggestions"
  []
  (let [popular (get-popular-tools 5)
        recent (get-recent-tools 3)]
    {:popular-tools popular
     :recent-tools recent
     :total-tools-used (count @tool-usage-history)
     :most-used (first popular)}))

(defn adaptive-suggestions
  "Provide suggestions based on usage patterns and current context"
  []
  (let [patterns (analyze-usage-patterns)
        context (analyze-system-context)]
    (println (explorer/format-header "🎯 ADAPTIVE SUGGESTIONS" :level 1 :color :magenta))

    ;; Usage-based suggestions
    (when (seq (:popular-tools patterns))
      (println (explorer/format-header "⭐ YOUR FAVORITE TOOLS" :level 2 :color :yellow))
      (println (explorer/format-list (:popular-tools patterns) :bullet true)))

    ;; Context + usage suggestions
    (println "\n" (explorer/format-header "🎪 PERSONALIZED RECOMMENDATIONS" :level 2 :color :green))
    (let [context-suggestions (get-context-suggestions context)
          personalized (if (:most-used patterns)
                        (cons (:most-used patterns) context-suggestions)
                        context-suggestions)]
      (println (explorer/format-list (distinct personalized) :numbered true)))

    ;; Learning insights
    (println "\n" (explorer/format-header "📈 LEARNING INSIGHTS" :level 2 :color :blue))
    (println (explorer/format-key-value "Tools Used" (:total-tools-used patterns)))
    (when (:most-used patterns)
      (println (explorer/format-key-value "Most Used Tool" (:most-used patterns))))
    (println (explorer/format-key-value "Recent Tools" (str/join ", " (:recent-tools patterns))))))

;; Interactive learning session
(defn learning-session
  "Start an interactive learning session to discover tools"
  []
  (println (explorer/format-header "🎓 INTERACTIVE LEARNING SESSION" :level 1 :color :magenta))
  (println "Let's explore the agent tools together!")

  (let [context (analyze-system-context)
        suggestions (get-context-suggestions context)]

    (println "\n" (explorer/format-header "🎯 STARTING POINT" :level 2 :color :cyan))
    (println (str "Your current context: " (name (:context context))))
    (println "Recommended first tool to try:")
    (println (str "• " (first suggestions)))

    (when (config/get-config :interactive-mode)
      (println "\n" (explorer/format-header "🧪 LEARNING PATH" :level 2 :color :yellow))
      (println "1. Try the suggested tool")
      (println "2. Explore related tools")
      (println "3. Learn about tool composition")
      (println "4. Set up monitoring")

      (loop [step 1]
        (when (<= step 4)
          (println (str "\n" (explorer/ansi-color :green (str "Step " step ":"))))
          (case step
            1 (do
                (println "Try running: (analyze-store-comprehensive)")
                (when (= "y" (explorer/prompt-user "Ready to try it? (y/n)" nil))
                  (explorer/analyze-store-comprehensive)))
            2 (do
                (println "Explore: (contextual-help)")
                (when (= "y" (explorer/prompt-user "Want to see more suggestions? (y/n)" nil))
                  (contextual-help)))
            3 (do
                (println "Learn about pipelines: (tool-composer/workflows)")
                (when (= "y" (explorer/prompt-user "Interested in tool composition? (y/n)" nil))
                  ((requiring-resolve 'agent.tool-composer/workflows))))
            4 (do
                (println "Set up monitoring: (monitoring/setup)")
                (when (= "y" (explorer/prompt-user "Want to set up monitoring? (y/n)" nil))
                  ((requiring-resolve 'agent.monitoring/setup)))))
          (recur (inc step)))))

    (println "\n" (explorer/ansi-color :green "🎉 Learning session complete!"))
    (println "Use (contextual-help) anytime for personalized assistance.")))

;; Tool usage tracking (call this from main tool functions)
(defn track-tool-usage
  "Track tool usage for learning (call this from tool functions)"
  [tool-name]
  (record-tool-usage tool-name))

;; Export main functions
(def help contextual-help)
(def topic help-topic)
(def suggest adaptive-suggestions)
(def learn learning-session)