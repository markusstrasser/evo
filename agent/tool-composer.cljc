;; Tool Composition and Chaining System
;; Enables powerful workflows by combining analysis tools

(ns agent.tool-composer
  "Tool composition system for chaining analysis operations.

  Provides:
  - Function composition for analysis pipelines
  - Data flow between tools
  - Conditional execution
  - Error handling and recovery
  - Result aggregation and transformation"
  (:require [agent.enhanced-explorer :as explorer]
            [clojure.string :as str]))

;; Core composition primitives
(defn ->>
  "Thread-last macro for tool composition"
  [x & forms]
  (loop [x x, forms forms]
    (if forms
      (let [form (first forms)
            threaded (if (seq? form)
                       (with-meta `(~(first form) ~@(next form) ~x) (meta form))
                       (list form x))]
        (recur threaded (next forms)))
      x)))

(defn ->>
  "Thread-first macro for tool composition"
  [x & forms]
  (loop [x x, forms forms]
    (if forms
      (let [form (first forms)
            threaded (if (seq? form)
                       (with-meta `(~(first form) ~x ~@(next form)) (meta form))
                       (list form x))]
        (recur threaded (next forms)))
      x)))

;; Pipeline construction
(defn pipeline
  "Create a composable analysis pipeline"
  [& steps]
  {:type :pipeline
   :steps steps
   :execute (fn [& args]
              (reduce (fn [result step]
                        (let [step-fn (if (map? step) (:fn step) step)
                              step-args (if (map? step) (:args step) [])]
                          (apply step-fn result step-args)))
                      args
                      steps))})

(defn conditional-pipeline
  "Create a pipeline that executes conditionally"
  [condition-fn then-pipeline else-pipeline]
  {:type :conditional-pipeline
   :condition condition-fn
   :then then-pipeline
   :else else-pipeline
   :execute (fn [& args]
              (let [condition-result (apply condition-fn args)]
                (if condition-result
                  (apply (:execute then-pipeline) args)
                  (apply (:execute else-pipeline) args))))})

