(ns evolver.state
  (:require [evolver.kernel :as kernel]
            [evolver.constants :as constants]
            [evolver.keyboard :as keyboard]
            [evolver.middleware :as middleware]
            [evolver.history :as history]))

(defn derive-view-state
  "Compute derived view state from raw db - now works with both old db format and history ring"
  [db-or-history-ring]
  (let [db (if (contains? db-or-history-ring :present)
             (:present db-or-history-ring) ; New history ring format
             db-or-history-ring) ; Old db format (for transition)
        nodes (:nodes db)
        referenced-nodes (->> nodes
                              (map (fn [[node-id _]]
                                     [node-id (kernel/get-references db node-id)]))
                              (filter #(seq (second %)))
                              (into {}))
        referencer-highlighted (get-in db [:view :hovered-referencers] #{})
        derived-db (assoc-in db [:computed]
                             {:referenced-nodes referenced-nodes
                              :referencer-highlighted referencer-highlighted})]
    (if (contains? db-or-history-ring :present)
      ;; Return updated history ring with derived view at root level for compatibility
      (-> db-or-history-ring
          (assoc :present derived-db)
          (assoc :view (:view derived-db))) ; Copy view to root level
      ;; Return updated db
      derived-db)))

(defn create-store-atom
  "Create properly initialized store with watchers - now works with history ring"
  [initial-history-ring render-fn root-element]
  (let [store (atom (derive-view-state initial-history-ring))]

    ;; Add reactive rendering watch
    (add-watch store ::render
               (fn [_key _ref old-state new-state]
                 (when (not= old-state new-state)
                   (js/console.log "Reactive render triggered")
                   (render-fn root-element new-state))))

    ;; Add logging watch for debugging
    (add-watch store ::log-changes
               (fn [_key _ref old-state new-state]
                 (when (not= old-state new-state)
                   (let [old-present (:present old-state)
                         new-present (:present new-state)]
                     (js/console.log "Store updated:"
                                     {:old-selection (get-in old-present [:view :selection])
                                      :new-selection (get-in new-present [:view :selection])
                                      :old-cursor (get-in old-present [:view :cursor])
                                      :new-cursor (get-in new-present [:view :cursor])
                                      :can-undo (history/can-undo? new-state)
                                      :can-redo (history/can-redo? new-state)})))))

    store))

