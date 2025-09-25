(ns agent.core
  "Enhanced unified entry point for all agent utilities and analysis tools.

  This module provides:
  - Rich interactive exploration and analysis
  - Tool composition and pipeline execution
  - Configuration management
  - Automated monitoring and alerting
  - Context-aware help and suggestions
  - Session persistence and management

  Quick start:
  (agent.core/explore)     - Interactive system exploration
  (agent.core/diagnose)    - Guided diagnostics
  (agent.core/help)        - Context-aware help
  (agent.core/monitor)     - Start monitoring"
  (:require [agent.enhanced-explorer :as explorer]
            [agent.tool-composer :as composer]
            [agent.configuration :as config]
            [agent.monitoring :as monitoring]
            [agent.context-help :as help]
            [agent.session-persistence :as sessions]
            ;; Legacy tools for backward compatibility
            [agent.code-analysis :as ca]
            [agent.debug-helpers :as dh]
            [agent.repl-workflow :as rw]
            [agent.reference_tools :as rt]
            [agent.dev_tools :as dt]))

;; Enhanced exploration and analysis tools
(def explore explorer/analyze-store-comprehensive)
(def diagnose explorer/interactive-diagnostics)
(def trends explorer/analyze-performance-trends)

;; Tool composition and pipelines
(def pipeline composer/pipeline)
(def execute composer/execute-pipeline)
(def workflows composer/list-pipelines)

;; Configuration management
(def config config/get-config)
(def set-config! config/set-config!)
(def set-profile! config/set-profile!)
(def save-config! config/save-config)
(def show-config config/show-config)

;; Monitoring and alerting
(def create-monitor monitoring/create-monitor)
(def start-monitoring monitoring/start-monitoring)
(def stop-monitoring monitoring/stop-monitoring)
(def monitor-status monitoring/monitoring-status)
(def monitor-report monitoring/monitoring-report)
(def setup-monitoring monitoring/setup-basic-monitoring)

;; Context-aware help and learning
(def contextual-help help/contextual-help)
(def help help/contextual-help)
(def suggest-tools help/adaptive-suggestions)
(def learn help/learning-session)

;; Session persistence
(def save-session sessions/save-session)
(def load-session sessions/load-session)
(def list-sessions sessions/list-sessions)
(def session-info sessions/session-info)
(def end-session sessions/end-session)

;; Legacy tools for backward compatibility
(def analyze-namespace-health ca/analyze-namespace-health)
(def find-potential-forward-decl-issues ca/find-potential-forward-decl-issues)
(def validate-db-structure ca/validate-db-structure)
(def profile-operation ca/profile-operation)

(def db-diff dh/db-diff)
(def check-operation-result dh/check-operation-result)
(def scan-for-errors dh/scan-for-errors)
(def check-consistency dh/check-consistency)

(def test-operations-batch rw/test-operations-batch)
(def health-check rw/health-check)
(def test-workflow rw/test-workflow)

;; Reference system tools
(def inspect-references rt/inspect-references)
(def find-orphaned-references rt/find-orphaned-references)
(def validate-reference-integrity rt/validate-reference-integrity)
(def simulate-reference-hover rt/simulate-reference-hover)
(def reference-stats rt/reference-stats)
(def test-reference-operations rt/test-reference-operations)
(def reference-health-check rt/reference-health-check)

;; Development workflow tools
(def reload! dt/reload!)
(def inspect-store dt/inspect-store)
(def cljs! dt/cljs!)
(def trigger-action! dt/trigger-action!)
(def select-node! dt/select-node!)
(def apply-operation! dt/apply-operation!)
(def watch-build! dt/watch-build!)
(def cljs-repl dt/cljs-repl)
(def inspect-node dt/inspect-node)
(def list-nodes dt/list-nodes)
(def show-selection dt/show-selection)
(def show-references dt/show-references)
(def test-reference-ui dt/test-reference-ui)
(def dev-status dt/dev-status)

;; Enhanced convenience functions for common workflows
(defn analyze-current-system
  "Analyze the current evolver system state comprehensively with rich formatting."
  []
  (explorer/analyze-store-comprehensive))

(defn quick-diagnostics
  "Run quick diagnostics on the system with guided troubleshooting."
  []
  (explorer/interactive-diagnostics))

(defn system-health-check
  "Perform a comprehensive health check with monitoring insights."
  []
  (let [health (rw/health-check)
        alerts (monitoring/get-alerts :acknowledged? false)]
    (println (explorer/format-header "🏥 COMPREHENSIVE HEALTH CHECK" :level 1 :color :cyan))
    (println (explorer/format-key-value "System Healthy" (:healthy? health)))
    (println (explorer/format-key-value "Active Alerts" (count alerts)))
    (when (seq (:issues health))
      (println (explorer/format-key-value "Issues Found" (count (:issues health)))))
    {:health health :alerts alerts}))

(defn start-full-monitoring
  "Start comprehensive monitoring with all available monitors."
  []
  (monitoring/setup-basic-monitoring)
  (monitoring/start-monitoring)
  (println (explorer/ansi-color :green "🚀 Full monitoring suite activated")))

(defn create-analysis-session
  "Create a new analysis session with current state."
  [name & {:keys [description]}]
  (sessions/save-session name :description (or description "Analysis session")))

