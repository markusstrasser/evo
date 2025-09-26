(ns evolver.core
  (:require [replicant.dom :as r]
            [evolver.kernel :as kernel]
            [evolver.renderer :as renderer]
            [evolver.state :as state]
            [evolver.registry :as registry]
            [evolver.commands :as commands]
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

(defn handle-event
  "Handles DOM events by dispatching commands to the store"
  {:malli/schema [:=> [:cat map? vector?] any?]}
  [event-data actions]
  (commands/dispatch-commands @store event-data actions))

(defn handle-keyboard-event [event]
  (when-let [action (keyboard/handle-keyboard-event event)]
    (commands/dispatch-command @store event action)))

(defn ^:export main []
  (r/set-dispatch!
   (fn [event-data handler-data]
     (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (handle-event event-data handler-data))))

  ;; Remove any existing keyboard event listener to prevent duplicates
  (.removeEventListener js/window "keydown" handle-keyboard-event)

  ;; Add keyboard event listener
  (js/console.log "Attaching keyboard event listener to window")
  (.addEventListener js/window "keydown" handle-keyboard-event)
  (js/console.log "Keyboard event listener attached to window")

  ;; Initial render
  (let [root (.getElementById js/document "root")]
    (js/console.log "Initial render")
    (r/render root (renderer/render (:present @@store)))

     ;; Set up reactive rendering
    (remove-watch @store :render)
    (add-watch @store :render
               (fn [_ _ old-state new-state]
                 (when (not= old-state new-state)
                   (js/console.log "Reactive render triggered")
                   (r/render root (renderer/render (:present new-state))))))))

(defn load-agent-tools!
  "Load agent tools for REPL development"
  []
  (js/console.log "Agent tools would be loaded here"))