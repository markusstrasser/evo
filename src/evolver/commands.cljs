(ns evolver.commands
  (:require [evolver.kernel :as kernel]
            [evolver.middleware :as middleware]))

(defn- get-selection-context [store]
  (let [view (:view @store)
        selected (:selected view)
        selected-set (set selected)
        selected-single (first selected)]
    {:selected selected
     :selected-set selected-set
     :selected-single selected-single
     :has-selection? (seq selected)
     :multi-selection? (> (count selected) 1)}))

(defn- select-node-command [store event-data {:keys [node-id]}]
  (when-let [dom-event (:replicant/dom-event event-data)]
    (.stopPropagation dom-event))

  (let [current-selected (get-in @store [:view :selected])
        has-modifiers? (when-let [dom-event (:replicant/dom-event event-data)]
                         (or (.getModifierState dom-event "Shift")
                             (.getModifierState dom-event "Control")
                             (.getModifierState dom-event "Meta")))
        new-selected (if has-modifiers?
                       (if (contains? current-selected node-id)
                         (disj current-selected node-id)
                         (conj current-selected node-id))
                       #{node-id})
        tx {:op :select-node :node-id node-id}]
    (swap! store kernel/log-operation tx)
    (swap! store assoc-in [:view :selected] new-selected)))

(defn- set-selected-op-command [store event-data _params]
  (let [value (.. (:replicant/dom-event event-data) -target -value)]
    (swap! store assoc :selected-op (when (not= value "") (keyword value)))))

(defn- hover-node-command [store _event-data {:keys [node-id]}]
  (let [referencers (kernel/get-references @store node-id)]
    (swap! store assoc-in [:view :hovered-referencers] referencers)))

(defn- unhover-node-command [store _event-data {:keys [node-id]}]
  (swap! store assoc-in [:view :hovered-referencers] #{}))

(defn- apply-selected-op-command [store _event-data _params]
  (let [op (:selected-op @store)
        {:keys [selected-single selected-set]} (get-selection-context store)]
    (when op
      (let [command (case op
                      :create-child-block
                      {:op :insert
                       :parent-id selected-single
                       :node-id (kernel/gen-new-id)
                       :node-data {:type :div :props {:text (str "Child of " selected-single)}}
                       :position nil}

                      :create-sibling-above
                      (let [pos (kernel/node-position @store selected-single)]
                        {:op :insert
                         :parent-id (:parent pos)
                         :node-id (kernel/gen-new-id)
                         :node-data {:type :div :props {:text (str "Sibling above " selected-single)}}
                         :position (:index pos)})

                      :create-sibling-below
                      (let [pos (kernel/node-position @store selected-single)]
                        {:op :insert
                         :parent-id (:parent pos)
                         :node-id (kernel/gen-new-id)
                         :node-data {:type :div :props {:text (str "Sibling below " selected-single)}}
                         :position (inc (:index pos))})

                      :indent
                      (let [pos (kernel/node-position @store selected-single)]
                        (when (> (:index pos) 0)
                          {:op :move
                           :node-id selected-single
                           :new-parent-id (get (:children pos) (dec (:index pos)))
                           :position nil}))

                      :outdent
                      (let [pos (kernel/node-position @store selected-single)]
                        (when (not= (:parent pos) "root")
                          {:op :move
                           :node-id selected-single
                           :new-parent-id (:parent (kernel/node-position @store (:parent pos)))
                           :position {:type :after :sibling-id (:parent pos)}}))

                      :add-reference
                      (when (= (count selected-set) 2)
                        (let [[from to] (vec selected-set)]
                          {:op :add-reference :from-node-id from :to-node-id to}))

                      :remove-reference
                      (when (= (count selected-set) 2)
                        (let [[from to] (vec selected-set)]
                          {:op :remove-reference :from-node-id from :to-node-id to}))

                      nil)]
        (when command
          (let [result (middleware/safe-apply-command-with-middleware @store command)]
            (reset! store result)))))))

(defn- undo-command [store _event-data _params]
  (let [result (middleware/safe-apply-command-with-middleware @store {:op :undo})]
    (reset! store result)))

(defn- redo-command [store _event-data _params]
  (let [result (middleware/safe-apply-command-with-middleware @store {:op :redo})]
    (reset! store result)))

(def command-registry
  "Registry mapping command names to handler functions"
  {:select-node select-node-command
   :set-selected-op set-selected-op-command
   :hover-node hover-node-command
   :unhover-node unhover-node-command
   :apply-selected-op apply-selected-op-command
   :undo undo-command
   :redo redo-command

   ;; Keyboard commands - proper implementations
   :clear-selection (fn [store _event-data _params]
                      (swap! store assoc-in [:view :selected] #{}))

   :select-all-blocks (fn [store _event-data _params]
                        (let [all-nodes (keys (:nodes @store))]
                          (swap! store assoc-in [:view :selected] (set all-nodes))))

   :navigate-sibling (fn [store _event-data {:keys [direction]}]
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

   :select-parent (fn [store _event-data _params]
                    (let [selected (first (get-in @store [:view :selected]))]
                      (when selected
                        (let [pos (kernel/node-position @store selected)
                              parent-id (:parent pos)]
                          (when (not= parent-id "root")
                            (swap! store assoc-in [:view :selected] #{parent-id}))))))

   :create-child-block (fn [store _event-data _params]
                         (let [selected (first (get-in @store [:view :selected]))]
                           (when selected
                             (let [command {:op :insert
                                            :parent-id selected
                                            :node-id (kernel/gen-new-id)
                                            :node-data {:type :div :props {:text "New child"}}
                                            :position nil}]
                               (let [result (middleware/safe-apply-command-with-middleware @store command)]
                                 (reset! store result))))))

   :create-sibling-above (fn [store _event-data _params]
                           (let [selected (first (get-in @store [:view :selected]))]
                             (when selected
                               (let [pos (kernel/node-position @store selected)]
                                 (let [command {:op :insert
                                                :parent-id (:parent pos)
                                                :node-id (kernel/gen-new-id)
                                                :node-data {:type :div :props {:text "New sibling"}}
                                                :position (:index pos)}]
                                   (let [result (middleware/safe-apply-command-with-middleware @store command)]
                                     (reset! store result)))))))

   :delete-selected-blocks (fn [store _event-data _params]
                             (let [selected (get-in @store [:view :selected])]
                               (when (seq selected)
                                 (doseq [node-id selected]
                                   (let [command {:op :delete :node-id node-id :recursive true}]
                                     (let [result (middleware/safe-apply-command-with-middleware @store command)]
                                       (reset! store result))))
                                 (swap! store assoc-in [:view :selected] #{}))))

   :indent-block (fn [store _event-data _params]
                   (let [selected (first (get-in @store [:view :selected]))]
                     (when selected
                       (let [pos (kernel/node-position @store selected)]
                         (when (> (:index pos) 0)
                           (let [siblings (get-in @store [:children-by-parent (:parent pos)])
                                 new-parent-id (nth siblings (dec (:index pos)))
                                 command {:op :move
                                          :node-id selected
                                          :new-parent-id new-parent-id
                                          :position nil}]
                             (let [result (middleware/safe-apply-command-with-middleware @store command)]
                               (reset! store result))))))))

   :outdent-block (fn [store _event-data _params]
                    (let [selected (first (get-in @store [:view :selected]))]
                      (when selected
                        (let [pos (kernel/node-position @store selected)]
                          (when (not= (:parent pos) "root")
                            (let [parent-pos (kernel/node-position @store (:parent pos))
                                  command {:op :move
                                           :node-id selected
                                           :new-parent-id (:parent parent-pos)
                                           :position (inc (:index parent-pos))}]
                              (let [result (middleware/safe-apply-command-with-middleware @store command)]
                                (reset! store result))))))))

   :move-block (fn [store _event-data {:keys [direction]}]
                 (let [selected (first (get-in @store [:view :selected]))]
                   (when selected
                     (let [pos (kernel/node-position @store selected)
                           siblings (get-in @store [:children-by-parent (:parent pos)])
                           current-idx (:index pos)
                           new-idx (case direction
                                     :up (max 0 (dec current-idx))
                                     :down (min (dec (count siblings)) (inc current-idx)))]
                       (when (not= current-idx new-idx)
                         (let [command {:op :reorder
                                        :node-id selected
                                        :parent-id (:parent pos)
                                        :from-index current-idx
                                        :to-index new-idx}]
                           (let [result (middleware/safe-apply-command-with-middleware @store command)]
                             (reset! store result))))))))

   :toggle-collapse (fn [store _event-data _params]
                      (let [selected (first (get-in @store [:view :selected]))]
                        (when selected
                          (let [current-collapsed (get-in @store [:view :collapsed] #{})
                                new-collapsed (if (contains? current-collapsed selected)
                                                (disj current-collapsed selected)
                                                (conj current-collapsed selected))]
                            (swap! store assoc-in [:view :collapsed] new-collapsed)))))})

(defn dispatch-command
  "Dispatch a command through the registry"
  [store event-data [cmd-name params]]
  (if-let [handler (command-registry cmd-name)]
    (try
      (handler store event-data params)
      (catch js/Error e
        (js/console.error "Error executing command:" cmd-name "Error:" e)
        (swap! store kernel/log-message :error
               (str "Command failed: " cmd-name)
               {:command cmd-name :params params :error (.-message e)})))
    (do
      (js/console.error "Unknown command:" cmd-name)
      (swap! store kernel/log-message :error
             (str "Unknown command: " cmd-name)
             {:command cmd-name :params params}))))

(defn dispatch-commands
  "Dispatch multiple commands"
  [store event-data actions]
  (let [actions (if (sequential? (first actions)) actions [actions])]
    (doseq [action actions]
      (dispatch-command store event-data action))))