(defn parallel-pipeline
  "Execute multiple pipelines in parallel"
  [& pipelines]
  {:type :parallel-pipeline
   :pipelines pipelines
   :execute (fn [& args]
              (pmap #(apply (:execute %) args) pipelines))})

;; Data transformation utilities
(defn transform-result
  "Transform pipeline results using a function"
  [transform-fn]
  {:type :transformer
   :transform transform-fn
   :execute (fn [& args]
              (apply transform-fn args))})

(defn aggregate-results
  "Aggregate multiple results into a single result"
  [aggregation-fn]
  {:type :aggregator
   :aggregate aggregation-fn
   :execute (fn [& results]
              (apply aggregation-fn results))})

(defn filter-results
  "Filter results based on a predicate"
  [predicate-fn]
  {:type :filter
   :predicate predicate-fn
   :execute (fn [& args]
              (if (apply predicate-fn args)
                args
                []))})

;; Error handling and recovery
(defn with-error-handling
  "Wrap a pipeline step with error handling"
  [step error-handler]
  {:type :error-wrapper
   :step step
   :error-handler error-handler
   :execute (fn [& args]
              (try
                (apply (:execute step) args)
                (catch Exception e
                  (error-handler e args))))})

(defn retry-on-failure
  "Retry a pipeline step on failure"
  [step max-retries]
  {:type :retry-wrapper
   :step step
   :max-retries max-retries
   :execute (fn [& args]
              (loop [attempts 0]
                (try
                  (apply (:execute step) args)
                  (catch Exception e
                    (if (< attempts max-retries)
                      (do
                        (println (explorer/ansi-color :yellow (str "⚠️  Attempt " (inc attempts) " failed, retrying...")))
                        (recur (inc attempts)))
                      (throw e))))))})

;; Pre-built analysis pipelines
(def comprehensive-system-analysis
  "Complete system analysis pipeline"
  (pipeline
   (fn [& _] @(requiring-resolve 'evolver.kernel/db))  ; Get current DB
   explorer/analyze-store-comprehensive
   explorer/analyze-performance-trends
   explorer/interactive-diagnostics))

(def health-check-pipeline
  "Quick health check pipeline"
  (conditional-pipeline
   (fn [& _]
     (let [db @(requiring-resolve 'evolver.kernel/db)]
       (> (count (:tx-log db)) 0)))  ; Has transactions?
   (pipeline
    (fn [& _] @(requiring-resolve 'evolver.kernel/db))
    explorer/interactive-diagnostics)
   (pipeline
    (fn [& _]
      (println (explorer/ansi-color :yellow "⚠️  No transaction history found - system may be freshly initialized"))
      {:status :fresh-system}))))

(def performance-monitoring-pipeline
  "Performance monitoring pipeline"
  (pipeline
   (fn [& _] @(requiring-resolve 'evolver.kernel/db))
   (transform-result
    (fn [db]
      {:node-count (count (:nodes db))
       :tx-count (count (:tx-log db))
       :ref-count (count (:references db))
       :memory-estimate (* (+ (count (:nodes db))
                              (count (:tx-log db))
                              (count (:references db))) 100)}))  ; Rough memory estimate
   explorer/analyze-performance-trends))

(def reference-integrity-pipeline
  "Reference system integrity check"
  (pipeline
   (requiring-resolve 'agent.reference_tools/inspect-references)
   (requiring-resolve 'agent.reference_tools/validate-reference-integrity)
   (transform-result
    (fn [result]
      (if (:valid? result)
        (assoc result :status :healthy :message "Reference system is healthy")
        (assoc result :status :unhealthy :message "Reference integrity issues found"))))))

;; Pipeline execution and management
(defn execute-pipeline
  "Execute a pipeline with timing and error handling"
  [pipeline & args]
  (let [start-time (System/currentTimeMillis)]
    (try
      (println (explorer/format-header "🚀 EXECUTING PIPELINE" :level 1 :color :magenta))
      (let [result (apply (:execute pipeline) args)
            end-time (System/currentTimeMillis)
            duration (- end-time start-time)]
        (println (explorer/ansi-color :green (str "✅ Pipeline completed in " duration "ms")))
        (assoc result :execution-time duration :status :success))
      (catch Exception e
        (let [end-time (System/currentTimeMillis)
              duration (- end-time start-time)]
          (println (explorer/ansi-color :red (str "❌ Pipeline failed after " duration "ms: " (.getMessage e))))
          {:status :failed :error (.getMessage e) :execution-time duration})))))

(defn create-custom-pipeline
  "Create a custom pipeline from a sequence of steps"
  [name description & steps]
  {:name name
   :description description
   :pipeline (apply pipeline steps)
   :created-at (System/currentTimeMillis)
   :execute (fn [& args]
              (println (explorer/format-header (str "🎯 " name) :level 1 :color :blue))
              (println (explorer/ansi-color :cyan description))
              (apply execute-pipeline (:pipeline {:name name :description description :pipeline (apply pipeline steps)}) args))})

;; Pipeline registry
(def pipeline-registry (atom {}))

(defn register-pipeline
  "Register a pipeline for reuse"
  [name pipeline]
  (swap! pipeline-registry assoc name pipeline)
  (println (explorer/ansi-color :green (str "📝 Pipeline '" name "' registered"))))

(defn get-pipeline
  "Get a registered pipeline"
  [name]
  (get @pipeline-registry name))

(defn list-pipelines
  "List all registered pipelines"
  []
  (if (seq @pipeline-registry)
    (do
      (println (explorer/format-header "📋 REGISTERED PIPELINES" :level 1 :color :cyan))
      (doseq [[name pipeline] @pipeline-registry]
        (println (str (explorer/ansi-color :yellow "• ") name))
        (println (str "  " (:description pipeline "")))
        (println)))
    (println (explorer/ansi-color :yellow "No pipelines registered"))))

;; Initialize with built-in pipelines
(register-pipeline "comprehensive-analysis" comprehensive-system-analysis)
(register-pipeline "health-check" health-check-pipeline)
(register-pipeline "performance-monitor" performance-monitoring-pipeline)
(register-pipeline "reference-integrity" reference-integrity-pipeline)

;; Result processing and visualization
(defn visualize-pipeline-result
  "Visualize pipeline execution results"
  [result]
  (cond
    (= (:status result) :success)
    (do
      (println (explorer/format-header "✅ PIPELINE SUCCESS" :level 2 :color :green))
      (println (explorer/format-key-value "Execution Time" (str (:execution-time result) "ms")))
      (when (:data result)
        (println (explorer/format-key-value "Result Data" (:data result)))))

    (= (:status result) :failed)
    (do
      (println (explorer/format-header "❌ PIPELINE FAILED" :level 2 :color :red))
      (println (explorer/format-key-value "Execution Time" (str (:execution-time result) "ms")))
      (println (explorer/format-key-value "Error" (:error result))))

    :else
    (println (explorer/format-key-value "Result" result))))

(defn compare-pipeline-results
  "Compare results from multiple pipeline runs"
  [& results]
  (println (explorer/format-header "📊 PIPELINE RESULT COMPARISON" :level 1 :color :blue))
  (let [successful (count (filter #(= (:status %) :success) results))
        failed (count (filter #(= (:status %) :failed) results))
        avg-time (/ (reduce + (map :execution-time results)) (count results))]
    (println (explorer/format-key-value "Total Runs" (count results)))
    (println (explorer/format-key-value "Successful" successful))
    (println (explorer/format-key-value "Failed" failed))
    (println (explorer/format-key-value "Average Time" (str (format "%.2f" avg-time) "ms")))
    (println (explorer/format-key-value "Success Rate" (str (format "%.1f" (* 100.0 (/ successful (count results)))) "%")))))

;; Workflow templates
(defn create-analysis-workflow
  "Create a complete analysis workflow"
  [name & {:keys [include-health include-performance include-references]}]
  (let [steps (cond-> []
               include-health (conj health-check-pipeline)
               include-performance (conj performance-monitoring-pipeline)
               include-references (conj reference-integrity-pipeline)
               true (conj comprehensive-system-analysis))]
    (create-custom-pipeline
     name
     (str "Complete analysis workflow"
          (when include-health " with health checks")
          (when include-performance " with performance monitoring")
          (when include-references " with reference integrity"))
     (apply parallel-pipeline steps))))

(defn run-workflow
  "Execute a named workflow"
  [workflow-name & args]
  (if-let [workflow (get-pipeline workflow-name)]
    (apply (:execute workflow) args)
    (do
      (println (explorer/ansi-color :red (str "❌ Workflow '" workflow-name "' not found")))
      (list-pipelines))))

;; Export main functions
(def compose pipeline)
(def run execute-pipeline)
(def register register-pipeline)
(def workflows list-pipelines)