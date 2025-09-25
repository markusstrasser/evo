(ns evolver.core
  (:require [replicant.dom :as r]
            [evolver.kernel :as kernel]
            [evolver.renderer :as renderer]))

(defonce store (atom kernel/db))

(defn handle-event [event-data actions]
  (js/console.log "handle-event called with actions:" actions)
  (let [actions (if (sequential? actions) actions [actions])]
    (doseq [action actions]
      (js/console.log "processing action:" action)
      (case (first action)
        :select-node
        (let [{:keys [node-id]} (second action)]
          (js/console.log "selecting node:" node-id)
          (swap! store assoc-in [:view :selected] #{node-id}))

        :set-selected-op
        (let [value (.. (:replicant/dom-event event-data) -target -value)]
          (js/console.log "setting selected-op to:" value)
          (swap! store assoc :selected-op (keyword value)))

        :apply-selected-op
        (let [op (:selected-op @store)]
          (js/console.log "applying op:" op)
          (when op
            (swap! store
                   (case op
                     :create-child-block kernel/create-child-block
                     :create-sibling-above kernel/create-sibling-above
                     :create-sibling-below kernel/create-sibling-below
                     :indent kernel/indent
                     :outdent kernel/outdent
                     (do (js/console.log "unknown op:" op) identity)))))

        nil))
    ;; Force re-render after handling events
    (when-let [root (.getElementById js/document "root")]
      (r/render root (renderer/render @store)))))

(defn ^:export main []
  (r/set-dispatch!
     (fn [event-data handler-data]
       (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
         (handle-event event-data handler-data))))
  ;; Initial render
  (let [root (.getElementById js/document "root")]
    (r/render root (renderer/render @store))

    ;; Remove existing watch if it exists
    (remove-watch store :render)
    (add-watch store :render
                (fn [_ _ _ new-state]
                  (r/render root (renderer/render new-state))))))