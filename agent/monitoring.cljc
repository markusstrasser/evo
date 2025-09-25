;; Automated Monitoring and Alerting System
;; Provides continuous health monitoring and issue detection

(ns agent.monitoring
  "Automated monitoring system for system health and performance.

  Features:
  - Continuous health monitoring
  - Configurable alert thresholds
  - Performance trend analysis
  - Automated issue detection
  - Historical monitoring data
  - Alert escalation and notifications"
  (:require [agent.enhanced-explorer :as explorer]
            [agent.configuration :as config]
            [clojure.string :as str]))

;; Monitoring state
(def ^:private monitors (atom {}))
(def ^:private alerts (atom []))
(def ^:private monitoring-history (atom []))
(def ^:private monitoring-thread (atom nil))

;; Monitor types and their check functions
(def monitor-types
  {:system-health
   {:description "Overall system health check"
    :check-fn (fn []
                (let [db @(requiring-resolve 'evolver.kernel/db)
                      errors (filter #(= (:level %) :error) (:log-history db))
                      orphan-nodes (filter #(and (not= % "root")
                                                 (not (contains? (:children-by-parent db) %)))
                                           (keys (:nodes db)))
                      invalid-refs (remove #(contains? (:nodes db) %)
                                           (keys (:references db)))]
                  {:healthy? (and (empty? errors) (empty? orphan-nodes) (empty? invalid-refs))
                   :error-count (count errors)
                   :orphan-nodes (count orphan-nodes)
                   :invalid-refs (count invalid-refs)
                   :node-count (count (:nodes db))
                   :tx-count (count (:tx-log db))}))}

   :performance-metrics
   {:description "Performance metrics monitoring"
    :check-fn (fn []
                (let [db @(requiring-resolve 'evolver.kernel/db)
                      tx-log (:tx-log db)
                      recent-txs (take-last 10 tx-log)]
                  {:node-count (count (:nodes db))
                   :tx-count (count tx-log)
                   :recent-tx-rate (if (>= (count tx-log) 2)
                                     (/ (- (:timestamp (last tx-log))
                                           (:timestamp (first recent-txs)))
                                        (max 1 (- (count recent-txs) 1)))
                                     0)
                   :memory-estimate (* (+ (count (:nodes db))
                                          (count (:tx-log db))
                                          (count (:references db))) 100)
                   :ref-density (if (zero? (count (:nodes db)))
                                  0
                                  (/ (reduce + (map count (vals (:references db))))
                                     (count (:nodes db))))}))}

   :reference-integrity
   {:description "Reference system integrity"
    :check-fn (fn []
                (let [db @(requiring-resolve 'evolver.kernel/db)
                      references (:references db)
                      nodes (:nodes db)
                      orphaned-refs (remove #(contains? nodes %) (keys references))
                      broken-refs (filter #(some (complement nodes) (val %)) references)]
                  {:total-references (count references)
                   :orphaned-references (count orphaned-refs)
                   :broken-references (count broken-refs)
                   :healthy? (and (empty? orphaned-refs) (empty? broken-refs))}))}

   :operation-success-rate
   {:description "Operation success rate monitoring"
    :check-fn (fn []
                (let [db @(requiring-resolve 'evolver.kernel/db)
                      tx-log (:tx-log db)
                      recent-txs (take-last 20 tx-log)
                      error-logs (filter #(= (:level %) :error) (:log-history db))
                      recent-errors (filter #(>= (:timestamp %)
                                                 (:timestamp (first recent-txs)))
                                            error-logs)]
                  {:total-operations (count recent-txs)
                   :error-count (count recent-errors)
                   :success-rate (if (zero? (count recent-txs))
                                   1.0
                                   (- 1.0 (/ (count recent-errors) (count recent-txs))))
                   :healthy? (>= (- 1.0 (/ (count recent-errors) (max 1 (count recent-txs)))) 0.95)}))}})

;; Alert system
(defn- create-alert
  "Create an alert for a monitoring issue"
  [monitor-name severity message & {:keys [data]}]
  {:id (str (random-uuid))
   :monitor monitor-name
   :severity severity
   :message message
   :timestamp (System/currentTimeMillis)
   :data data
   :acknowledged? false})

(defn- check-alert-thresholds
  "Check if monitor results trigger alerts"
  [monitor-name results thresholds]
  (let [alerts (atom [])]
    (doseq [[metric threshold-config] thresholds]
      (when-let [value (get results metric)]
        (cond
          (and (:max threshold-config) (> value (:max threshold-config)))
          (swap! alerts conj (create-alert monitor-name :high
                                           (str metric " exceeded maximum threshold: " value " > " (:max threshold-config))
                                           :data {:metric metric :value value :threshold (:max threshold-config)}))

          (and (:min threshold-config) (< value (:min threshold-config)))
          (swap! alerts conj (create-alert monitor-name :medium
                                           (str metric " below minimum threshold: " value " < " (:min threshold-config))
                                           :data {:metric metric :value value :threshold (:min threshold-config)}))

          (and (:equals threshold-config) (not= value (:equals threshold-config)))
          (swap! alerts conj (create-alert monitor-name :low
                                           (str metric " not equal to expected value: " value " != " (:equals threshold-config))
                                           :data {:metric metric :value value :expected (:equals threshold-config)})))))
    @alerts))

(defn- record-monitoring-data
  "Record monitoring results in history"
  [monitor-name results]
  (let [entry {:timestamp (System/currentTimeMillis)
               :monitor monitor-name
               :results results}]
    (swap! monitoring-history conj entry)
    ;; Keep only last 1000 entries
    (when (> (count @monitoring-history) 1000)
      (reset! monitoring-history (vec (take-last 1000 @monitoring-history))))))

;; Monitor management
(defn create-monitor
  "Create a new monitor"
  [name type & {:keys [interval-ms thresholds enabled?]}]
  (if-let [monitor-type (monitor-types type)]
    (let [monitor {:name name
                   :type type
                   :description (:description monitor-type)
                   :check-fn (:check-fn monitor-type)
                   :interval-ms (or interval-ms 30000) ; 30 seconds default
                   :thresholds (or thresholds {})
                   :enabled? (if (some? enabled?) enabled? true)
                   :last-run nil
                   :last-result nil
                   :run-count 0}]
      (swap! monitors assoc name monitor)
      (println (explorer/ansi-color :green (str "📊 Monitor '" name "' created (" (:description monitor-type) ")")))
      monitor)
    (do
      (println (explorer/ansi-color :red (str "❌ Unknown monitor type: " type)))
      (println (explorer/ansi-color :yellow "Available types:"))
      (println (explorer/format-list (keys monitor-types) :bullet true))
      nil)))

(defn start-monitor
  "Start a monitor"
  [name]
  (when-let [monitor (get @monitors name)]
    (swap! monitors assoc-in [name :enabled?] true)
    (println (explorer/ansi-color :green (str "▶️  Monitor '" name "' started")))))

(defn stop-monitor
  "Stop a monitor"
  [name]
  (when-let [monitor (get @monitors name)]
    (swap! monitors assoc-in [name :enabled?] false)
    (println (explorer/ansi-color :green (str "⏹️  Monitor '" name "' stopped")))))

(defn delete-monitor
  "Delete a monitor"
  [name]
  (if (contains? @monitors name)
    (do
      (swap! monitors dissoc name)
      (println (explorer/ansi-color :green (str "🗑️  Monitor '" name "' deleted"))))
    (println (explorer/ansi-color :red (str "❌ Monitor '" name "' not found")))))

(defn run-monitor
  "Run a monitor manually"
  [name]
  (if-let [monitor (get @monitors name)]
    (try
      (let [results ((:check-fn monitor))
            new-alerts (check-alert-thresholds name results (:thresholds monitor))]
        (swap! monitors update name
               assoc :last-run (System/currentTimeMillis)
                     :last-result results
                     :run-count (inc (:run-count monitor)))
        (record-monitoring-data name results)
        (doseq [alert new-alerts]
          (swap! alerts conj alert))

        (println (explorer/ansi-color :green (str "✅ Monitor '" name "' completed")))
        (when (seq new-alerts)
          (println (explorer/ansi-color :red (str "🚨 " (count new-alerts) " alerts generated"))))
        results)
      (catch Exception e
        (println (explorer/ansi-color :red (str "❌ Monitor '" name "' failed: " (.getMessage e))))
        (swap! alerts conj (create-alert name :critical
                                         (str "Monitor execution failed: " (.getMessage e))
                                         :data {:error (.getMessage e)}))
        nil))
    (do
      (println (explorer/ansi-color :red (str "❌ Monitor '" name "' not found")))
      nil)))

;; Automated monitoring loop
(defn- monitoring-loop
  "Main monitoring loop"
  []
  (while (and @monitoring-thread (config/get-config :monitoring-enabled))
    (try
      (doseq [[name monitor] @monitors]
        (when (:enabled? monitor)
          (let [now (System/currentTimeMillis)
                last-run (:last-run monitor)]
            (when (or (nil? last-run)
                      (>= (- now last-run) (:interval-ms monitor)))
              (run-monitor name)))))
      (Thread/sleep 5000) ; Check every 5 seconds
      (catch Exception e
        (println (explorer/ansi-color :red (str "❌ Monitoring loop error: " (.getMessage e))))
        (Thread/sleep 10000))))) ; Wait longer on error

(defn start-monitoring
  "Start the automated monitoring system"
  []
  (if (config/get-config :monitoring-enabled)
    (if @monitoring-thread
      (println (explorer/ansi-color :yellow "⚠️  Monitoring already running"))
      (do
        (reset! monitoring-thread (Thread. monitoring-loop))
        (.setName @monitoring-thread "agent-monitoring")
        (.setDaemon @monitoring-thread true)
        (.start @monitoring-thread)
        (println (explorer/ansi-color :green "🚀 Automated monitoring started"))))
    (println (explorer/ansi-color :red "❌ Monitoring disabled in configuration"))))

(defn stop-monitoring
  "Stop the automated monitoring system"
  []
  (when @monitoring-thread
    (reset! monitoring-thread nil)
    (println (explorer/ansi-color :green "⏹️  Automated monitoring stopped"))))

;; Alert management
(defn get-alerts
  "Get current alerts"
  [& {:keys [acknowledged? severity]}]
  (let [filtered-alerts (cond->> @alerts
                          (some? acknowledged?) (filter #(= (:acknowledged? %) acknowledged?))
                          severity (filter #(= (:severity %) severity)))]
    filtered-alerts))

(defn acknowledge-alert
  "Acknowledge an alert"
  [alert-id]
  (if-let [alert-index (first (keep-indexed #(when (= (:id %2) alert-id) %1) @alerts))]
    (do
      (swap! alerts assoc-in [alert-index :acknowledged?] true)
      (println (explorer/ansi-color :green (str "✅ Alert '" alert-id "' acknowledged"))))
    (println (explorer/ansi-color :red (str "❌ Alert '" alert-id "' not found")))))

(defn clear-alerts
  "Clear alerts based on criteria"
  [& {:keys [acknowledged? severity older-than]}]
  (let [to-remove (cond->> @alerts
                    (some? acknowledged?) (filter #(= (:acknowledged? %) acknowledged?))
                    severity (filter #(= (:severity %) severity))
                    older-than (filter #(< (:timestamp %) (- (System/currentTimeMillis) older-than))))]
    (when (seq to-remove)
      (reset! alerts (remove (set to-remove) @alerts))
      (println (explorer/ansi-color :green (str "🧹 Cleared " (count to-remove) " alerts"))))))

;; Status and reporting
(defn monitoring-status
  "Get monitoring system status"
  []
  (println (explorer/format-header "📊 MONITORING SYSTEM STATUS" :level 1 :color :cyan))

  (println (explorer/format-header "📈 MONITORS" :level 2 :color :blue))
  (if (seq @monitors)
    (println (explorer/format-table ["Monitor" "Type" "Status" "Last Run" "Run Count"]
                                    (mapv (fn [[name monitor]]
                                            [name
                                             (:type monitor)
                                             (if (:enabled? monitor)
                                               (explorer/ansi-color :green "ENABLED")
                                               (explorer/ansi-color :red "DISABLED"))
                                             (if (:last-run monitor)
                                               (java.util.Date. (:last-run monitor))
                                               "Never")
                                             (:run-count monitor)])
                                          @monitors)
                                    :alignments [:left :left :center :left :right]))
    (println (explorer/ansi-color :yellow "No monitors configured")))

  (println "\n" (explorer/format-header "🚨 ALERTS" :level 2 :color :red))
  (let [active-alerts (get-alerts :acknowledged? false)]
    (if (seq active-alerts)
      (do
        (println (explorer/format-key-value "Active Alerts" (count active-alerts)))
        (doseq [alert (take 5 active-alerts)]
          (println (str "• " (case (:severity alert)
                               :critical (explorer/ansi-color :red "🚨 CRITICAL")
                               :high (explorer/ansi-color :red "⚠️  HIGH")
                               :medium (explorer/ansi-color :yellow "⚠️  MEDIUM")
                               :low (explorer/ansi-color :blue "ℹ️  LOW"))
                     " [" (:monitor alert) "] " (:message alert)))))
      (println (explorer/ansi-color :green "✅ No active alerts"))))

  (println "\n" (explorer/format-header "📋 SYSTEM INFO" :level 2 :color :green))
  (println (explorer/format-key-value "Monitoring Thread" (if @monitoring-thread "Running" "Stopped")))
  (println (explorer/format-key-value "History Entries" (count @monitoring-history)))
  (println (explorer/format-key-value "Total Alerts" (count @alerts))))

(defn monitoring-report
  "Generate a comprehensive monitoring report"
  []
  (println (explorer/format-header "📊 COMPREHENSIVE MONITORING REPORT" :level 1 :color :magenta))

  ;; Current status
  (println (explorer/format-header "📈 CURRENT STATUS" :level 2 :color :cyan))
  (doseq [[name monitor] @monitors]
    (when (:last-result monitor)
      (println (str (explorer/ansi-color :yellow name) ":"))
      (println (explorer/format-key-value "Healthy" (get (:last-result monitor) :healthy? false) :indent 2))
      (doseq [[k v] (dissoc (:last-result monitor) :healthy?)]
        (println (explorer/format-key-value k v :indent 2)))))

  ;; Trends analysis
  (println "\n" (explorer/format-header "📉 TRENDS ANALYSIS" :level 2 :color :yellow))
  (let [recent-history (take-last 20 @monitoring-history)]
    (if (>= (count recent-history) 2)
      (let [grouped-by-monitor (group-by :monitor recent-history)]
        (doseq [[monitor-name entries] grouped-by-monitor]
          (println (str (explorer/ansi-color :cyan monitor-name) " trends:"))
          ;; Simple trend analysis - could be much more sophisticated
          (let [first-entry (first entries)
                last-entry (last entries)
                time-span (- (:timestamp last-entry) (:timestamp first-entry))]
            (println (explorer/format-key-value "Time Span" (str (format "%.1f" (/ time-span 1000.0)) "s") :indent 2))
            (println (explorer/format-key-value "Data Points" (count entries) :indent 2)))))
      (println (explorer/ansi-color :yellow "Insufficient historical data for trend analysis"))))

  ;; Recommendations
  (println "\n" (explorer/format-header "💡 RECOMMENDATIONS" :level 2 :color :green))
  (let [active-alerts (get-alerts :acknowledged? false)]
    (cond
      (seq active-alerts)
      (println (explorer/ansi-color :red "• Address active alerts immediately"))

      (empty? @monitors)
      (println (explorer/ansi-color :yellow "• Consider setting up monitors for critical system components"))

      (not (config/get-config :monitoring-enabled))
      (println (explorer/ansi-color :yellow "• Enable automated monitoring for continuous health checks"))

      :else
      (println (explorer/ansi-color :green "• System monitoring is properly configured")))))

;; Pre-configured monitor templates
(defn setup-basic-monitoring
  "Set up basic monitoring for common issues"
  []
  (println (explorer/ansi-color :blue "Setting up basic monitoring..."))
  (create-monitor "system-health" :system-health
                  :interval-ms 60000  ; Every minute
                  :thresholds {:error-count {:max 5}
                              :orphan-nodes {:max 0}
                              :invalid-refs {:max 0}})
  (create-monitor "performance" :performance-metrics
                  :interval-ms 300000  ; Every 5 minutes
                  :thresholds {:memory-estimate {:max 10000000}}) ; 10MB
  (create-monitor "references" :reference-integrity
                  :interval-ms 120000  ; Every 2 minutes
                  :thresholds {:orphaned-references {:max 0}
                              :broken-references {:max 0}})
  (println (explorer/ansi-color :green "✅ Basic monitoring setup complete")))

;; Export main functions
(def create create-monitor)
(def start start-monitor)
(def stop stop-monitor)
(def run run-monitor)
(def status monitoring-status)
(def report monitoring-report)
(def alerts get-alerts)
(def acknowledge acknowledge-alert)
(def clear clear-alerts)
(def setup setup-basic-monitoring)