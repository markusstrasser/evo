(ns core.db
  "Canonical database shape and derivation functions.
   
   The kernel owns the canonical structure:
   {:nodes {} :children-by-parent {} :derived {}}
   
   Derived data is recomputed after every transaction.")

(defn empty-db
  "Creates an empty database with canonical structure."
  []
  {:nodes {}
   :children-by-parent {}
   :derived {}})

(defn remove-element
  "Removes element x from vector v."
  [v x]
  (vec (remove #{x} v)))

(defn clamp-index
  "Clamps index i to bounds [lo, hi]."
  [i lo hi]
  (-> i (max lo) (min hi)))

(defn- rebuild-parent-index
  "Builds parent-of map from children-by-parent."
  [children-by-parent]
  (reduce-kv (fn [m p kids]
               (reduce #(assoc %1 %2 p) m kids))
             {}
             children-by-parent))

(defn- rebuild-sibling-index
  "Builds index-of map from children-by-parent."
  [children-by-parent]
  (reduce-kv (fn [m _ kids]
               (reduce-kv (fn [mi i id] (assoc mi id i)) m kids))
             {}
             children-by-parent))

(defn- rebuild-prev-next-index
  "Builds prev-id-of and next-id-of maps from children-by-parent."
  [children-by-parent]
  (let [pairs (for [[_ kids] children-by-parent
                    :let [n (count kids)]
                    i (range n)]
                (let [id (kids i)
                      prev (when (pos? i) (kids (dec i)))
                      next (when (< (inc i) n) (kids (inc i)))]
                  [id prev next]))]
    {:prev-id-of (into {} (map (fn [[id p _]] [id p]) pairs))
     :next-id-of (into {} (map (fn [[id _ n]] [id n]) pairs))}))

(defn derive
  "Recomputes all derived data from canonical structure.
   
   Returns db with updated :derived containing:
   - :parent-of - map of child-id -> parent-id  
   - :index-of - map of child-id -> index in parent's children
   - :prev-id-of - map of child-id -> previous sibling id
   - :next-id-of - map of child-id -> next sibling id"
  [db]
  (let [children-by-parent (:children-by-parent db)
        parent-of (rebuild-parent-index children-by-parent)
        index-of (rebuild-sibling-index children-by-parent)
        prev-next (rebuild-prev-next-index children-by-parent)]
    (assoc db :derived (merge {:parent-of parent-of
                               :index-of index-of}
                              prev-next))))