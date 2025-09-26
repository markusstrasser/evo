;; PARADIGM SHIFT: Commands vs Streams
(ns paradigm-shift-demo
  (:require [missionary.core :as m]))

;; ============================================
;; CURRENT: Commands (your current system)
;; ============================================

;; 1. Event -> Command conversion (from your commands.cljs)
(defn handle-keyboard-event [event]
  (when (= (.-key event) "Enter")
    {:op :insert
     :parent-id "current-selection"
     :node-data {:text "new block"}}))

;; 2. Command dispatch (from your core.cljs)
(defn dispatch-command [store cmd]
  (when cmd
    (swap! store (fn [db]
                   ;; validation, logging, etc.
                   (apply-command db cmd)))))

;; 3. Manual state updates
(defn apply-command [db {:keys [op parent-id node-data]}]
  (case op
    :insert (update-in db [:nodes] assoc (gensym) node-data)
    ;; ... more cases
    ))

;; ============================================
;; REACTIVE: Streams (proposed system)
;; ============================================

;; 1. Event stream (replaces event handlers)
(def keyboard-events
  (m/observe (fn [!]
    (let [handler (fn [e] (! {:key (.-key e) :shift (.-shiftKey e)}))]
      (.addEventListener js/window "keydown" handler)
      #(.removeEventListener js/window "keydown" handler)))))

;; 2. Data transformation (replaces command builders)
(def insert-operations
  (m/ap (fn [event]
          (when (= (:key event) "Enter")
            {:parent-id "current-selection"
             :node-data {:text "new block"}}))
        keyboard-events))

;; 3. Automatic state updates (replaces dispatch + apply)
(def tree-state
  (m/reactor
    (m/stream!
      (m/ap (fn [op]
              (when op
                (fn [current-db]
                  (update-in current-db [:nodes]
                             assoc (gensym) (:node-data op)))))
            insert-operations))))

;; ============================================
;; USAGE COMPARISON
;; ============================================

;; Current: Manual event handling + dispatch
;; (in your main function)
(.addEventListener js/window "keydown"
  (fn [e] (dispatch-command store (handle-keyboard-event e))))

;; Reactive: Just read the current state
;; (UI automatically updates when tree-state changes)
(println "Tree:" @tree-state)

;; ============================================
;; WHY STREAMS ARE SIMPLER
;; ============================================

;; Commands: You manually wire events -> commands -> dispatch -> state
;; Streams: Data flows automatically through pipelines

;; Commands: Discrete objects that need routing/dispatch
;; Streams: Continuous data flow with automatic propagation

;; Commands: Imperative - "do this action now"
;; Streams: Declarative - "when this happens, transform data this way"