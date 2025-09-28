(ns evolver.middleware
  (:require [evolver.kernel :as kernel]
            [evolver.schemas :as schemas]))

(defn ->commands
  "Normalizes any transaction shape into a canonical vector of command maps."
  [tx]
  (cond
    (nil? tx) []
    (map? tx) (if (= (:op tx) :transaction) (vec (:commands tx)) [tx])
    (sequential? tx) (vec tx)
    :else (throw (ex-info "Bad transaction shape" {:tx tx}))))

(defn apply-transaction-with-middleware
  "Applies a normalized transaction through the kernel, with validation."
  [db tx]
  (try
    (let [commands (->commands tx)
          new-db (kernel/apply-transaction db commands)]
      (schemas/validate-db new-db)
      new-db)
    (catch :default e
      (js/console.error "Transaction failed:" (pr-str tx) "Error:" (.-message e))
      db)))