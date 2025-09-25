(ns evolver.commands
  (:require [evolver.kernel :as kernel]
            [evolver.keyboard-commands :as kb]
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

   ;; Keyboard commands
   :clear-selection kb/clear-selection-command
   :select-all-blocks kb/select-all-blocks-command
   :navigate-sibling kb/navigate-sibling-command
   :select-parent kb/select-parent-command
   :create-child-block kb/create-child-block-command
   :create-sibling-above kb/create-sibling-above-command
   :delete-selected-blocks kb/delete-selected-blocks-command
   :indent-block kb/indent-block-command
   :outdent-block kb/outdent-block-command
   :move-block kb/move-block-command
   :toggle-collapse kb/toggle-collapse-command})

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