(ns evolver.middleware
  (:require [evolver.kernel :as kernel]
            [evolver.schemas :as schemas]))

(defn run-pipeline
  "Execute pipeline with early exit on error"
  [initial-ctx steps]
  (reduce
    (fn [ctx step-fn]
      (let [next-ctx (step-fn ctx)]
        (if (seq (:errors next-ctx))
          (reduced next-ctx)
          next-ctx)))
    initial-ctx
    steps))

(defn validate-cmd-step
  "Validate command structure"
  [ctx]
  (let [cmd (:cmd ctx)]
    (try
      (schemas/validate-command cmd)
      ctx
      (catch js/Error e
        (assoc ctx :errors [(str "Command validation failed: " (.-message e))])))))

(defn log-before-step
  "Log command before execution"
  [ctx]
  (js/console.log "🔄 Executing command:" (pr-str (:cmd ctx)))
  ctx)

(defn apply-command-step
  "Apply the command"
  [ctx]
  (try
    (let [new-db (kernel/execute-command (:db ctx) (:cmd ctx))]
      (assoc ctx :db new-db))
    (catch js/Error e
      (assoc ctx :errors [(str "Command execution failed: " (.-message e))]))))

(defn validate-result-step
  "Validate database state after operation"
  [ctx]
  (try
    (kernel/validate-db-state (:db ctx))
    ctx
    (catch js/Error e
      (js/console.error "❌ Database validation failed after command:" (:cmd ctx))
      (js/console.error "Validation error:" (.-message e))
      (assoc ctx :errors [(str "Database validation failed: " (.-message e))]))))

(defn update-derived-step
  "Update derived state"
  [ctx]
  (let [updated-db (kernel/update-derived (:db ctx))]
    (assoc ctx :db updated-db)))

(defn operation-log-step
  "Add operation to history (skip for undo/redo)"
  [ctx]
  (let [cmd (:cmd ctx)]
    (if (#{:undo :redo} (:op cmd))
      ctx
      (let [logged-db (kernel/log-operation (:db ctx) cmd)]
        (assoc ctx :db logged-db)))))

(defn log-after-step
  "Log successful command execution"
  [ctx]
  (js/console.log "✅ Command completed:" (:op (:cmd ctx)))
  ctx)

(def pipeline-steps
  "Definitive pipeline"
  [validate-cmd-step
   log-before-step
   apply-command-step
   validate-result-step
   update-derived-step
   operation-log-step
   log-after-step])

(defn safe-apply-command-with-middleware
  "Apply command through the context pipeline"
  [db command]
  (let [initial-ctx {:db db :cmd command :log [] :errors [] :effects []}
        final-ctx (run-pipeline initial-ctx pipeline-steps)]
    (if (seq (:errors final-ctx))
      (do
        (js/console.error "Pipeline failed with errors:" (:errors final-ctx))
        db) ; Return original db on error
      (:db final-ctx))))