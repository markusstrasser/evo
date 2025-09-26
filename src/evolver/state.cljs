(ns evolver.state
  (:require [evolver.kernel :as kernel]
            [evolver.constants :as constants]
            [evolver.keyboard :as keyboard]
            [evolver.middleware :as middleware]))

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
                   (js/console.log "Reactive render triggered")
                   (render-fn root-element new-state))))

    ;; Add logging watch for debugging
    (add-watch store ::log-changes
               (fn [_key _ref old-state new-state]
                 (when (not= old-state new-state)
                   (js/console.log "Store updated:"
                                   {:old-selection (get-in old-state [:view :selection])
                                    :new-selection (get-in new-state [:view :selection])
                                    :old-cursor (get-in old-state [:view :cursor])
                                    :new-cursor (get-in new-state [:view :cursor])
                                    :operation-count (count (:operation-history new-state))}))))

    store))

(defn dispatch!
  "Single entrypoint for all state mutations"
  [store command]
  (let [initial-ctx {:db @store :cmd command :log [] :errors [] :effects []}
        final-ctx (middleware/run-pipeline initial-ctx middleware/pipeline-steps)]
    (if (seq (:errors final-ctx))
      (js/console.error "Dispatch failed with errors:" (:errors final-ctx))
      (let [cmd (:cmd final-ctx)
            db (:db final-ctx)]
        (if (#{:undo :redo} (:op cmd))
          ;; For undo/redo, recompute db from history
          (let [history (:history db)
                history-index (:history-index db)
                new-db (reduce kernel/apply-command constants/initial-db-base (take history-index history))]
            (reset! store (assoc new-db :history history :history-index history-index)))
          ;; For other commands, update history
          (let [history (:history db)
                history-index (:history-index db)
                truncated-history (if (< history-index (count history))
                                    (subvec history 0 history-index)
                                    history)
                new-history (conj truncated-history cmd)
                new-history-index (inc history-index)
                final-db (assoc db :history new-history :history-index new-history-index)]
            (reset! store final-db)))
        ;; Process effects if any
        (doseq [effect (:effects final-ctx)]
          (js/console.log "Processing effect:" effect))))))

