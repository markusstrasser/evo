(ns labs.structure.wrap
  "Structural editing operations as lowerings to 3-op kernel.
   Each operation returns a sequence of {create,place,update} ops."
  (:require [core.schema :refer [gen-id]]))

(defn wrap-range
  "Wrap a range of sibling nodes under a new parent.
   Returns sequence of 3-ops: create wrapper, then place each child under wrapper."
  [{:keys [parent ids new-id type props]}]
  (let [wrapper-id (or new-id (gen-id))]
    (concat
      ;; Create wrapper at position of first child
     [{:op :create-node
       :id wrapper-id
       :type (or type :div)
       :props (or props {})
       :under parent
       :at {:before (first ids)}}]
      ;; Move all children under wrapper
     (for [id ids]
       {:op :place :id id :under wrapper-id :at :last}))))

(defn unwrap
  "Remove wrapper node, moving its children to wrapper's parent.
   Returns sequence of 3-ops: place each child, then trash wrapper."
  [{:keys [wrapper-id db]}]
  (let [wrapper (get-in db [:nodes wrapper-id])
        parent-id (get-in db [:parent-by-child wrapper-id])
        children (get-in db [:children-by-parent wrapper-id] [])]
    (concat
      ;; Move children to wrapper's parent 
     (for [child-id children]
       {:op :place :id child-id :under parent-id :at :last})
      ;; Remove wrapper
     [{:op :place :id wrapper-id :under :trash :at :last}])))

(defn split-node
  "Split node at cursor position, creating new sibling.
   Requires adapter-level cursor/content handling."
  [{:keys [node-id split-point new-id type]}]
  (let [new-node-id (or new-id (gen-id))]
    [{:op :update-node :id node-id :props {:split-at split-point}}
     {:op :create-node :id new-node-id :type (or type :text) :props {:split-from node-id}}
     {:op :place :id new-node-id :under :same-parent :at {:after node-id}}]))

(defn merge-nodes
  "Merge second node into first, remove second.
   Requires adapter-level content merging."
  [{:keys [first-id second-id]}]
  [{:op :update-node :id first-id :props {:merge-from second-id}}
   {:op :place :id second-id :under :trash :at :last}])

(defn move-section
  "Move a section of nodes to new location."
  [{:keys [ids target-parent anchor]}]
  (for [id ids]
    {:op :place :id id :under target-parent :at anchor}))