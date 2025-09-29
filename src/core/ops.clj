(ns core.ops
  "Pure operations for the three-op kernel: create-node, place, update-node.")

(defn create-node
  "Create a node shell. Idempotent - if node exists, no change.
   
   Args:
     db - database 
     id - node identifier
     type - node type keyword
     props - node properties map
     
   Returns:
     Updated database"
  [db id type props]
  (if (contains? (:nodes db) id)
    db ; idempotent - node already exists
    (assoc-in db [:nodes id] {:type type :props props})))

(defn- resolve-at-position
  "Resolve :at anchor to concrete index within siblings list.
   
   Args:
     siblings - vector of sibling IDs
     at - anchor specification
     
   Returns:
     Concrete index (clamped to valid range)"
  [siblings at]
  (let [max-idx (count siblings)]
    (cond
      (integer? at)
      (max 0 (min at max-idx))

      (= at :first)
      0

      (= at :last)
      max-idx

      (map? at)
      (cond
        (:before at)
        (let [target-id (:before at)
              target-idx (.indexOf siblings target-id)]
          (if (>= target-idx 0)
            target-idx
            max-idx)) ; if target not found, append at end

        (:after at)
        (let [target-id (:after at)
              target-idx (.indexOf siblings target-id)]
          (if (>= target-idx 0)
            (inc target-idx)
            max-idx)) ; if target not found, append at end

        :else max-idx)

      :else max-idx)))

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
  [old new]
  (cond
    (and (map? old) (map? new))
    (merge-with deep-merge old new)

    :else
    new))

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