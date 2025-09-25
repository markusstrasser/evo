(ns evolver.state
  (:require [evolver.kernel :as kernel]
            [evolver.commands :as commands]
            [evolver.keyboard :as keyboard]))

(defn derive-view-state
  "Compute derived view state from raw db"
  [db]
  (let [nodes (:nodes db)
        referenced-nodes (->> nodes
                              (map (fn [[node-id _]]
                                     [node-id (kernel/get-references db node-id)]))
                              (filter #(seq (second %)))
                              (into {}))
        referencer-highlighted (get-in db [:view :hovered-referencers] #{})]
    (assoc-in db [:computed]
              {:referenced-nodes referenced-nodes
               :referencer-highlighted referencer-highlighted})))

(defn create-store-atom
  "Create properly initialized store with watchers"
  [initial-db render-fn root-element]
  (let [store (atom (derive-view-state initial-db))]

    ;; Add reactive rendering watch
    (add-watch store ::render
               (fn [_key _ref old-state new-state]
                 (when (not= old-state new-state)
                   (let [derived-state (derive-view-state new-state)]
                     (when (not= derived-state new-state)
                       (reset! store derived-state))
                     (render-fn root-element derived-state)))))

    ;; Add logging watch for debugging
    (add-watch store ::log-changes
               (fn [_key _ref old-state new-state]
                 (when (not= old-state new-state)
                   (js/console.log "Store updated:"
                                   {:old-selected (get-in old-state [:view :selected])
                                    :new-selected (get-in new-state [:view :selected])
                                    :operation-count (count (:operation-history new-state))}))))

    store))

(defn update-store!
  "Update store with proper derived state computation"
  [store update-fn & args]
  (let [result (apply update-fn @store args)
        derived-result (derive-view-state result)]
    (reset! store derived-result)))

(defn apply-command!
  "Apply a command and update store"
  [store command]
  (let [result (kernel/safe-apply-command @store command)]
    (update-store! store (constantly result))))

(defn dispatch-event!
  "Main event dispatcher for the application"
  [store event-data actions]
  (js/console.log "Dispatching event:" {:event-data event-data :actions actions})
  (commands/dispatch-commands store event-data actions))

(defn handle-keyboard!
  "Handle keyboard events through the state layer"
  [store event]
  (js/console.log "Keyboard event:" (.-key event))
  (keyboard/handle-keyboard-event store event))