(ns evolver.registry
  (:require [evolver.dispatcher :as dispatcher]
            [evolver.history :as history]
            [evolver.kernel :as k]))

;; Helper to get the current cursor
(defn- cursor [store] (-> @store :present :view :selection peek))
(defn- selv [store] (-> @store :present :view :selection))

(def registry
  {;; Data-mutating commands (via dispatcher)
   :create-child-block
   {:id :create-child-block
    :doc "Creates a new child of the current cursor."
    :handler (fn [store _]
               (dispatcher/dispatch-intent! store :create-child-block {:cursor (cursor store)}))
    :hotkey {:key "Enter"}}

   :indent-block
   {:id :indent-block
    :doc "Indents the selected block(s)."
    :handler (fn [store _] (dispatcher/dispatch-intent! store :indent-block {:cursor (cursor store)}))
    :hotkey {:key "Tab"}}

   :outdent-block
   {:id :outdent-block
    :doc "Outdents the selected block(s)."
    :handler (fn [store _] (dispatcher/dispatch-intent! store :outdent-block {:cursor (cursor store)}))
    :hotkey {:key "Tab" :shift true}}

   :delete-selected-blocks
   {:id :delete-selected-blocks
    :doc "Deletes all selected blocks."
    :handler (fn [store _] (dispatcher/dispatch-intent! store :delete-selected-blocks {:ids (selv store)}))
    :hotkey {:key "Backspace"}}

   :move-up
   {:id :move-up
    :doc "Moves the current block up."
    :handler (fn [store _] (dispatcher/dispatch-intent! store :move-up {:cursor (cursor store)}))
    :hotkey {:key "ArrowUp" :alt true :shift true}}

   :move-down
   {:id :move-down
    :doc "Moves the current block down."
    :handler (fn [store _] (dispatcher/dispatch-intent! store :move-down {:cursor (cursor store)}))
    :hotkey {:key "ArrowDown" :alt true :shift true}}

   ;; History commands (direct manipulation)
   :undo
   {:id :undo
    :doc "Undo the last action."
    :handler (fn [store _] (swap! store history/undo))
    :hotkey {:key "z" :meta true}}

   :redo
   {:id :redo
    :doc "Redo the last undone action."
    :handler (fn [store _] (swap! store history/redo))
    :hotkey {:key "z" :meta true :shift true}}

   ;; View-only commands (direct swap!)
   :select-node
   {:id :select-node
    :doc "Selects a single node, clearing previous selection."
    :handler (fn [store _ {:keys [node-id]}]
               (swap! store assoc-in [:present :view :selection] [node-id]))}

   :toggle-selection
   {:id :toggle-selection
    :doc "Toggles the selection of a single node."
    :handler (fn [store _ {:keys [target-id]}]
               (swap! store update-in [:present :view :selection]
                      (fn [selection]
                        (let [sel-set (set selection)]
                          (if (contains? sel-set target-id)
                            (vec (disj sel-set target-id))
                            (conj (vec selection) target-id))))))}

   :select-block-below
   {:id :select-block-below
    :doc "Selects the block below the current cursor."
    :handler (fn [store _]
               (let [db (:present @store)
                     cur (cursor store)]
                 (when-let [next-node (k/get-next db cur)]
                   (swap! store assoc-in [:present :view :selection] [next-node]))))
    :hotkey {:key "ArrowDown" :alt true}}

   :select-block-above
   {:id :select-block-above
    :doc "Selects the block above the current cursor."
    :handler (fn [store _]
               (let [db (:present @store)
                     cur (cursor store)]
                 (when-let [prev-node (k/get-prev db cur)]
                   (swap! store assoc-in [:present :view :selection] [prev-node]))))
    :hotkey {:key "ArrowUp" :alt true}}

   :hover-node
   {:id :hover-node
    :doc "Highlights a node on hover."
    :handler (fn [store _ {:keys [node-id]}]
               (swap! store update-in [:present :view :hovered-referencers] (fnil conj #{}) node-id))}

   :unhover-node
   {:id :unhover-node
    :doc "Removes highlight from a node on unhover."
    :handler (fn [store _ {:keys [node-id]}]
               (swap! store update-in [:present :view :hovered-referencers] (fnil disj #{}) node-id))}

   :toggle-collapse
   {:id :toggle-collapse
    :doc "Toggles the collapsed state of the current node."
    :handler (fn [store _]
               (let [cur (cursor store)]
                 (swap! store update-in [:present :view :collapsed]
                        (fn [collapsed-set]
                          (if (contains? collapsed-set cur)
                            (disj collapsed-set cur)
                            (conj collapsed-set cur))))))
    :hotkey {:key "." :meta true}}

   :select-node-with-modifiers
   {:id :select-node-with-modifiers
    :doc "Selects a node with modifier key support for multi-selection."
    :handler (fn [store event {:keys [node-id]}]
               (let [shift? (.-shiftKey event)
                     meta? (or (.-metaKey event) (.-ctrlKey event))]
                 (cond
                   ;; Shift or Meta/Ctrl = toggle selection (add/remove from multi-selection)
                   (or shift? meta?)
                   (swap! store update-in [:present :view :selection]
                          (fn [selection]
                            (let [sel-set (set selection)]
                              (if (contains? sel-set node-id)
                                (vec (disj sel-set node-id))
                                (conj (vec selection) node-id)))))

                   ;; No modifiers = replace selection with single node
                   :else
                   (swap! store assoc-in [:present :view :selection] [node-id]))))}

   :merge-block-up
   {:id :merge-block-up
    :doc "Merge current block with previous block (Backspace at start of block)."
    :handler (fn [store _]
               (let [cur (cursor store)
                     ;; TODO: Get actual cursor position from editor state
                     cursor-position 0] ; Assume at start for now
                 (dispatcher/dispatch-intent! store :merge-block-up
                                              {:cursor cur
                                               :cursor-position cursor-position})))}

   :merge-block-down
   {:id :merge-block-down
    :doc "Merge next block into current block (Delete at end of block)."
    :handler (fn [store _]
               (let [cur (cursor store)
                     ;; TODO: Get actual cursor position and content from editor state
                     cursor-position nil
                     block-content nil]
                 (dispatcher/dispatch-intent! store :merge-block-down
                                              {:cursor cur
                                               :cursor-position cursor-position
                                               :block-content block-content})))}

   :apply-selected-op
   {:id :apply-selected-op
    :doc "Apply the selected operation from the dropdown."
    :handler (fn [store _ _]
               ;; TODO: Implement dropdown operation selection
               (js/console.log "Apply selected operation - not implemented"))}

   :set-selected-op
   {:id :set-selected-op
    :doc "Set the selected operation in the dropdown."
    :handler (fn [store event _]
               ;; TODO: Implement dropdown operation selection
               (js/console.log "Set selected operation - not implemented"))}})
