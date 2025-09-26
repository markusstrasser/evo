(ns agent.core
  "Simple agent utilities for the evolver app"
  (:require [agent.schemas :as schemas]))

;; Schema validation
(def validate-transaction schemas/validate-transaction)

(defn help
  "Show available agent functions"
  []
  (println "Agent utilities:")
  (println "  (validate-transaction tx) - Validate transaction format"))