(defn quick-start
  "Quick start guide for new users."
  []
  (println (explorer/format-header "🚀 AGENT TOOLS QUICK START" :level 1 :color :magenta))
  (println "Welcome to the enhanced agent tools! Here's how to get started:")
  (println)
  (println (explorer/ansi-color :cyan "1. Explore your system:"))
  (println "   (agent.core/explore)")
  (println)
  (println (explorer/ansi-color :cyan "2. Get personalized help:"))
  (println "   (agent.core/help)")
  (println)
  (println (explorer/ansi-color :cyan "3. Set up monitoring:"))
  (println "   (agent.core/start-full-monitoring)")
  (println)
  (println (explorer/ansi-color :cyan "4. Save your session:"))
  (println "   (agent.core/create-analysis-session \"my-session\")")
  (println)
  (println (explorer/ansi-color :cyan "5. Learn interactively:"))
  (println "   (agent.core/learn)")
  (println)
  (println "For more advanced usage, see (agent.core/help :topic :analysis)"))

;; Enhanced pretty-printing helpers with rich formatting
(defn pprint-db-diff
  "Pretty print a database diff result with rich formatting."
  [diff]
  (println (explorer/format-header "🔄 DATABASE DIFF" :level 1 :color :yellow))
  (println (explorer/format-key-value "Nodes Added" (:nodes-added diff)))
  (println (explorer/format-key-value "Children Changed" (:children-changed diff)))
  (println (explorer/format-key-value "View Changed" (:view-changed diff)))
  (println (explorer/format-key-value "TX Log Growth" (:tx-log-growth diff)))
  (println (explorer/format-key-value "Summary" (:summary diff))))

(defn pprint-health-check
  "Pretty print a health check result with rich formatting."
  [health]
  (let [status-color (if (:healthy? health) :green :red)]
    (println (explorer/format-header "🏥 HEALTH CHECK" :level 1 :color status-color))
    (println (explorer/format-key-value "System Healthy" (:healthy? health)))
    (println (explorer/format-key-value "Schema Valid" (:schema-valid? health)))
    (println (explorer/format-key-value "Node Count" (:node-count health)))
    (println (explorer/format-key-value "TX Count" (:tx-count health)))
    (when (seq (:issues health))
      (println (explorer/format-header "⚠️ ISSUES FOUND" :level 2 :color :red))
      (explorer/format-list (:issues health) :bullet true))))

(defn pprint-analysis-result
  "Pretty print an analysis result with rich formatting."
  [result]
  (cond
    (:namespace result)
    (let [status-color (case (:status result)
                         :healthy :green
                         :not-found :yellow
                         :error :red
                         :blue)]
      (println (explorer/format-header (str "📦 NAMESPACE: " (:namespace result)) :level 1 :color status-color))
      (println (explorer/format-key-value "Status" (:status result)))
      (when (= (:status result) :healthy)
        (println (explorer/format-key-value "Public Functions" (:public-fns result)))
        (println (explorer/format-key-value "Dependencies" (:dependencies result))))
      (when (:potential-issues result)
        (println (explorer/format-header "⚠️ POTENTIAL ISSUES" :level 2 :color :yellow))
        (doseq [[k v] (:potential-issues result)]
          (println (explorer/format-key-value (name k) v)))))

    (:valid? result)
    (let [status-color (if (:valid? result) :green :red)]
      (println (explorer/format-header "🗂️ DB STRUCTURE VALIDATION" :level 1 :color status-color))
      (println (explorer/format-key-value "Valid" (:valid? result)))
      (println (explorer/format-key-value "Node Count" (:node-count result)))
      (println (explorer/format-key-value "TX Count" (:tx-count result)))
      (when (seq (:missing-keys result))
        (println (explorer/format-key-value "Missing Keys" (explorer/format-list (:missing-keys result) :bullet true))))
      (when (seq (:extra-keys result))
        (println (explorer/format-key-value "Extra Keys" (explorer/format-list (:extra-keys result) :bullet true)))))

    :else
    (do
      (println (explorer/format-header "📊 ANALYSIS RESULT" :level 1 :color :blue))
      (println result))))

(defn pprint-session-info
  "Pretty print session information."
  [session]
  (when session
    (println (explorer/format-header (str "💾 SESSION: " (:name session)) :level 1 :color :cyan))
    (println (explorer/format-key-value "Description" (:description session)))
    (println (explorer/format-key-value "Created" (java.util.Date. (:timestamp session))))
    (when (:system-state session)
      (println (explorer/format-header "📊 SYSTEM STATE" :level 2 :color :green))
      (doseq [[k v] (:system-state session)]
        (println (explorer/format-key-value (name k) v))))))

(defn pprint-monitor-status
  "Pretty print monitoring status."
  [status]
  (println (explorer/format-header "📊 MONITOR STATUS" :level 1 :color :cyan))
  (doseq [[name monitor] status]
    (let [status-color (case (:status monitor)
                         :running :green
                         :stopped :red
                         :blue)]
      (println (str (explorer/ansi-color status-color "● ") name))
      (println (explorer/format-key-value "Status" (:status monitor) :indent 2))
      (println (explorer/format-key-value "Last Run" (if (:last-run monitor)
                                                       (java.util.Date. (:last-run monitor))
                                                       "Never") :indent 2))
      (println (explorer/format-key-value "Run Count" (:run-count monitor) :indent 2)))))