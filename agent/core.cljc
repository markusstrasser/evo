(ns agent.core
  "Unified entry point for all agent utilities and analysis tools.

  Provides convenient access to code analysis, debugging helpers,
  and REPL workflow functions for efficient development."
  (:require [agent.code-analysis :as ca]
            [agent.debug-helpers :as dh]
            [agent.repl-workflow :as rw]
            [agent.reference_tools :as rt]
            [agent.dev_tools :as dt]))

;; Re-export all public functions for easy access
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

;; Convenience functions for common workflows
(defn analyze-current-system
  "Analyze the current evolver system state comprehensively."
  []
  (let [db @(requiring-resolve 'evolver.kernel/db)]
    {:db-structure (validate-db-structure-validated db)
     :consistency (dh/check-consistency db)
     :errors (dh/scan-for-errors db)
     :health (rw/health-check)}))

(defn quick-diagnostics
  "Run quick diagnostics on the system."
  []
  (let [results (analyze-current-system)]
    (println "=== System Diagnostics ===")
    (println "DB Structure Valid:" (:valid? (:db-structure results)))
    (println "Consistent:" (:consistent? (:consistency results)))
    (println "Healthy:" (:healthy? (:health results)))
    (when (seq (:issues (:health results)))
      (println "Issues:" (:issues (:health results))))
    results))

;; REPL pretty-printing helpers
(defn pprint-db-diff
  "Pretty print a database diff result."
  [diff]
  (println "Database Changes:")
  (println (str "  Nodes: +" (:nodes-added diff)))
  (println (str "  Children: " (:children-changed diff) " changed"))
  (println (str "  View: " (:view-changed diff) " changed"))
  (println (str "  TX Log: +" (:tx-log-growth diff)))
  (println (str "  Summary: " (:summary diff))))

(defn pprint-health-check
  "Pretty print a health check result."
  [health]
  (println "System Health Check:")
  (println (str "  Healthy: " (:healthy? health)))
  (println (str "  Schema Valid: " (:schema-valid? health)))
  (println (str "  Node Count: " (:node-count health)))
  (println (str "  TX Count: " (:tx-count health)))
  (when (seq (:issues health))
    (println "  Issues:")
    (doseq [issue (:issues health)]
      (println (str "    - " issue)))))

(defn pprint-analysis-result
  "Pretty print an analysis result."
  [result]
  (cond
    (:namespace result)
    (do
      (println (str "Namespace: " (:namespace result)))
      (println (str "  Status: " (:status result)))
      (when (= (:status result) :healthy)
        (println (str "  Public Functions: " (:public-fns result)))
        (println (str "  Dependencies: " (:dependencies result))))
      (when (:potential-issues result)
        (println "  Issues:")
        (doseq [[k v] (:potential-issues result)]
          (println (str "    " k ": " v)))))

    (:valid? result)
    (do
      (println "DB Structure Validation:")
      (println (str "  Valid: " (:valid? result)))
      (println (str "  Node Count: " (:node-count result)))
      (println (str "  TX Count: " (:tx-count result)))
      (when (seq (:missing-keys result))
        (println (str "  Missing Keys: " (:missing-keys result))))
      (when (seq (:extra-keys result))
        (println (str "  Extra Keys: " (:extra-keys result)))))

    :else
    (println "Analysis Result:" result)))