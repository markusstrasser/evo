;; DEMO: Reactive streams replacing commands
(ns reactive-demo
  (:require [missionary.core :as m]))

;; Current: Commands are discrete objects
{:op :insert :parent-id "p1" :node-data {:text "hello"}}

;; Reactive: Data flows through streams
(def keyboard-presses
  "Stream of all keyboard events"
  (m/observe (fn [!]
    (let [handler (fn [e] (! {:key (.-key e) :shift (.-shiftKey e)}))]
      (.addEventListener js/window "keydown" handler)
      #(.removeEventListener js/window "keydown" handler)))))

(def insert-intents
  "Stream of insert operations"
  (m/ap (fn [press]
          (when (= (:key press) "Enter")
            {:parent-id "current-selection" :text "new block"}))
        keyboard-presses))

(def tree-state
  "Reactive tree state - automatically updates when inputs change"
  (m/reactor
    (m/stream!
      (m/ap (fn [insert]
              (when insert
                (update-db-insert insert)))
            insert-intents))))

;; Usage: Just read current state
(println "Current tree:" @tree-state)