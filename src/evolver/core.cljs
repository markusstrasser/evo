(ns evolver.core
  (:require [replicant.dom :as r]
            [evolver.kernel :as kernel]
            [evolver.renderer :as renderer]
            [evolver.schemas :as schemas]))

(defonce store (atom kernel/db))

(defn handle-event [event-data actions]
  (js/console.log "Dispatch received:" {:event-data event-data :actions actions})
  (let [actions (if (sequential? actions) actions [actions])]
    (doseq [action actions]
      (js/console.log "Processing action:" action)
      (try
        (case (first action)
          :select-node
          (let [{:keys [node-id]} (second action)]
            (js/console.log "Selecting node:" node-id)
              ;; Stop event propagation to prevent bubbling to parent elements
            (when-let [dom-event (:replicant/dom-event event-data)]
              (.stopPropagation dom-event))
            (let [dom-event (:replicant/dom-event event-data)
                  current-selected (:selected (:view @store))
                  new-selected (if (and dom-event
                                        (or (.getModifierState dom-event "Shift")
                                            (.getModifierState dom-event "Control")
                                            (.getModifierState dom-event "Meta")
                                            (> (count current-selected) 0))) ; Allow multiple selection if already have selection
                                 (if (contains? current-selected node-id)
                                   (disj current-selected node-id)
                                   (conj current-selected node-id))
                                 #{node-id})
                  tx {:op :select-node :node-id node-id}]
              (swap! store kernel/log-operation tx)
              (swap! store assoc-in [:view :selected] new-selected)))

          :set-selected-op
          (let [value (.. (:replicant/dom-event event-data) -target -value)]
            (js/console.log "Setting selected-op to:" value)
            (swap! store assoc :selected-op (when (not= value "") (keyword value))))

          :hover-node
          (let [{:keys [node-id]} (second action)]
            (js/console.log "Hovering node:" node-id)
            (let [referencers (kernel/get-references @store node-id)]
              (swap! store assoc-in [:view :hovered-referencers] referencers)))

          :unhover-node
          (let [{:keys [node-id]} (second action)]
            (js/console.log "Unhovering node:" node-id)
            (swap! store assoc-in [:view :hovered-referencers] #{}))

          :apply-selected-op
          (let [op (:selected-op @store)
                selected (first (:selected (:view @store)))
                selected-nodes (:selected (:view @store))]
            (js/console.log "Apply selected op:" {:op op :selected selected :selected-nodes selected-nodes})
            (when op
              (let [command (case op
                              :create-child-block {:op :insert
                                                   :parent-id selected
                                                   :node-id (kernel/gen-new-id)
                                                   :node-data {:type :div :props {:text (str "Child of " selected)}}
                                                   :position nil}
                              :create-sibling-above (let [pos (kernel/node-position @store selected)]
                                                      {:op :insert
                                                       :parent-id (:parent pos)
                                                       :node-id (kernel/gen-new-id)
                                                       :node-data {:type :div :props {:text (str "Sibling above " selected)}}
                                                       :position (:index pos)})
                              :create-sibling-below (let [pos (kernel/node-position @store selected)]
                                                      {:op :insert
                                                       :parent-id (:parent pos)
                                                       :node-id (kernel/gen-new-id)
                                                       :node-data {:type :div :props {:text (str "Sibling below " selected)}}
                                                       :position (inc (:index pos))})
                              :indent (let [pos (kernel/node-position @store selected)]
                                        (when (> (:index pos) 0)
                                          {:op :move
                                           :node-id selected
                                           :new-parent-id (get (:children pos) (dec (:index pos)))
                                           :position nil}))
                              :outdent (let [pos (kernel/node-position @store selected)]
                                         (when (not= (:parent pos) "root")
                                           {:op :move
                                            :node-id selected
                                            :new-parent-id (:parent (kernel/node-position @store (:parent pos)))
                                            :position {:type :after :sibling-id (:parent pos)}}))
                              :add-reference (let [selected-nodes (:selected (:view @store))]
                                               (when (= (count selected-nodes) 2)
                                                 (let [[from to] (vec selected-nodes)]
                                                   (js/console.log "Creating add-reference command:" {:from from :to to})
                                                   {:op :add-reference :from-node-id from :to-node-id to})))
                              :remove-reference (let [selected-nodes (:selected (:view @store))]
                                                  (when (= (count selected-nodes) 2)
                                                    (let [[from to] (vec selected-nodes)]
                                                      (js/console.log "Creating remove-reference command:" {:from from :to to})
                                                      {:op :remove-reference :from-node-id from :to-node-id to})))
                              nil)]
                (js/console.log "Command created:" command)
                (when command
                  (let [result (kernel/safe-apply-command @store command)]
                    (js/console.log "Command result:" (pr-str result))
                    (reset! store result))))))

          :undo
          (let [result (kernel/safe-apply-command @store {:op :undo})]
            (js/console.log "Undo result:" (pr-str result))
            (reset! store result))

          :redo
          (let [result (kernel/safe-apply-command @store {:op :redo})]
            (js/console.log "Redo result:" (pr-str result))
            (reset! store result))

          (js/console.log "Unknown action:" action))
        (catch js/Error e
          (js/console.error "Error handling action:" action "Error:" e)
          (swap! store kernel/log-message :error (str "Action failed: " (first action)) {:action action :error (.-message e)}))))))

(defn handle-keyboard-event [event]
  (let [key (.-key event)
        shift? (.-shiftKey event)
        ctrl? (.-ctrlKey event)
        alt? (.-altKey event)
        meta? (.-metaKey event)
        selected-set (:selected (:view @store))
        selected (first selected-set)]
    (cond
      ;; Escape - Clear selection
      (= key "Escape")
      (do (.preventDefault event)
          (swap! store assoc-in [:view :selected] #{}))

      ;; Delete operations (Backspace or Delete) - Delete selected blocks
      (and (or (= key "Backspace") (= key "Delete"))
           (not-empty selected-set))
      (do (.preventDefault event)
          (doseq [node-id selected-set]
            (let [command {:op :delete :node-id node-id :recursive false}]
              (swap! store kernel/safe-apply-command command)))
          ;; Clear selection after deletion
          (swap! store assoc-in [:view :selected] #{}))

      ;; Enter - Edit selected block (for now, just create child - later we can add inline editing)
      (and (= key "Enter") (not shift?) selected (not ctrl?) (not alt?) (not meta?))
      (do (.preventDefault event)
          (let [command {:op :insert
                         :parent-id selected
                         :node-id (kernel/gen-new-id)
                         :node-data {:type :div :props {:text "New block"}}
                         :position nil}]
            (swap! store kernel/safe-apply-command command)))

      ;; Cmd+Shift+A - Select all blocks
      (and (= key "A") shift? (or meta? ctrl?))
      (do (.preventDefault event)
          (let [all-nodes (set (keys (:nodes @store)))]
            (swap! store assoc-in [:view :selected] (disj all-nodes "root"))))

      ;; Alt+Down - Select block below
      (and (= key "ArrowDown") alt? selected (not shift?))
      (do (.preventDefault event)
          (let [pos (kernel/node-position @store selected)
                parent-id (:parent pos)
                siblings (get-in @store [:children-by-parent parent-id])
                current-idx (.indexOf siblings selected)
                next-idx (inc current-idx)]
            (when (< next-idx (count siblings))
              (let [next-node (nth siblings next-idx)]
                (swap! store assoc-in [:view :selected] #{next-node})))))

      ;; Alt+Up - Select block above  
      (and (= key "ArrowUp") alt? selected (not shift?))
      (do (.preventDefault event)
          (let [pos (kernel/node-position @store selected)
                parent-id (:parent pos)
                siblings (get-in @store [:children-by-parent parent-id])
                current-idx (.indexOf siblings selected)
                prev-idx (dec current-idx)]
            (when (>= prev-idx 0)
              (let [prev-node (nth siblings prev-idx)]
                (swap! store assoc-in [:view :selected] #{prev-node})))))

      ;; Cmd+A - Select parent block
      (and (= key "a") (or meta? ctrl?) (not shift?) selected)
      (do (.preventDefault event)
          (let [pos (kernel/node-position @store selected)
                parent-id (:parent pos)]
            (when (and parent-id (not= parent-id "root"))
              (swap! store assoc-in [:view :selected] #{parent-id}))))

      ;; Existing hotkeys - only apply when we have a selection
      (and selected (not-empty selected-set))
      (cond
        ;; Shift+Enter - Create sibling above
        (and (= key "Enter") shift?)
        (do (.preventDefault event)
            (let [pos (kernel/node-position @store selected)
                  command {:op :insert
                           :parent-id (:parent pos)
                           :node-id (kernel/gen-new-id)
                           :node-data {:type :div :props {:text "New sibling above"}}
                           :position (:index pos)}]
              (swap! store kernel/safe-apply-command command)))

        ;; Tab - Indent (make child)
        (and (= key "Tab") (not shift?))
        (do (.preventDefault event)
            (let [pos (kernel/node-position @store selected)
                  command (when (> (:index pos) 0)
                            {:op :move
                             :node-id selected
                             :new-parent-id (get (:children pos) (dec (:index pos)))
                             :position nil})]
              (when command
                (swap! store kernel/safe-apply-command command))))

        ;; Shift+Tab - Outdent (promote)
        (and (= key "Tab") shift?)
        (do (.preventDefault event)
            (let [pos (kernel/node-position @store selected)
                  command (when (not= (:parent pos) "root")
                            {:op :move
                             :node-id selected
                             :new-parent-id (:parent (kernel/node-position @store (:parent pos)))
                             :position {:type :after :sibling-id (:parent pos)}})]
              (when command
                (swap! store kernel/safe-apply-command command))))

        ;; Alt+Shift+Up/Down - Move block up/down
        (and alt? shift? (or (= key "ArrowUp") (= key "ArrowDown")))
        (do (.preventDefault event)
            (let [pos (kernel/node-position @store selected)
                  siblings (:children pos)
                  current-idx (:index pos)
                  new-idx (if (= key "ArrowUp")
                            (max 0 (dec current-idx))
                            (min (dec (count siblings)) (inc current-idx)))]
              (when (not= current-idx new-idx)
                (let [new-siblings (vec (remove #{selected} siblings))
                      final-siblings (vec (concat (take new-idx new-siblings) [selected] (drop new-idx new-siblings)))
                      command {:op :reorder
                               :node-id selected
                               :parent-id (:parent pos)
                               :from-index current-idx
                               :to-index new-idx}]
                  (swap! store kernel/safe-apply-command command)))))

        ;; Cmd+. / Ctrl+. - Toggle collapse/expand
        (and (= key ".") (or meta? ctrl?))
        (do (.preventDefault event)
            (let [collapsed? (contains? (:collapsed (:view @store)) selected)]
              (if collapsed?
                (swap! store update-in [:view :collapsed] disj selected)
                (swap! store update-in [:view :collapsed] conj selected))))))))

(defn ^:export main []
  (r/set-dispatch!
   (fn [event-data handler-data]
     (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (handle-event event-data handler-data))))

  ;; Add keyboard event listener
  (js/console.log "Attaching keyboard event listener to window")
  (.addEventListener js/window "keydown" handle-keyboard-event)
  (js/console.log "Keyboard event listener attached to window")

  ;; Initial render
  (let [root (.getElementById js/document "root")]
    (js/console.log "Initial render")
    (r/render root (renderer/render @store))

    ;; Set up reactive rendering
    (remove-watch store :render)
    (add-watch store :render
               (fn [_ _ old-state new-state]
                 (when (not= old-state new-state)
                   (js/console.log "Reactive render triggered")
                   (r/render root (renderer/render new-state)))))))