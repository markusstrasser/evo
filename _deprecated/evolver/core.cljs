(ns evolver.core
  (:require [replicant.dom :as r]
            [evolver.kernel :as kernel]
            [evolver.renderer :as renderer]
            [evolver.state :as state]
            [evolver.registry :as registry]
            [evolver.keyboard :as keyboard]
            [evolver.history :as history]
            [agent.core :as agent]
            [agent.store-inspector :as inspector]))

(defonce store
  (delay
    (let [history-ring (history/create-history-ring kernel/db)
          root-element (when (exists? js/document)
                         (js/document.getElementById "root"))]
      (state/create-store-atom
       history-ring
       (fn [element db]
         (when element ; Only render if we have a valid element
           (r/render element (renderer/render (:present db)))))
       root-element))))

(defn dispatch!
  "Generic dispatcher for both UI and keyboard events."
  [event [command-id params]]
  (if-let [command (get registry/registry command-id)]
    ((:handler command) @store event params) ; Dereference store delay to get atom
    (js/console.error "No command found in registry for id:" command-id)))

(defn keyboard-event-handler [event]
  (when-let [[command-id params :as action] (keyboard/handle-keyboard-event event)]
    (dispatch! event action)))

(defn ^:export main []
  (r/set-dispatch!
   (fn [event-data handler-data]
     (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (doseq [action handler-data]
         (dispatch! event-data action)))))

  (.removeEventListener js/window "keydown" keyboard-event-handler)
  (.addEventListener js/window "keydown" keyboard-event-handler)

  (let [root (.getElementById js/document "root")
        store-atom @store] ; Dereference the delay to get the actual atom
    (js/console.log "Initial render")
    (r/render root (renderer/render (:present @store-atom)))))