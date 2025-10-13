(ns dev.debug
  "REPL debugging helpers - load in browser console or REPL"
  (:require [lab.anki.ui :as ui]
            [lab.anki.core :as core]
            [cljs.pprint :refer [pprint]]))

;; State inspection

(defn state
  "Get current app state"
  []
  @ui/!state)

(defn cards
  "Get all cards"
  []
  (get-in @ui/!state [:state :cards]))

(defn events
  "Get all events"
  []
  (:events @ui/!state))

(defn undo-stack
  "Get undo stack"
  []
  (get-in @ui/!state [:state :undo-stack]))

(defn redo-stack
  "Get redo stack"
  []
  (get-in @ui/!state [:state :redo-stack]))

(defn due-cards
  "Get cards due now"
  []
  (core/due-cards (:state @ui/!state)))

;; Event inspection

(defn last-event
  "Get last event"
  []
  (last (events)))

(defn event-types
  "Get list of event types"
  []
  (map :event/type (events)))

(defn event-status-map
  "Get event status map (active/undone)"
  []
  (core/build-event-status-map (events)))

(defn active-events
  "Get only active events"
  []
  (let [status-map (event-status-map)]
    (filter #(= :active (get status-map (:event/id %) :active)) (events))))

(defn undone-events
  "Get only undone events"
  []
  (let [status-map (event-status-map)]
    (filter #(= :undone (get status-map (:event/id %))) (events))))

;; Summary functions

(defn summary
  "Print state summary"
  []
  (let [s (state)]
    {:cards/total (count (cards))
     :cards/due (count (due-cards))
     :events/total (count (events))
     :events/active (count (active-events))
     :events/undone (count (undone-events))
     :undo-stack/size (count (undo-stack))
     :redo-stack/size (count (redo-stack))}))

(defn inspect-events
  "Print recent events with status"
  ([] (inspect-events 10))
  ([n]
   (let [status-map (event-status-map)]
     (doseq [event (take-last n (events))]
       (let [status (get status-map (:event/id event) :active)]
         (println (if (= status :active) "✅" "❌")
                  (:event/type event)
                  (str "(" (subs (str (:event/id event)) 0 8) "...)")))))))

;; Action helpers

(defn reload!
  "Hard reload the page"
  []
  (.reload js/location true))

(defn clear-events!
  "Clear all events (WARNING: destructive)"
  []
  (when (js/confirm "Clear all events? This will reset the app.")
    (swap! ui/!state assoc :events [] :state {:cards {} :meta {}})))

;; Export to window for console access
(defn ^:export init! []
  (js/console.log "🔧 Debug helpers loaded. Try:")
  (js/console.log "  DEBUG.summary()")
  (js/console.log "  DEBUG.events()")
  (js/console.log "  DEBUG.inspectEvents()")
  (set! js/window.DEBUG
    #js {:state state
         :cards cards
         :events events
         :undoStack undo-stack
         :redoStack redo-stack
         :dueCards due-cards
         :summary summary
         :inspectEvents inspect-events
         :lastEvent last-event
         :eventTypes event-types
         :activeEvents active-events
         :undoneEvents undone-events
         :reload reload!
         :clearEvents clear-events!}))
