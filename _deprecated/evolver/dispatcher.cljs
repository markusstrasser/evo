(ns evolver.dispatcher
  (:require [evolver.history :as history]
            [evolver.intents :as intents]
            [evolver.kernel :as k]
            [evolver.middleware :as mw]))

(defn dispatch-intent!
  "The primary entrypoint for all state mutations.
   1. Retrieves the pure intent function from the intents registry.
   2. Generates a transaction (a vector of kernel commands).
   3. Runs the transaction through the middleware pipeline.
   4. Pushes the new state as a snapshot into the history ring."
  [store intent-id params]
  (let [db-state (:present @store)]
    (if-let [intent-fn (get intents/intents intent-id)]
      (when-let [transaction (intent-fn db-state params)]
        (let [new-state (mw/apply-transaction-with-middleware db-state transaction)]
          (swap! store history/push-snapshot new-state)))
      (js/console.warn "No intent function found for id:" intent-id))))
