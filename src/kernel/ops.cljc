(ns kernel.ops
  "Pure operations for the kernel: create-node, place, update-node, delete-node.

   READER GUIDE:
   ─────────────
   This is the kernel's operation layer. Four operations:
   1. create-node - Add a node shell to :nodes (idempotent)
   2. place - Position node under parent at anchor (remove from old parent first)
   3. update-node - Merge props into existing node
   4. delete-node - Permanently remove node from database

   ONE LAW: All operations are pure functions (DB → DB). No side effects, no validation.
   Validation happens in transaction layer, derivation happens in derive-indexes.
   These ops just transform canonical state."
  (:require [kernel.position :as pos]))

(defn create-node
  "Create a node shell. Idempotent - if node exists, no change.

   Args:
     db - database
     id - node identifier
     node-type - node type keyword
     props - node properties map

   Returns:
     Updated database"
  [db id node-type props]
  (if (contains? (:nodes db) id)
    db
    (assoc-in db [:nodes id] {:type node-type :props props})))

(defn- remove-child-from-parent
  "Remove child-id from its current parent's children list.
   If parent's children become empty, removes the parent key entirely.

   Args:
     db - database
     parent - parent ID or root keyword
     children - parent's children vector
     child-id - ID to remove

   Returns:
     Updated database (or unchanged if child not found)"
  [db parent children child-id]
  (let [filtered-children (filterv #(not= % child-id) children)]
    (cond
      ;; Child wasn't in this parent's list - no change
      (= (count filtered-children) (count children))
      db

      ;; Parent now has no children - remove parent key
      (empty? filtered-children)
      (update db :children-by-parent dissoc parent)

      ;; Parent still has children - update list
      :else
      (assoc-in db [:children-by-parent parent] filtered-children))))

(defn- remove-from-current-parent
  "Remove node from whichever parent currently contains it.
   Scans all parents to find and remove the node.

   Args:
     db - database
     node-id - node to remove

   Returns:
     Database with node removed from its current parent"
  [db node-id]
  (reduce-kv
   (fn [acc parent children]
     (remove-child-from-parent acc parent children node-id))
   db
   (:children-by-parent db)))

(defn- insert-child-at-position
  "Insert child-id into siblings at the specified position.

   Args:
     siblings - vector of current sibling IDs
     child-id - ID to insert
     target-idx - index where child should be inserted

   Returns:
     Vector with child-id inserted at target-idx"
  [siblings child-id target-idx]
  (into []
        (concat (take target-idx siblings)
                [child-id]
                (drop target-idx siblings))))

(defn- resolve-target-position
  "Resolve the target index for placing a node, simulating removal first.

   Args:
     db - database
     under - parent (ID or root keyword)
     at - position anchor
     drop-id - ID to simulate removing before resolution

   Returns:
     Target index for insertion"
  [db under at drop-id]
  (let [siblings (get-in db [:children-by-parent under] [])]
    (pos/resolve-insert-index siblings at {:drop-id drop-id})))

(defn- insert-at-resolved-position
  "Insert node at resolved position in parent's children.

   Args:
     db - database
     id - node to insert
     under - parent (ID or root keyword)
     target-idx - resolved index for insertion

   Returns:
     Updated database"
  [db id under target-idx]
  (let [siblings (get-in db [:children-by-parent under] [])
        updated-siblings (insert-child-at-position siblings id target-idx)]
    (assoc-in db [:children-by-parent under] updated-siblings)))

(defn place
  "Move node to new parent at specified position. Three phases: remove, resolve, insert.

   Args:
     db - database
     id - node to move
     under - parent (ID or root keyword)
     at - position anchor

   Returns:
     Updated database"
  [db id under at]
  (let [target-idx (resolve-target-position db under at id)]
    (-> db
        (remove-from-current-parent id)
        (insert-at-resolved-position id under target-idx))))

(defn deep-merge
  "Recursively merge maps. For nested maps, merge recursively.
   For non-map values, the new value overwrites the old.

   Used by update-node and transaction normalization."
  [old-val new-val]
  (cond
    (and (map? old-val) (map? new-val))
    (merge-with deep-merge old-val new-val)

    :else
    new-val))

(defn update-node
  "Update node properties using recursive merge.
   Scalars overwrite, nested maps merge recursively.

   Args:
     db - database
     id - node to update
     props - properties to merge

   Returns:
     Updated database"
  [db id props]
  (if (contains? (:nodes db) id)
    (update-in db [:nodes id :props] #(deep-merge % props))
    db))

(defn delete-node
  "Permanently delete a node from the database.
   Removes from :nodes map and from its parent's children list.
   
   NOTE: This does NOT delete descendants - caller must delete children first.
   
   Args:
     db - database
     id - node to delete
   
   Returns:
     Updated database with node removed"
  [db id]
  (-> db
      (update :nodes dissoc id)
      (remove-from-current-parent id)))