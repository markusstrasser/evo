(ns evolver.keyboard-commands
  (:require [evolver.kernel :as kernel]
            [evolver.middleware :as middleware]))

(defn clear-selection-command [store _event-data _params]
  (swap! store assoc-in [:view :selected] #{}))

(defn select-all-blocks-command [store _event-data _params]
  (let [all-nodes (set (keys (:nodes @store)))]
    (swap! store assoc-in [:view :selected] (disj all-nodes "root"))))

(defn navigate-sibling-command [store _event-data {:keys [direction]}]
  (let [selected (first (get-in @store [:view :selected]))]
    (when selected
      (let [pos (kernel/node-position @store selected)
            parent-id (:parent pos)
            siblings (get-in @store [:children-by-parent parent-id])
            current-idx (.indexOf siblings selected)
            new-idx (case direction
                      :up (dec current-idx)
                      :down (inc current-idx))]
        (when (and (>= new-idx 0) (< new-idx (count siblings)))
          (let [target-node (nth siblings new-idx)]
            (swap! store assoc-in [:view :selected] #{target-node})))))))

(defn select-parent-command [store _event-data _params]
  (let [selected (first (get-in @store [:view :selected]))]
    (when selected
      (let [pos (kernel/node-position @store selected)
            parent-id (:parent pos)]
        (when (and parent-id (not= parent-id "root"))
          (swap! store assoc-in [:view :selected] #{parent-id}))))))

(defn create-child-block-command [store _event-data _params]
  (let [selected (first (get-in @store [:view :selected]))]
    (when selected
      (let [command {:op :insert
                     :parent-id selected
                     :node-id (kernel/gen-new-id)
                     :node-data {:type :div :props {:text "New block"}}
                     :position nil}
            result (middleware/safe-apply-command-with-middleware @store command)]
        (reset! store result)))))

(defn create-sibling-above-command [store _event-data _params]
  (let [selected (first (get-in @store [:view :selected]))]
    (when selected
      (let [pos (kernel/node-position @store selected)
            command {:op :insert
                     :parent-id (:parent pos)
                     :node-id (kernel/gen-new-id)
                     :node-data {:type :div :props {:text "New sibling above"}}
                     :position (:index pos)}
            result (middleware/safe-apply-command-with-middleware @store command)]
        (reset! store result)))))

(defn delete-selected-blocks-command [store _event-data _params]
  (let [selected-set (get-in @store [:view :selected])]
    (when (seq selected-set)
      (let [final-result
            (reduce (fn [current-store node-id]
                      (let [command {:op :delete :node-id node-id :recursive false}]
                        (middleware/safe-apply-command-with-middleware current-store command)))
                    @store
                    selected-set)]
        (reset! store (assoc-in final-result [:view :selected] #{}))))))

(defn indent-block-command [store _event-data _params]
  (let [selected (first (get-in @store [:view :selected]))]
    (when selected
      (let [pos (kernel/node-position @store selected)]
        (when (> (:index pos) 0)
          (let [command {:op :move
                         :node-id selected
                         :new-parent-id (get (:children pos) (dec (:index pos)))
                         :position nil}
                result (middleware/safe-apply-command-with-middleware @store command)]
            (reset! store result)))))))

(defn outdent-block-command [store _event-data _params]
  (let [selected (first (get-in @store [:view :selected]))]
    (when selected
      (let [pos (kernel/node-position @store selected)]
        (when (not= (:parent pos) "root")
          (let [command {:op :move
                         :node-id selected
                         :new-parent-id (:parent (kernel/node-position @store (:parent pos)))
                         :position {:type :after :sibling-id (:parent pos)}}
                result (middleware/safe-apply-command-with-middleware @store command)]
            (reset! store result)))))))

(defn move-block-command [store _event-data {:keys [direction]}]
  (let [selected (first (get-in @store [:view :selected]))]
    (when selected
      (let [pos (kernel/node-position @store selected)
            siblings (:children pos)
            current-idx (:index pos)
            new-idx (case direction
                      :up (max 0 (dec current-idx))
                      :down (min (dec (count siblings)) (inc current-idx)))]
        (when (not= current-idx new-idx)
          (let [command {:op :reorder
                         :node-id selected
                         :parent-id (:parent pos)
                         :from-index current-idx
                         :to-index new-idx}
                result (middleware/safe-apply-command-with-middleware @store command)]
            (reset! store result)))))))

(defn toggle-collapse-command [store _event-data _params]
  (let [selected (first (get-in @store [:view :selected]))]
    (when selected
      (let [collapsed? (contains? (:collapsed (:view @store)) selected)]
        (if collapsed?
          (swap! store update-in [:view :collapsed] disj selected)
          (swap! store update-in [:view :collapsed] conj selected))))))