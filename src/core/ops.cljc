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
    db ; idempotent - node already exists
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

(defn place
  "Move node to new parent at specified position. Removes from current parent first.
   
   Args:
     db - database
     id - node to move
     under - parent (ID or root keyword)  
     at - position anchor
     
   Returns:
     Updated database"
  [db id under at]
  (let [children-by-parent (:children-by-parent db)

        ;; Remove from current parent (if any)
        db-removed (reduce-kv
                    (fn [acc parent children]
                      (let [filtered-children (vec (remove #(= % id) children))]
                        (if (= (count filtered-children) (count children))
                          acc ; id not found in this parent's children
                          (if (empty? filtered-children)
                            (update acc :children-by-parent dissoc parent)
                            (assoc-in acc [:children-by-parent parent] filtered-children)))))
                    db
                    children-by-parent)

        ;; Get current siblings under new parent
        current-siblings (get (:children-by-parent db-removed) under [])

        ;; Resolve position
        target-idx (resolve-at-position current-siblings at)

        ;; Insert at target position
        new-siblings (vec (concat
                           (take target-idx current-siblings)
                           [id]
                           (drop target-idx current-siblings)))]

    (assoc-in db-removed [:children-by-parent under] new-siblings)))

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
    db)) ; no-op if node doesn't exist