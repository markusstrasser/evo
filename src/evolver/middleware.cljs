(ns evolver.middleware
  (:require [evolver.kernel :as kernel]
            [evolver.schemas :as schemas]))

;; ... keep existing functions like run-pipeline, steps etc. if you want to reuse them
;; For simplicity, we'll create a direct transactional function.

(defn apply-transaction-with-middleware
  "Applies a transaction through the kernel, with validation."
  [db transaction]
  (try
    ;; Optional: Add pre-validation for the whole transaction
    (let [new-db (kernel/apply-transaction db transaction)]
      ;; Optional: Add post-validation
      (schemas/validate-db new-db)
      new-db)
    (catch :default e
      (js/console.error "Transaction failed:" (pr-str transaction) "Error:" (.-message e))
      db))) ;; Return original db on failure
