(ns evolver.core
  (:require [replicant.dom :as r]
            [evolver.kernel :as kernel]
            [evolver.renderer :as renderer]))

(defonce store (atom kernel/db))

(defn handle-event [event-data actions]
  (js/console.log "Dispatch received:" {:event-data event-data :actions actions})
  (let [actions (if (sequential? actions) actions [actions])]
    (doseq [action actions]
      (js/console.log "Processing action:" action)
      (case (first action)
        :select-node
        (let [{:keys [node-id]} (second action)]
          (js/console.log "Selecting node:" node-id)
          ;; Stop event propagation to prevent bubbling to parent elements
          (when-let [dom-event (:replicant/dom-event event-data)]
            (.stopPropagation dom-event))
          (swap! store assoc-in [:view :selected] #{node-id}))

        :set-selected-op
        (let [value (.. (:replicant/dom-event event-data) -target -value)]
          (js/console.log "Setting selected-op to:" value)
          (swap! store assoc :selected-op (when (not= value "") (keyword value))))

        :apply-selected-op
        (let [op (:selected-op @store)
              selected (first (:selected (:view @store)))]
          (js/console.log "Applying op:" op "on selected:" selected)
          (when (and op selected)
            (let [new-db (case op
                           :create-child-block (kernel/create-child-block @store)
                           :create-sibling-above (kernel/create-sibling-above @store)
                           :create-sibling-below (kernel/create-sibling-below @store)
                           :indent (kernel/indent @store)
                           :outdent (kernel/outdent @store)
                           (do (js/console.log "Unknown op:" op) @store))]
              (reset! store new-db)
              (js/console.log "Applied op, new db keys:" (keys new-db)))))

        (js/console.log "Unknown action:" action)))))

(defn ^:export main []
  (r/set-dispatch!
     (fn [event-data handler-data]
       (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
         (handle-event event-data handler-data))))
  ;; Initial render
  (let [root (.getElementById js/document "root")]
    (js/console.log "Initial render")
    (r/render root (renderer/render @store))

    ;; Set up reactive rendering
    (remove-watch store :render)
    (add-watch store :render
                (fn [_ _ old-state new-state]
                  (when (not= old-state new-state)
                    (js/console.log "Reactive render triggered")
                    (r/render root (renderer/render new-state)))))))