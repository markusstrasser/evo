(ns core.ops
  "Pure operations for the three-op kernel: create-node, place, update-node.")

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

(defn- find-anchor-index
  "Find index of target-id in siblings, returning -1 if not found."
  [siblings target-id]
  (.indexOf siblings target-id))

(defn- resolve-relative-anchor
  "Resolve {:before id} or {:after id} to concrete index.
   Returns append-position if target not found."
  [siblings anchor-map append-position]
  (let [[relation target-id] (or (find anchor-map :before)
                                  (find anchor-map :after))
        target-idx (find-anchor-index siblings target-id)]
    (if (neg? target-idx)
      append-position
      (cond-> target-idx
        (= relation :after) inc))))

(defn- resolve-at-position
  "Resolve :at anchor to concrete index within siblings list.

   Anchor types:
     - integer: direct index (clamped to [0, count])
     - :first/:last: start or end position
     - {:before id}: insert before target (or end if not found)
     - {:after id}: insert after target (or end if not found)

   Args:
     siblings - vector of sibling IDs
     at - anchor specification

   Returns:
     Concrete index (clamped to valid range)"
  [siblings at]
  (let [append-position (count siblings)]
    (cond
      (integer? at)     (max 0 (min at append-position))
      (= at :first)     0
      (= at :last)      append-position
      (map? at)         (resolve-relative-anchor siblings at append-position)
      :else             append-position)))

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
  (let [db-after-remove (remove-from-current-parent db id)
        siblings (get-in db-after-remove [:children-by-parent under] [])
        target-idx (resolve-at-position siblings at)
        updated-siblings (insert-child-at-position siblings id target-idx)]
    (assoc-in db-after-remove [:children-by-parent under] updated-siblings)))

(defn- deep-merge
  "Recursively merge maps. For nested maps, merge recursively.
   For non-map values, the new value overwrites the old."
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