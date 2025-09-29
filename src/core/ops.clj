(ns core.ops
  "Pure operations for the kernel.
   
   Three frozen primitives: create-node, place, update-node.
   All operations are pure functions that return updated database."
  (:require [core.db :as db]))

(defn- insert-at-index
  "Inserts id at index j in vector v, clamping j to valid bounds."
  [v j id]
  (let [j' (db/clamp-index (or j (count v)) 0 (count v))]
    (vec (concat (subvec v 0 j') [id] (subvec v j')))))

(defn- place-child-in-parent
  "Updates children-by-parent to place id under parent pid at position at.
   
   First removes id from all existing parents, then places it in the target parent."
  [children-by-parent id parent-id at]
  (let [children-by-parent' (into {} (for [[p kids] children-by-parent]
                                       [p (db/remove-element kids id)]))
        target-children (get children-by-parent' parent-id [])]
    (assoc children-by-parent' parent-id
           (cond
             (= at :first)
             (vec (cons id target-children))

             (= at :last)
             (conj target-children id)

             (map? at)
             (let [[k ref] (first at)
                   j (case k
                       :before (.indexOf target-children ref)
                       :after (let [i (.indexOf target-children ref)]
                                (if (neg? i) (count target-children) (inc i))))]
               (insert-at-index target-children j id))

             (int? at)
             (insert-at-index target-children at id)

             :else target-children))))

(defn apply-op
  "Applies a single operation to the database.
   
   Returns updated database without derivation (that happens in interpret)."
  [db {:keys [op id under at type props] :as operation}]
  (case op
    :create-node
    (-> db
        (assoc-in [:nodes id] {:type type :props props})
        (update :children-by-parent place-child-in-parent id under at))

    :place
    (update db :children-by-parent place-child-in-parent id under at)

    :update-node
    (update-in db [:nodes id :props] #(merge % props))

    ;; Unknown operations are handled by interpret layer - just return db unchanged
    db))