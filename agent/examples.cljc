(ns agent.examples
  "Example usage patterns for agent utilities.

  Demonstrates common workflows and best practices for using
  the analysis and debugging tools effectively."
  (:require [agent.core :as agent]))

;; Example: Analyzing namespace health
(comment
  ;; Check if a namespace is healthy
  (agent/analyze-namespace-health "clojure.core")
  ;; => {:namespace "clojure.core", :status :healthy, :public-fns 679, :dependencies 1}

  ;; Check a non-existent namespace
  (agent/analyze-namespace-health "nonexistent.ns")
  ;; => {:namespace "nonexistent.ns", :status :not-found}
  )

;; Example: Database structure validation
(comment
  ;; Validate current db structure
  (let [db @(requiring-resolve 'evolver.kernel/db)]
    (agent/validate-db-structure db))
  ;; => {:valid? true, :missing-keys [], :extra-keys [], :node-count 8, :tx-count 0}
  )

;; Example: Performance profiling
(comment
  ;; Profile a simple operation
  (agent/profile-operation
   (fn [] {:status :ok})
   1000)
  ;; => {:iterations 1000, :total-time-ms 45, :avg-time-ms 0.045, :results-summary {:ok 1000}}
  )

;; Example: Debugging database changes
(comment
  ;; Compare db states before/after an operation
  (let [before @(requiring-resolve 'evolver.kernel/db)
        after (evolver.kernel/insert-node before
                                         {:parent-id "root"
                                          :node-id "test-node"
                                          :node-data {:type :div}
                                          :position 0})]
    (agent/db-diff before after))
  ;; => {:nodes-added 1, :children-changed 1, :view-changed 0, :tx-log-growth 0, :summary "..."}
  )

;; Example: Batch testing operations
(comment
  ;; Test multiple operations at once
  (agent/test-operations-batch
   [{:op :insert :parent-id "root" :node-id "test1" :node-data {:type :div} :position 0}
    {:op :insert :parent-id "root" :node-id "test2" :node-data {:type :p} :position 1}]
   :validate? true
   :profile? true)
  ;; => {:summary "2/2 ops successful", :avg-time "15ms/op", :validation {...}, :performance {...}}
  )

;; Example: System health check
(comment
  ;; Quick health check
  (agent/health-check)
  ;; => {:healthy? true, :issues [], :schema-valid? true, :node-count 8, :tx-count 0}
  )

;; Example: Comprehensive system analysis
(comment
  ;; Full system diagnostics
  (agent/analyze-current-system)
  ;; => {:db-structure {...}, :consistency {...}, :errors {...}, :health {...}}
  )

;; Example: Performance profiling with enhanced metadata
(comment
  ;; Profile with detailed timing stats
  (agent/profile-operation
   (fn [] {:status :ok, :data (range 100)})
   100)
  ;; => includes min/max/median/p95 timing stats
  )

;; Example: Pretty printing results
(comment
  ;; Pretty print analysis results
  (agent/pprint-analysis-result (agent/analyze-namespace-health "cljs.core"))

  ;; Pretty print performance profile
  (agent/pprint-performance-profile (agent/profile-operation #(+ 1 2) 50))

  ;; Pretty print health check
  (agent/pprint-health-check (agent/health-check))
  )

;; Example: Quick diagnostics output
(comment
  ;; Print diagnostics to console
  (agent/quick-diagnostics)
  ;; Prints formatted diagnostic information
  )