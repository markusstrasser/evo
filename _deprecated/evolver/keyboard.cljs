(ns evolver.keyboard
  (:require [evolver.registry :as registry]))

(def key-mappings
  "Generate mappings directly from the single source of truth: the registry."
  (->> (vals registry/registry)
       (filter :hotkey)
       (map (fn [{:keys [id hotkey]}]
              (assoc hotkey :command [id {}])))
       (vec)))

(defn key-matches? [event mapping]
  (let [{:keys [key shift ctrl alt meta]} mapping]
    (and (= (.-key event) key)
         (= (boolean shift) (.getModifierState event "Shift"))
         (= (boolean ctrl) (.getModifierState event "Control"))
         (= (boolean alt) (.getModifierState event "Alt"))
         (= (boolean meta) (.getModifierState event "Meta")))))

(defn handle-keyboard-event [event]
  (when-let [mapping (first (filter #(key-matches? event %) key-mappings))]
    (.preventDefault event)
    (:command mapping)))