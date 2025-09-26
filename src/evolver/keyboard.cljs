(ns evolver.keyboard
  (:require [evolver.kernel :as kernel]
            [evolver.registry :as registry]))

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

;; Generate keyboard mappings from registry
(defn generate-keyboard-mappings
  "Generate keyboard mappings from command registry"
  []
  (let [registry-mappings (registry/get-keyboard-mappings)]
    (concat
     ;; Registry-based mappings
     (for [[key [cmd-id params]] registry-mappings]
       {:key key
        :command [cmd-id params]})

     ;; Complex mappings with conditions
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

      ;; Sequential navigation (main navigation keys)
      {:key "ArrowDown"
       :requires-selection true
       :command [:navigate-sequential {:direction :down}]}

      {:key "ArrowUp"
       :requires-selection true
       :command [:navigate-sequential {:direction :up}]}

      ;; Sibling navigation (with Alt modifier)
      {:key "ArrowDown" :alt true
       :requires-selection true
       :command [:navigate-sibling {:direction :down}]}

      {:key "ArrowUp" :alt true
       :requires-selection true
       :command [:navigate-sibling {:direction :up}]}

      ;; Parent/child navigation
      {:key "ArrowLeft"
       :requires-selection true
       :command [:select-parent]}

      {:key "ArrowRight"
       :requires-selection true
       :command [:select-first-child]}

      {:key "ArrowRight" :shift true
       :requires-selection true
       :command [:select-last-child]}

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
       :command [:toggle-collapse]}])))

(def keyboard-mappings
  "Declarative keyboard mappings with conditions and commands"
  (vec (generate-keyboard-mappings)))

(defn handle-keyboard-event
  "Handle keyboard events using declarative mappings"
  [store event]
  (let [selection-state (get-current-selection store)]
    (loop [mappings keyboard-mappings]
      (if-let [mapping (first mappings)]
        (if (and (key-matches? event mapping)
                 (or (not (:requires-selection mapping))
                     (not-empty (:selected-set selection-state))))
          (do (.preventDefault event)
              (let [[cmd-name & params] (:command mapping)
                    cmd-params (if (seq params)
                                 (if (= 1 (count params)) (first params) params)
                                 {})]
                (js/console.log "Executing keyboard command:" cmd-name cmd-params)
                ;; Return the command for dispatch to the command registry
                [cmd-name cmd-params]))
          (recur (rest mappings)))
        ;; Return false when no command found
        false))))