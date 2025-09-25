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
             (let [tx {:op :select-node :node-id node-id}]
               (swap! store kernel/log-operation tx)
               (swap! store assoc-in [:view :selected] #{node-id})))

           :set-selected-op
           (let [value (.. (:replicant/dom-event event-data) -target -value)]
             (js/console.log "Setting selected-op to:" value)
             (swap! store assoc :selected-op (when (not= value "") (keyword value))))

           :apply-selected-op
           (let [op (:selected-op @store)
                 selected (first (:selected (:view @store)))]
             (js/console.log "Applying op:" op "on selected:" selected "store selected-op:" (:selected-op @store))
             (when (and op selected)
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
                               nil)]
                 (when command
                   (swap! store kernel/safe-apply-command command)))))

           :undo
           (swap! store kernel/safe-apply-command {:op :undo})

           :redo
           (swap! store kernel/safe-apply-command {:op :redo})

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
        selected (first (:selected (:view @store)))]
    (when selected
      (cond
        ;; Enter - Create child block
        (and (= key "Enter") (not shift?))
        (do (.preventDefault event)
            (let [command {:op :insert
                           :parent-id selected
                           :node-id (kernel/gen-new-id)
                           :node-data {:type :div :props {:text (str "Child of " selected)}}
                           :position nil}]
              (swap! store kernel/safe-apply-command command)))

        ;; Shift+Enter - Create sibling above
        (and (= key "Enter") shift?)
        (do (.preventDefault event)
            (let [pos (kernel/node-position @store selected)
                  command {:op :insert
                           :parent-id (:parent pos)
                           :node-id (kernel/gen-new-id)
                           :node-data {:type :div :props {:text (str "Sibling above " selected)}}
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