(ns evolver.registry
  "Unified command registry for the evolver system")

(def command-registry
  "Registry of all available commands with metadata"
  [{:id :insert
    :label "Insert Node"
    :doc "Inserts a new node into the tree"
    :params [:parent-id :node-id :node-data :position]
    :keys ["i"]
    :handler (fn [ctx] ctx)} ; Placeholder, will be implemented

   {:id :move
    :label "Move Node"
    :doc "Moves a node to a new parent"
    :params [:node-id :new-parent-id :position]
    :keys ["m"]
    :handler (fn [ctx] ctx)}

   {:id :patch
    :label "Patch Node"
    :doc "Updates properties of a node"
    :params [:node-id :updates]
    :keys ["p"]
    :handler (fn [ctx] ctx)}

   {:id :delete
    :label "Delete Node"
    :doc "Removes a node from the tree"
    :params [:node-id :recursive]
    :keys ["d"]
    :handler (fn [ctx] ctx)}

   {:id :reorder
    :label "Reorder Nodes"
    :doc "Changes the order of sibling nodes"
    :params [:node-id :parent-id :from-index :to-index]
    :keys ["r"]
    :handler (fn [ctx] ctx)}

   {:id :add-reference
    :label "Add Reference"
    :doc "Creates a reference between nodes"
    :params [:from-node-id :to-node-id]
    :keys ["a"]
    :handler (fn [ctx] ctx)}

   {:id :remove-reference
    :label "Remove Reference"
    :doc "Removes a reference between nodes"
    :params [:from-node-id :to-node-id]
    :keys ["x"]
    :handler (fn [ctx] ctx)}

   {:id :undo
    :label "Undo"
    :doc "Undoes the last operation"
    :params []
    :keys ["u"]
    :handler (fn [ctx] ctx)}

   {:id :redo
    :label "Redo"
    :doc "Redoes the last undone operation"
    :params []
    :keys ["U"]
    :handler (fn [ctx] ctx)}])

(defn get-command-by-id
  "Get command metadata by id"
  [id]
  (first (filter #(= (:id %) id) command-registry)))

(defn get-keyboard-mappings
  "Generate keyboard mappings from registry"
  []
  (into {}
        (map (fn [cmd]
               (when (:keys cmd)
                 [(first (:keys cmd)) [(:id cmd) {}]]))
             command-registry)))

(defn get-ui-commands
  "Get commands suitable for UI display"
  []
  (filter #(not (#{:undo :redo} (:id %))) command-registry))