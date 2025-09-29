(ns labs.structure.sugar-ops
  "Sugar operations that lower to 3-op kernel.
   
   These are convenience operations that decompose to create-node, place, update-node."
  (:require [core.schema :as schema]))

(defn insert
  "Create node then place it under parent.
   Returns sequence of core ops."
  [{:keys [id parent-id type props anchor]}]
  [{:op :create-node
    :id id
    :type (or type :div)
    :props (or props {})}
   {:op :place
    :id id
    :under parent-id
    :at (or anchor :last)}])

(defn move
  "Move node to new parent.
   Returns sequence of core ops."
  [{:keys [id target-parent-id anchor]}]
  [{:op :place
    :id id
    :under target-parent-id
    :at (or anchor :last)}])

(defn delete
  "Delete node by moving to trash.
   Returns sequence of core ops."
  [{:keys [id]}]
  [{:op :place :id id :under :trash :at :last}])

(defn reorder
  "Reposition within current parent.
   Returns sequence of core ops."
  [{:keys [id parent-id anchor]}]
  [{:op :place :id id :under parent-id :at anchor}])

(defn lower-sugar-op
  "Lower a single sugar operation to core ops."
  [op]
  (case (:op op)
    :insert (insert op)
    :move (move op)
    :delete (delete op)
    :reorder (reorder op)
    ;; Unknown ops pass through
    [op]))

(defn lower-sugar-ops
  "Lower sequence of sugar operations to core ops."
  [ops]
  (mapcat lower-sugar-op ops))