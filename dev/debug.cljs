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

;; Undo/redo specific helpers

(defn explain-undo-stack
  "Explain what's in the undo stack"
  []
  (let [stack (undo-stack)
        evts (events)]
    (js/console.log "=== Undo Stack (" (count stack) "items) ===")
    (doseq [[idx event-id] (map-indexed vector stack)]
      (let [event (first (filter #(= event-id (:event/id %)) evts))]
        (when event
          (case (:event/type event)
            :review
            (js/console.log
             (str (inc idx) ". REVIEW "
                  (get-in event [:event/data :card-hash])
                  " → "
                  (get-in event [:event/data :rating])))

            :card-created
            (js/console.log
             (str (inc idx) ". CREATE "
                  (get-in event [:event/data :card-hash])))

            (js/console.log (str (inc idx) ". " (:event/type event)))))))))

(defn explain-redo-stack
  "Explain what's in the redo stack"
  []
  (let [stack (redo-stack)
        evts (events)]
    (js/console.log "=== Redo Stack (" (count stack) "items) ===")
    (doseq [[idx event-id] (map-indexed vector stack)]
      (let [event (first (filter #(= event-id (:event/id %)) evts))]
        (when event
          (case (:event/type event)
            :review
            (js/console.log
             (str (inc idx) ". REVIEW "
                  (get-in event [:event/data :card-hash])
                  " → "
                  (get-in event [:event/data :rating])))

            (js/console.log (str (inc idx) ". " (:event/type event)))))))))

(defn verify-undo-invariants
  "Verify undo/redo invariants"
  []
  (let [stack (undo-stack)
        evts (events)
        stack-events (keep (fn [event-id]
                             (first (filter #(= event-id (:event/id %)) evts)))
                           stack)
        has-card-created? (some #(= :card-created (:event/type %)) stack-events)]
    {:all-reviews? (every? #(= :review (:event/type %)) stack-events)
     :has-card-created? has-card-created?
     :invariant-ok? (not has-card-created?)
     :message (if has-card-created?
                "❌ ERROR: Undo stack contains card-created events!"
                "✅ OK: Undo stack only contains review events")}))

;; Export to window for console access
(defn ^:export init! []
  (js/console.log "🔧 Debug helpers loaded. Try:")
  (js/console.log "  DEBUG.summary()")
  (js/console.log "  DEBUG.explainUndoStack()")
  (js/console.log "  DEBUG.verifyUndoInvariants()")
  (set! js/window.DEBUG
        #js {:state state
             :cards cards
             :events events
             :undoStack undo-stack
             :redoStack redo-stack
             :dueCards due-cards
             :summary summary
             :inspectEvents inspect-events
             :explainUndoStack explain-undo-stack
             :explainRedoStack explain-redo-stack
             :verifyUndoInvariants verify-undo-invariants
             :lastEvent last-event
             :eventTypes event-types
             :activeEvents active-events
             :undoneEvents undone-events
             :reload reload!
             :clearEvents clear-events!}))
