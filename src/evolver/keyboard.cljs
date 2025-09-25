(ns evolver.keyboard
  (:require [evolver.kernel :as kernel]
            [evolver.commands :as commands]))

(defn key-matches?
  "Check if a key event matches the given criteria"
  [event mapping]
  (let [{:keys [key shift ctrl alt meta]} mapping]
    (and (= (.-key event) key)
         (= (boolean shift) (.getModifierState event "Shift"))
         (= (boolean ctrl) (.getModifierState event "Control"))
         (= (boolean alt) (.getModifierState event "Alt"))
         (= (boolean meta) (.getModifierState event "Meta")))))

(defn get-current-selection [store]
  "Get current selection state"
  (let [selected-set (:selected (:view @store))
        selected (first selected-set)]
    {:selected-set selected-set :selected selected}))

;; Keyboard mapping configuration
(def keyboard-mappings
  "Declarative keyboard mappings with conditions and commands"
  [{:key "Escape"
    :command [:clear-selection]}

   {:key "Backspace"
    :requires-selection true
    :command [:delete-selected-blocks]}

   {:key "Delete"
    :requires-selection true
    :command [:delete-selected-blocks]}

   {:key "Enter"
    :requires-selection true
    :command [:create-child-block]}

   {:key "Enter" :shift true
    :requires-selection true
    :command [:create-sibling-above]}

   {:key "A" :shift true :meta true
    :command [:select-all-blocks]}

   {:key "A" :shift true :ctrl true
    :command [:select-all-blocks]}

   {:key "ArrowDown" :alt true
    :requires-selection true
    :command [:navigate-sibling {:direction :down}]}

   {:key "ArrowUp" :alt true
    :requires-selection true
    :command [:navigate-sibling {:direction :up}]}

   {:key "a" :meta true
    :requires-selection true
    :command [:select-parent]}

   {:key "a" :ctrl true
    :requires-selection true
    :command [:select-parent]}

   {:key "Tab"
    :requires-selection true
    :command [:indent-block]}

   {:key "Tab" :shift true
    :requires-selection true
    :command [:outdent-block]}

   {:key "ArrowUp" :alt true :shift true
    :requires-selection true
    :command [:move-block {:direction :up}]}

   {:key "ArrowDown" :alt true :shift true
    :requires-selection true
    :command [:move-block {:direction :down}]}

   {:key "." :meta true
    :requires-selection true
    :command [:toggle-collapse]}

   {:key "." :ctrl true
    :requires-selection true
    :command [:toggle-collapse]}])

(defn handle-keyboard-event
  "Handle keyboard events using declarative mappings and command dispatch"
  [store event]
  (let [selection-state (get-current-selection store)]
    (loop [mappings keyboard-mappings]
      (when-let [mapping (first mappings)]
        (if (and (key-matches? event mapping)
                 (or (not (:requires-selection mapping))
                     (not-empty (:selected-set selection-state))))
          (do (.preventDefault event)
              (let [[cmd-name & params] (:command mapping)
                    cmd-params (if (seq params)
                                 (if (= 1 (count params)) (first params) params)
                                 {})]
                (commands/dispatch-command store {:keyboard-event event} [cmd-name cmd-params]))
              true)
          (recur (rest mappings)))))
    false))