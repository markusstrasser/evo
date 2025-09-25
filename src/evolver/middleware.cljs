(ns evolver.middleware
  (:require [evolver.kernel :as kernel]
            [evolver.schemas :as schemas]))

(defn validate-command-middleware
  "Validate command structure before execution"
  [command db]
  (when-not (map? command)
    (throw (js/Error. (str "Command must be a map, got: " (type command)))))
  (when-not (:op command)
    (throw (js/Error. (str "Command must have :op key, got: " command))))
  [command db])

(defn log-before-middleware
  "Log command before execution"
  [command db]
  (js/console.log "🔄 Executing command:" (pr-str command))
  [command db])

(defn apply-operation-middleware
  "Apply the actual operation"
  [command db]
  (let [result (kernel/apply-command db command)]
    [command result]))

(defn validate-result-middleware
  "Validate database state after operation"
  [command db]
  (try
    (kernel/validate-db-state db)
    [command db]
    (catch js/Error e
      (js/console.error "❌ Database validation failed after command:" command)
      (js/console.error "Validation error:" (.-message e))
      (throw e))))

(defn log-after-middleware
  "Log successful command execution"
  [command db]
  (js/console.log "✅ Command completed:" (:op command))
  [command db])

(defn update-derived-middleware
  "Update derived state after operation"
  [command db]
  (let [updated-db (kernel/update-derived db)]
    [command updated-db]))

(defn operation-log-middleware
  "Add operation to history for undo/redo"
  [command db]
  (let [logged-db (kernel/log-operation db command)]
    [command logged-db]))

(def operation-pipeline
  "Complete operation pipeline"
  [validate-command-middleware
   log-before-middleware
   apply-operation-middleware
   validate-result-middleware
   update-derived-middleware
   operation-log-middleware
   log-after-middleware])

(defn execute-pipeline
  "Execute command through the middleware pipeline"
  [command db pipeline]
  (try
    (loop [middlewares pipeline
           current-command command
           current-db db]
      (if-let [middleware (first middlewares)]
        (let [[next-command next-db] (middleware current-command current-db)]
          (recur (rest middlewares) next-command next-db))
        current-db))
    (catch js/Error e
      (js/console.error "❌ Pipeline execution failed:" (.-message e))
      (js/console.error "Command:" command)
      (js/console.error "Error details:" e)
      ;; Return original db on error to maintain consistency
      db)))

(defn safe-apply-command-with-middleware
  "Apply command through the complete pipeline safely"
  [db command]
  (execute-pipeline command db operation-pipeline))