;; Enhanced Interactive Explorer for Evolver
;; Provides rich formatting, interactive exploration, and powerful analysis tools

(ns agent.enhanced-explorer
  "Enhanced interactive exploration tools with rich formatting and analysis capabilities.

  This module provides:
  - Rich text formatting and visualization
  - Interactive exploration workflows
  - Context-aware analysis
  - Tool composition and chaining
  - Session persistence
  - Automated monitoring"
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]))

;; Configuration system
(def ^:dynamic *explorer-config*
  "Global configuration for explorer behavior"
  {:output-format :rich-text
   :max-depth 5
   :max-items 20
   :colors-enabled true
   :interactive-mode true
   :auto-save-sessions false
   :monitoring-enabled false})

(defn configure!
  "Configure explorer behavior globally or for current session"
  [config]
  (alter-var-root #'*explorer-config* merge config))

;; Rich text formatting utilities
(defn- ansi-color [color text]
  (if (:colors-enabled *explorer-config*)
    (case color
      :red (str "\u001b[31m" text "\u001b[0m")
      :green (str "\u001b[32m" text "\u001b[0m")
      :yellow (str "\u001b[33m" text "\u001b[0m")
      :blue (str "\u001b[34m" text "\u001b[0m")
      :magenta (str "\u001b[35m" text "\u001b[0m")
      :cyan (str "\u001b[36m" text "\u001b[0m")
      :bold (str "\u001b[1m" text "\u001b[0m")
      :dim (str "\u001b[2m" text "\u001b[0m")
      text)
    text))

(defn- format-header [title & {:keys [level color]}]
  (let [level (or level 1)
        color (or color :cyan)
        prefix (case level
                 1 "╔══════════════════════════════════════════════════════════════╗\n║ "
                 2 "┌─────────────────────────────────────────────────────────────┐\n│ "
                 3 "┌─ "
                 "")
        suffix (case level
                 1 " ║\n╚══════════════════════════════════════════════════════════════╝"
                 2 " │\n└─────────────────────────────────────────────────────────────┘"
                 3 " ─┐"
                 "")]
    (str (ansi-color color (str prefix title suffix)))))

(defn- format-key-value [key value & {:keys [indent]}]
  (let [indent (or indent 0)
        indent-str (str/join "" (repeat indent " "))
        key-str (ansi-color :cyan (str key ":"))
        value-str (if (coll? value)
                    (with-out-str (pp/pprint value))
                    (str value))]
    (str indent-str key-str " " value-str)))

(defn- format-list [items & {:keys [numbered bullet max-items]}]
  (let [max-items (or max-items (:max-items *explorer-config*))
        items (if (> (count items) max-items)
                (concat (take max-items items) [(str "... and " (- (count items) max-items) " more")])
                items)]
    (str/join "\n"
              (map-indexed
               (fn [idx item]
                 (let [prefix (cond
                                numbered (str (inc idx) ". ")
                                bullet "• "
                                :else "  ")]
                   (str prefix item)))
               items))))

(defn- format-table [headers rows & {:keys [alignments]}]
  (let [alignments (or alignments (repeat (count headers) :left))
        col-widths (mapv (fn [idx]
                          (apply max
                                 (map #(count (str %))
                                      (cons (nth headers idx)
                                            (map #(nth % idx) rows)))))
                        (range (count headers)))
        format-row (fn [row]
                     (str/join " │ "
                               (map-indexed
                                (fn [idx cell]
                                  (let [width (nth col-widths idx)
                                        align (nth alignments idx :left)
                                        cell-str (str cell)]
                                    (case align
                                      :right (format (str "%" width "s") cell-str)
                                      :center (let [padding (/ (- width (count cell-str)) 2)
                                                    left-pad (int (Math/floor padding))
                                                    right-pad (int (Math/ceil padding))]
                                                (str (str/join "" (repeat left-pad " "))
                                                     cell-str
                                                     (str/join "" (repeat right-pad " "))))
                                      (format (str "%-" width "s") cell-str))))
                                row)))]
    (str (format-row headers) "\n"
         (str/join "─┼─" (map #(str/join "" (repeat % "─")) col-widths)) "\n"
         (str/join "\n" (map format-row rows)))))

;; Interactive exploration utilities
(defn- prompt-user [question options]
  (when (:interactive-mode *explorer-config*)
    (println (ansi-color :yellow (str "? " question)))
    (when options
      (println (format-list options :numbered true)))
    (print (ansi-color :green "→ "))
    (flush)
    (read-line)))

(defn- explore-collection [coll path & {:keys [max-depth]}]
  (let [max-depth (or max-depth (:max-depth *explorer-config*))]
    (cond
      (and (map? coll) (> (count coll) 0))
      (let [keys (keys coll)]
        (println (format-header (str "Exploring Map at " path) :level 2 :color :blue))
        (println (str "Keys: " (format-list (map str keys) :bullet true)))
        (when-let [choice (prompt-user "Choose a key to explore (or 'all' for summary)" nil)]
          (cond
            (= choice "all")
            (doseq [k keys]
              (println (format-key-value (str k) (get coll k) :indent 2)))

            (contains? (set (map str keys)) choice)
            (let [key (if (keyword? (first keys)) (keyword choice) choice)]
              (explore-collection (get coll key) (str path "/" choice) :max-depth (dec max-depth))))

            :else
            (println (ansi-color :red "Invalid choice")))))

      (and (seq? coll) (> (count coll) 0))
      (let [items coll]
        (println (format-header (str "Exploring Sequence at " path) :level 2 :color :blue))
        (println (str "Count: " (count items)))
        (println (str "Sample: " (format-list (take 5 items) :bullet true)))
        (when-let [choice (prompt-user "Choose an index to explore (0-based)" nil)]
          (try
            (let [idx (Integer/parseInt choice)]
              (when (< idx (count items))
                (explore-collection (nth items idx) (str path "[" idx "]") :max-depth (dec max-depth))))
            (catch NumberFormatException _
              (println (ansi-color :red "Invalid index"))))))

      :else
      (println (format-key-value "Value" coll)))))

;; Enhanced analysis functions
(defn analyze-store-comprehensive
  "Comprehensive store analysis with rich formatting and insights"
  [& {:keys [include-references include-history include-performance]}]
  (let [db @(requiring-resolve 'evolver.kernel/db)
        include-references (or include-references true)
        include-history (or include-history true)
        include-performance (or include-performance true)]

    ;; Header
    (println (format-header "🧠 EVOLVER STORE ANALYSIS" :level 1 :color :magenta))

    ;; Basic stats
    (println (format-header "📊 BASIC STATISTICS" :level 2 :color :cyan))
    (println (format-key-value "Nodes" (count (:nodes db))))
    (println (format-key-value "Children Relationships" (count (:children-by-parent db))))
    (println (format-key-value "Selected Nodes" (count (:selected (:view db)))))
    (println (format-key-value "Transaction Log" (count (:tx-log db))))
    (println (format-key-value "Undo Stack" (count (:undo-stack db))))

    ;; Node type distribution
    (let [node-types (frequencies (map :type (vals (:nodes db))))]
      (println "\n" (format-header "🏷️ NODE TYPE DISTRIBUTION" :level 2 :color :green))
      (println (format-table ["Type" "Count" "Percentage"]
                            (mapv (fn [[type count]]
                                    [type count (format "%.1f%%" (* 100.0 (/ count (count (:nodes db)))))] )
                                  (sort-by second > node-types))
                            :alignments [:left :right :right])))

    ;; Selection analysis
    (let [selected (:selected (:view db))]
      (println "\n" (format-header "🎯 SELECTION ANALYSIS" :level 2 :color :yellow))
      (println (format-key-value "Selection Count" (count selected)))
      (when (seq selected)
        (println (format-key-value "Selected IDs" (format-list selected :bullet true)))
        (let [selected-nodes (map #(get (:nodes db) %) selected)
              types (frequencies (map :type selected-nodes))]
          (println (format-key-value "Selected Types" (format-table ["Type" "Count"]
                                                                   (sort-by second > types)
                                                                   :alignments [:left :right]))))))

    ;; Reference analysis
    (when include-references
      (let [references (:references db)]
        (println "\n" (format-header "🔗 REFERENCE ANALYSIS" :level 2 :color :blue))
        (println (format-key-value "Total References" (count references)))
        (when (seq references)
          (let [ref-counts (map count (vals references))
                total-refs (reduce + ref-counts)]
            (println (format-key-value "Total Reference Links" total-refs))
            (println (format-key-value "Avg References per Node" (format "%.2f" (/ total-refs (max 1 (count references))))))
            (println (format-key-value "Most Referenced"
                                      (when-let [most-refed (first (sort-by #(count (val %)) > references))]
                                        (str (key most-refed) " (" (count (val most-refed)) " references)"))))))))

    ;; Performance metrics
    (when include-performance
      (println "\n" (format-header "⚡ PERFORMANCE METRICS" :level 2 :color :red))
      (let [node-count (count (:nodes db))
            ref-count (count (:references db))
            tx-count (count (:tx-log db))]
        (println (format-key-value "Memory Footprint Estimate"
                                  (str (format "%.2f" (/ (+ (* node-count 100) ; rough node size
                                                           (* ref-count 50)   ; rough ref size
                                                           (* tx-count 200))  ; rough tx size
                                                        1024.0)) " KB")))
        (println (format-key-value "Operation Density" (format "%.2f ops/node" (/ tx-count (max 1 node-count)))))))

    ;; Interactive exploration
    (when (:interactive-mode *explorer-config*)
      (println "\n" (format-header "🔍 INTERACTIVE EXPLORATION" :level 2 :color :magenta))
      (println "Available exploration options:")
      (println "• 'nodes' - Explore node structure")
      (println "• 'tree' - Explore tree hierarchy")
      (println "• 'refs' - Explore reference graph")
      (println "• 'history' - Explore transaction history")
      (println "• 'quit' - Exit exploration")

      (loop []
        (when-let [choice (prompt-user "What would you like to explore?" nil)]
          (case choice
            "nodes" (explore-collection (:nodes db) "nodes")
            "tree" (explore-collection (:children-by-parent db) "children-by-parent")
            "refs" (explore-collection (:references db) "references")
            "history" (explore-collection (:tx-log db) "tx-log")
            "quit" nil
            (println (ansi-color :red "Unknown option")))
          (when (not= choice "quit")
            (recur)))))))

(defn analyze-performance-trends
  "Analyze performance trends over time with visualizations"
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)
        tx-log (:tx-log db)]

    (println (format-header "📈 PERFORMANCE TRENDS ANALYSIS" :level 1 :color :green))

    (if (< (count tx-log) 2)
      (println (ansi-color :yellow "⚠️  Not enough transaction data for trend analysis"))
      (let [operations (map :op tx-log)
            timestamps (map :timestamp tx-log)
            time-diffs (map - (rest timestamps) timestamps)

            op-freq (frequencies operations)
            avg-time-between-ops (if (seq time-diffs)
                                   (/ (reduce + time-diffs) (count time-diffs))
                                   0)

            recent-ops (take-last 10 operations)
            recent-freq (frequencies recent-ops)]

        (println (format-header "⏱️ TIMING ANALYSIS" :level 2 :color :cyan))
        (println (format-key-value "Total Operations" (count tx-log)))
        (println (format-key-value "Time Span" (str (format "%.2f" (/ (- (last timestamps) (first timestamps)) 1000.0)) " seconds")))
        (println (format-key-value "Avg Time Between Ops" (str (format "%.2f" (/ avg-time-between-ops 1000.0)) " seconds")))

        (println "\n" (format-header "📊 OPERATION FREQUENCY" :level 2 :color :yellow))
        (println (format-table ["Operation" "Count" "Percentage"]
                              (mapv (fn [[op count]]
                                      [op count (format "%.1f%%" (* 100.0 (/ count (count operations))))])
                                    (sort-by second > op-freq))
                              :alignments [:left :right :right]))

        (println "\n" (format-header "🔥 RECENT ACTIVITY (last 10 ops)" :level 2 :color :red))
        (println (format-list (map #(str (name %) " (" (get recent-freq % 1) "x)") recent-ops) :bullet true))

        ;; Performance insights
        (println "\n" (format-header "💡 PERFORMANCE INSIGHTS" :level 2 :color :magenta))
        (cond
          (> avg-time-between-ops 5000) ; 5 seconds
          (println (ansi-color :red "🐌 Slow operation rate - consider batching operations"))

          (> (count (filter #(= % :undo) operations)) (* 0.3 (count operations)))
          (println (ansi-color :yellow "🔄 High undo frequency - users may be experimenting"))

          (some #(= % :error) (map :level (:log-history db)))
          (println (ansi-color :red "❌ Errors detected - check system health"))

          :else
          (println (ansi-color :green "✅ Performance looks healthy")))))))

(defn interactive-diagnostics
  "Interactive diagnostic workflow with guided troubleshooting"
  []
  (println (format-header "🔧 INTERACTIVE DIAGNOSTICS" :level 1 :color :blue))

  (let [db @(requiring-resolve 'evolver.kernel/db)
        issues-found (atom [])]

    ;; Health checks
    (println (format-header "🏥 SYSTEM HEALTH CHECKS" :level 2 :color :green))

    ;; Schema validation
    (try
      ((requiring-resolve 'evolver.schemas/validate-db) db)
      (println (ansi-color :green "✅ Schema validation passed"))
      (catch Exception e
        (swap! issues-found conj {:type :schema :severity :critical :message (str "Schema validation failed: " (.getMessage e))})))

    ;; Reference integrity
    (let [invalid-refs ((requiring-resolve 'agent.reference_tools/find-orphaned-references))]
      (if (seq invalid-refs)
        (do
          (println (ansi-color :red (str "❌ Found " (count invalid-refs) " orphaned references")))
          (swap! issues-found conj {:type :references :severity :high :message (str "Orphaned references: " invalid-refs)}))
        (println (ansi-color :green "✅ Reference integrity OK"))))

    ;; Node consistency
    (let [orphan-nodes (filter #(and (not= % "root") (not (contains? (:children-by-parent db) %))) (keys (:nodes db)))]
      (if (seq orphan-nodes)
        (do
          (println (ansi-color :red (str "❌ Found " (count orphan-nodes) " orphan nodes")))
          (swap! issues-found conj {:type :nodes :severity :medium :message (str "Orphan nodes: " orphan-nodes)}))
        (println (ansi-color :green "✅ Node consistency OK"))))

    ;; Error logs
    (let [errors (filter #(= (:level %) :error) (:log-history db))]
      (if (seq errors)
        (do
          (println (ansi-color :red (str "❌ Found " (count errors) " error log entries")))
          (swap! issues-found conj {:type :logs :severity :medium :message (str (count errors) " errors in log history")}))
        (println (ansi-color :green "✅ Error logs clean"))))

    ;; Issue analysis and recommendations
    (when (seq @issues-found)
      (println "\n" (format-header "🔍 ISSUE ANALYSIS" :level 2 :color :yellow))

      (doseq [issue @issues-found]
        (println (str (case (:severity issue)
                        :critical "🚨 CRITICAL"
                        :high "⚠️  HIGH"
                        :medium "ℹ️  MEDIUM"
                        :low "ℹ️  LOW")
                     " - " (:message issue))))

      ;; Interactive troubleshooting
      (when (:interactive-mode *explorer-config*)
        (println "\n" (format-header "🛠️ TROUBLESHOOTING OPTIONS" :level 2 :color :cyan))
        (println "Available actions:")
        (println "• 'fix-refs' - Attempt to fix orphaned references")
        (println "• 'clean-nodes' - Remove orphan nodes")
        (println "• 'clear-logs' - Clear error logs")
        (println "• 'deep-analysis' - Run comprehensive analysis")
        (println "• 'quit' - Exit diagnostics")

        (loop []
          (when-let [choice (prompt-user "Choose an action" nil)]
            (case choice
              "fix-refs"
              (println (ansi-color :yellow "🔧 Attempting to fix references..."))
              ;; TODO: Implement reference fixing logic

              "clean-nodes"
              (println (ansi-color :yellow "🧹 Cleaning orphan nodes..."))
              ;; TODO: Implement node cleaning logic

              "clear-logs"
              (println (ansi-color :yellow "📝 Clearing error logs..."))
              ;; TODO: Implement log clearing logic

              "deep-analysis"
              (analyze-store-comprehensive)

              "quit" nil

              (println (ansi-color :red "Unknown action")))
            (when (not= choice "quit")
              (recur))))))

    ;; Summary
    (println "\n" (format-header "📋 DIAGNOSTICS SUMMARY" :level 2 :color :magenta))
    (let [issue-count (count @issues-found)
          critical-count (count (filter #(= (:severity %) :critical) @issues-found))]
      (println (format-key-value "Issues Found" issue-count))
      (println (format-key-value "Critical Issues" critical-count))
      (println (format-key-value "Overall Health" (if (= issue-count 0)
                                                    (ansi-color :green "EXCELLENT")
                                                    (ansi-color :red "NEEDS ATTENTION")))))))

;; Tool composition and chaining
(defn chain-analysis
  "Chain multiple analysis functions together with data flow"
  [& analysis-fns]
  (fn [& args]
    (reduce (fn [result analysis-fn]
              (analysis-fn result))
            args
            analysis-fns)))

(defn create-analysis-pipeline
  "Create a reusable analysis pipeline"
  [name & steps]
  {:name name
   :steps steps
   :run (fn [& args]
          (println (format-header (str "🚀 RUNNING PIPELINE: " name) :level 1 :color :magenta))
          (let [start-time (System/currentTimeMillis)]
            (try
              (let [result (reduce (fn [acc step]
                                     (println (ansi-color :cyan (str "→ " (:name step)))))
                                   args
                                   steps)
                    end-time (System/currentTimeMillis)]
                (println (ansi-color :green (str "✅ Pipeline completed in " (- end-time start-time) "ms")))
                result)
              (catch Exception e
                (println (ansi-color :red (str "❌ Pipeline failed: " (.getMessage e))))
                (throw e)))))})

;; Session persistence
(def sessions (atom {}))

(defn save-session
  "Save current analysis session"
  [session-name]
  (let [session-data {:timestamp (System/currentTimeMillis)
                      :config *explorer-config*
                      :db-snapshot @(requiring-resolve 'evolver.kernel/db)}]
    (swap! sessions assoc session-name session-data)
    (println (ansi-color :green (str "💾 Session '" session-name "' saved")))))

(defn load-session
  "Load a saved analysis session"
  [session-name]
  (if-let [session (get @sessions session-name)]
    (do
      (configure! (:config session))
      (println (ansi-color :green (str "📂 Session '" session-name "' loaded")))
      session)
    (println (ansi-color :red (str "❌ Session '" session-name "' not found")))))

(defn list-sessions
  "List all saved sessions"
  []
  (if (seq @sessions)
    (println (format-table ["Session Name" "Saved At" "Config"]
                          (mapv (fn [[name data]]
                                  [name
                                   (java.util.Date. (:timestamp data))
                                   (str (:output-format (:config data)) ", "
                                        (if (:interactive-mode (:config data)) "interactive" "batch"))])
                                @sessions)
                          :alignments [:left :left :left]))
    (println (ansi-color :yellow "No saved sessions"))))

;; Monitoring system
(def monitors (atom {}))

(defn create-monitor
  "Create an automated monitor for system health"
  [name check-fn interval-ms & {:keys [alert-threshold]}]
  (let [monitor {:name name
                 :check-fn check-fn
                 :interval-ms interval-ms
                 :alert-threshold alert-threshold
                 :last-run nil
                 :status :stopped
                 :history []}]
    (swap! monitors assoc name monitor)
    (println (ansi-color :green (str "📊 Monitor '" name "' created")))))

(defn start-monitor
  "Start a monitor"
  [name]
  (when-let [monitor (get @monitors name)]
    (swap! monitors assoc-in [name :status] :running)
    (println (ansi-color :green (str "▶️  Monitor '" name "' started")))))

(defn stop-monitor
  "Stop a monitor"
  [name]
  (when-let [monitor (get @monitors name)]
    (swap! monitors assoc-in [name :status] :stopped)
    (println (ansi-color :green (str "⏹️  Monitor '" name "' stopped")))))

(defn get-monitor-status
  "Get status of all monitors"
  []
  (println (format-header "📊 MONITOR STATUS" :level 1 :color :blue))
  (if (seq @monitors)
    (println (format-table ["Monitor" "Status" "Last Run" "Interval"]
                          (mapv (fn [[name monitor]]
                                  [name
                                   (case (:status monitor)
                                     :running (ansi-color :green "RUNNING")
                                     :stopped (ansi-color :red "STOPPED")
                                     (ansi-color :yellow "UNKNOWN"))
                                   (if (:last-run monitor)
                                     (java.util.Date. (:last-run monitor))
                                     "Never")
                                   (str (:interval-ms monitor) "ms")])
                                @monitors)
                          :alignments [:left :center :left :right]))
    (println (ansi-color :yellow "No monitors configured"))))

;; Context-aware help system
(defn suggest-tools
  "Suggest appropriate tools based on current context"
  [context]
  (let [suggestions (cond
                      (= context :error-debugging)
                      ["analyze-store-comprehensive" "interactive-diagnostics" "debug-helpers/scan-for-errors"]

                      (= context :performance-analysis)
                      ["analyze-performance-trends" "store-inspector/performance-metrics" "repl-workflow/test-operations-batch"]

                      (= context :reference-investigation)
                      ["reference-tools/inspect-references" "reference-tools/validate-reference-integrity" "analyze-store-comprehensive"]

                      (= context :development-workflow)
                      ["dev-tools/cljs-repl" "dev-tools/test-reference-ui" "dev-tools/keyboard-health-check"]

                      :else
                      ["analyze-store-comprehensive" "interactive-diagnostics" "get-monitor-status"])]

    (println (format-header "💡 SUGGESTED TOOLS" :level 2 :color :cyan))
    (println (str "For " (name context) ":"))
    (println (format-list suggestions :bullet true))))

(defn help
  "Context-aware help system"
  [& {:keys [topic]}]
  (println (format-header "🆘 ENHANCED EXPLORER HELP" :level 1 :color :cyan))

  (cond
    (= topic :analysis)
    (do
      (println "📊 ANALYSIS FUNCTIONS:")
      (println "• analyze-store-comprehensive - Full system analysis with rich formatting")
      (println "• analyze-performance-trends - Performance metrics and trends")
      (println "• interactive-diagnostics - Guided troubleshooting workflow"))

    (= topic :monitoring)
    (do
      (println "📊 MONITORING SYSTEM:")
      (println "• create-monitor - Set up automated health checks")
      (println "• start-monitor/stop-monitor - Control monitors")
      (println "• get-monitor-status - View monitor status"))

    (= topic :sessions)
    (do
      (println "💾 SESSION MANAGEMENT:")
      (println "• save-session - Save current analysis state")
      (println "• load-session - Restore saved session")
      (println "• list-sessions - View saved sessions"))

    (= topic :configuration)
    (do
      (println "⚙️ CONFIGURATION:")
      (println "• configure! - Change explorer behavior")
      (println "• Current config:" *explorer-config*))

    :else
    (do
      (println "🎯 QUICK START:")
      (println "• (analyze-store-comprehensive) - Get started with comprehensive analysis")
      (println "• (interactive-diagnostics) - Run guided diagnostics")
      (println "• (help :topic :analysis) - Learn about analysis tools")
      (println "• (suggest-tools :performance-analysis) - Get tool suggestions")

      (println "\n📋 AVAILABLE TOPICS:")
      (println "• :analysis - Analysis and exploration tools")
      (println "• :monitoring - Automated monitoring system")
      (println "• :sessions - Session persistence")
      (println "• :configuration - Configuration options"))))

;; Export main functions
(def explore analyze-store-comprehensive)
(def diagnose interactive-diagnostics)
(def trends analyze-performance-trends)