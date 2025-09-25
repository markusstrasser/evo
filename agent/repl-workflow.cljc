;; REPL workflow optimizer for efficient testing
;; Provides batch operations and concise reporting

(ns agent.repl-workflow
  (:require [agent.debug-helpers :as debug]
            [agent.code-analysis :as analysis]))

;; Batch operation tester with automatic validation
(defn test-operations-batch
  "Test multiple operations in batch with optional validation and profiling.

  Args:
    operations: Sequence of operation maps
    validate?: Boolean, whether to validate final db state
    profile?: Boolean, whether to include performance metrics

  Returns:
    Map with :summary, :avg-time, and optionally :failures, :validation, :performance."
  [operations & {:keys [validate? profile?]}]
  (let [start-db (requiring-resolve 'evolver.kernel/db)
        results (mapv (fn [op]
                       (let [op-start (System/currentTimeMillis)
                             result (try
                                     ((requiring-resolve 'evolver.kernel/safe-apply-command) @start-db op)
                                     (catch Exception e {:error (str e)}))
                             op-end (System/currentTimeMillis)]
                         {:operation op
                          :result result
                          :duration (- op-end op-start)
                          :success? (and (map? result) (not (:error result)))}))
                     operations)

        summary {:total (count operations)
                :successful (count (filter :success? results))
                :failed (count (remove :success? results))
                :avg-duration (if (seq results)
                               (/ (reduce + (map :duration results)) (count results))
                               0)}

        validation (when validate?
                    (let [final-db (:result (last (filter :success? results)))]
                      (when final-db
                        (debug/scan-for-errors final-db))))

        profile (when profile?
                 {:total-time (reduce + (map :duration results))
                  :slowest (apply max (map :duration results))
                  :fastest (apply min (map :duration results))})]

    ;; Return concise result
    (cond-> {:summary (str (:successful summary) "/" (:total summary) " ops successful")
            :avg-time (str (:avg-duration summary) "ms/op")}

      (> (:failed summary) 0)
      (assoc :failures (mapv #(select-keys % [:operation :error]) (remove :success? results)))

      validate?
      (assoc :validation validation)

      profile?
      (assoc :performance profile))))

;; Quick system health check
(defn health-check
  "Perform a comprehensive health check on the current system state.

  Returns:
    Map with :healthy?, :issues, :schema-valid?, :node-count, :tx-count."
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)
        consistency (debug/check-consistency db)
        errors (debug/scan-for-errors db)
         schema-valid? (try
                        ((requiring-resolve 'evolver.schemas/validate-db) db)
                        (catch Exception e
                          (log-message db :error "Schema validation failed" {:error (.getMessage e)})
                          false))]
    {:healthy? (and (:consistent? consistency)
                   (:healthy? errors)
                   schema-valid?)
     :issues (concat (:issues consistency) (:issues errors))
     :schema-valid? schema-valid?
     :node-count (count (:nodes db))
     :tx-count (count (:tx-log db))}))

;; Workflow: test → validate → report
(defn test-workflow
  "Run a workflow of test functions and report results.

  Args:
    test-fns: Sequence of test functions (each taking a context map)

  Returns:
    Map with :status (:success/:failed), :passed, :total, :details."
  [test-fns]
  (let [results (mapv #(% {}) test-fns)
        all-passed? (every? :success? results)]
    {:status (if all-passed? :success :failed)
     :passed (count (filter :success? results))
     :total (count results)
     :details (mapv #(select-keys % [:name :success? :duration]) results)}))

;; Example usage:
;; (test-operations-batch
;;   [{:op :insert :parent-id "root" :node-id "test1" :node-data {:type :div :props {:text "Test"}} :position 0}
;;    {:op :undo}
;;    {:op :redo}]
;;   :validate? true :profile? true)