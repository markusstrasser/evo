(ns evolver.core
  (:require [replicant.dom :as r]
            [evolver.kernel :as kernel]
            [evolver.renderer :as renderer]
            [evolver.state :as state]
            [evolver.schemas :as schemas]))

(defonce store (state/create-store-atom
                kernel/db
                (fn [element db] (r/render element (renderer/render db)))
                (js/document.getElementById "root")))

(defn handle-event [event-data actions]
  (state/dispatch-event! store event-data actions))

(defn handle-keyboard-event [event]
  (state/handle-keyboard! store event))

(defn ^:export main []
  (r/set-dispatch!
   (fn [event-data handler-data]
     (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (handle-event event-data handler-data))))

  ;; Add keyboard event listener
  (js/console.log "Attaching keyboard event listener to window")
  (.addEventListener js/window "keydown" handle-keyboard-event)
  (js/console.log "Keyboard event listener attached to window")

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