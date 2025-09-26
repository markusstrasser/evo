(ns evolver.registry
  "Unified command registry for the evolver system - the single source of truth for all user actions"
  (:require [evolver.commands :as commands]))

(def command-schema
  "Schema for each command in the registry"
  [:map
   [:id :keyword]
   [:doc :string]
   [:handler fn?] ; Takes [store params], performs side-effects
   [:hotkey {:optional true}
    [:map
     [:key :string]
     [:shift {:optional true} :boolean]
     [:ctrl {:optional true} :boolean]
     [:alt {:optional true} :boolean]
     [:meta {:optional true} :boolean]]]])

;; Mutable reference to dispatch-intent! function that will be set by the dispatcher

(def registry
  "The authoritative registry - single source of truth for all user actions"
  {:enter-new-block
   {:id :enter-new-block
    :doc "Context-sensitive Enter behavior - split block or create sibling."
    :handler (fn [store event params] (commands/dispatch-command store event [:enter-new-block params]))
    :hotkey {:key "Enter"}}

   :enter-line-break
   {:id :enter-line-break
    :doc "Add line break within current block (Shift+Enter)."
    :handler (fn [store event params] (commands/dispatch-command store event [:enter-line-break params]))
    :hotkey {:key "Enter" :shift true}}

   :create-sibling-below
   {:id :create-sibling-below
    :doc "Creates a new sibling below the current cursor."
    :handler (fn [store event params] (commands/dispatch-command store event [:create-sibling-below params]))}

   :indent-block
   {:id :indent-block
    :doc "Indents the selected block(s)."
    :handler (fn [store event params] (commands/dispatch-command store event [:indent-block params]))
    :hotkey {:key "Tab"}}

   :outdent-block
   {:id :outdent-block
    :doc "Outdents the selected block(s)."
    :handler (fn [store event params] (commands/dispatch-command store event [:outdent-block params]))
    :hotkey {:key "Tab" :shift true}}

   :move-up
   {:id :move-up
    :doc "Moves the selected block up."
    :handler (fn [store event params] (commands/dispatch-command store event [:move-block {:direction :up}]))
    :hotkey {:key "ArrowUp" :alt true :shift true}}

   :move-down
   {:id :move-down
    :doc "Moves the selected block down."
    :handler (fn [store event params] (commands/dispatch-command store event [:move-block {:direction :down}]))
    :hotkey {:key "ArrowDown" :alt true :shift true}}

   :delete-selected-blocks
   {:id :delete-selected-blocks
    :doc "Deletes all selected blocks."
    :handler (fn [store event params] (commands/dispatch-command store event [:delete-selected-blocks params]))
    :hotkey {:key "Backspace"}}

   :select-all-blocks
   {:id :select-all-blocks
    :doc "Selects all blocks in the tree."
    :handler (fn [store event params] (commands/dispatch-command store event [:select-all-blocks params]))
    :hotkey {:key "A" :meta true :shift true}}

   :clear-selection
   {:id :clear-selection
    :doc "Clears the current selection."
    :handler (fn [store event params] (commands/dispatch-command store event [:clear-selection params]))
    :hotkey {:key "Escape"}}

   :navigate-sequential-down
   {:id :navigate-sequential-down
    :doc "Navigate to the next block in sequence."
    :handler (fn [store event params] (commands/dispatch-command store event [:navigate-sequential {:direction :down}]))
    :hotkey {:key "ArrowDown"}}

   :navigate-sequential-up
   {:id :navigate-sequential-up
    :doc "Navigate to the previous block in sequence."
    :handler (fn [store event params] (commands/dispatch-command store event [:navigate-sequential {:direction :up}]))
    :hotkey {:key "ArrowUp"}}

   :navigate-sibling-down
   {:id :navigate-sibling-down
    :doc "Navigate to the next sibling."
    :handler (fn [store event params] (commands/dispatch-command store event [:navigate-sibling {:direction :down}]))
    :hotkey {:key "ArrowDown" :alt true}}

   :navigate-sibling-up
   {:id :navigate-sibling-up
    :doc "Navigate to the previous sibling."
    :handler (fn [store event params] (commands/dispatch-command store event [:navigate-sibling {:direction :up}]))
    :hotkey {:key "ArrowUp" :alt true}}

   :select-parent
   {:id :select-parent
    :doc "Select the parent of the current block."
    :handler (fn [store event params] (commands/dispatch-command store event [:select-parent params]))
    :hotkey {:key "ArrowLeft"}}

   :select-first-child
   {:id :select-first-child
    :doc "Select the first child of the current block."
    :handler (fn [store event params] (commands/dispatch-command store event [:select-first-child params]))
    :hotkey {:key "ArrowRight"}}

   :select-last-child
   {:id :select-last-child
    :doc "Select the last child of the current block."
    :handler (fn [store event params] (commands/dispatch-command store event [:select-last-child params]))
    :hotkey {:key "ArrowRight" :shift true}}

   :toggle-collapse
   {:id :toggle-collapse
    :doc "Toggle collapse/expand of the current block."
    :handler (fn [store event params] (commands/dispatch-command store event [:toggle-collapse params]))
    :hotkey {:key "." :meta true}}

   :add-reference
   {:id :add-reference
    :doc "Add a reference between two selected blocks."
    :handler (fn [store event params] (commands/dispatch-command store event [:add-reference params]))}

   :remove-reference
   {:id :remove-reference
    :doc "Remove a reference between two selected blocks."
    :handler (fn [store event params] (commands/dispatch-command store event [:remove-reference params]))}

   :undo
   {:id :undo
    :doc "Undo the last action."
    :handler (fn [store event params] (commands/dispatch-command store event [:undo params]))
    :hotkey {:key "z" :meta true}}

   :redo
   {:id :redo
    :doc "Redo the last undone action."
    :handler (fn [store event params] (commands/dispatch-command store event [:redo params]))
    :hotkey {:key "z" :meta true :shift true}}

   :set-selected-op
   {:id :set-selected-op
    :doc "Set the selected operation for the UI dropdown."
    :handler (fn [store event params] (commands/dispatch-command store event [:set-selected-op params]))}

   :apply-selected-op
   {:id :apply-selected-op
    :doc "Apply the selected operation from the UI dropdown."
    :handler (fn [store event params] (commands/dispatch-command store event [:apply-selected-op params]))}})

(defn get-command-by-id
  "Get command metadata by id"
  [id]
  (get registry id))

(defn get-keyboard-mappings
  "Generate keyboard mappings from registry"
  []
  (into {}
        (keep (fn [[cmd-id cmd-config]]
                (when-let [hotkey (:hotkey cmd-config)]
                  [hotkey [cmd-id {}]]))
              registry)))

(defn get-ui-commands
  "Get commands suitable for UI display"
  []
  (vals (dissoc registry :undo :redo)))