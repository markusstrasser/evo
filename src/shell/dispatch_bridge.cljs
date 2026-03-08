(ns shell.dispatch-bridge
  "Bridge Replicant handler data to the active runtime.")

(defn dispatch-handler-data!
  "Dispatch lifecycle hooks and plain function handlers from Replicant.

   Evo's shipped runtime only supports function handler data. Non-function
   handler payloads are ignored so stale vector-based handlers fail closed."
  [event-data handler-data]
  (let [trigger (:replicant/trigger event-data)]
    (cond
      (and (= :replicant.trigger/life-cycle trigger)
           (fn? handler-data))
      (handler-data event-data)

      (and (= :replicant.trigger/dom-event trigger)
           (fn? handler-data))
      (handler-data (:replicant/dom-event event-data))

      :else
      (do
        (when (and ^boolean goog.DEBUG
                   (some? handler-data)
                   (not (fn? handler-data)))
          (js/console.warn "Unsupported Replicant handler data; expected function handler"
                           (clj->js {:trigger trigger
                                     :handler-type (cond
                                                     (vector? handler-data) "vector"
                                                     (map? handler-data) "map"
                                                     :else (goog/typeOf handler-data))})))
        nil))))
