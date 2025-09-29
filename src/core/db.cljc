(ns core.db
  "Canonical DB shape, derive function, and invariants for the three-op kernel."
  (:require [clojure.set :as set]
            #?(:cljs [goog.string :as gstr])
            #?(:cljs [goog.string.format])))

(defn empty-db
  "Create an empty database with canonical shape."
  []
  {:nodes {}
   :children-by-parent {}
   :roots #{:doc :trash}
   :derived {:parent-of {}
             :index-of {}
             :prev-id-of {}
             :next-id-of {}
             :pre {}
             :post {}
             :id-by-pre {}}})

(defn- compute-parent-of
  "Compute :parent-of map from :children-by-parent."
  [children-by-parent]
  (into {}
        (for [[parent children] children-by-parent
              child children]
          [child parent])))

(defn- compute-index-of
  "Compute :index-of map - position of each child within its parent's children list."
  [children-by-parent]
  (into {}
        (for [[_parent children] children-by-parent
              [idx child] (map-indexed vector children)]
          [child idx])))

(defn- compute-siblings
  "Compute prev/next sibling relationships."
  [children-by-parent]
  (let [prev-next (for [[_parent children] children-by-parent
                        [prev curr next] (partition 3 1 (concat [nil] children [nil]))
                        :when curr]
                    [curr prev next])]
    {:prev-id-of (into {} (map (fn [[curr prev _next]] [curr prev]) prev-next))
     :next-id-of (into {} (map (fn [[curr _prev next]] [curr next]) prev-next))}))

(defn- compute-traversal
  "Compute pre-order and post-order traversal indexes."
  [children-by-parent roots]
  (let [pre-order (atom [])
        post-order (atom [])
        pre-counter (atom 0)
        post-counter (atom 0)]

    (letfn [(visit [id]
              (let [pre-idx @pre-counter]
                (swap! pre-order conj id)
                (swap! pre-counter inc)

                (doseq [child (get children-by-parent id [])]
                  (visit child))

                (let [post-idx @post-counter]
                  (swap! post-order conj id)
                  (swap! post-counter inc)
                  [pre-idx post-idx])))]

      (doseq [root roots]
        (when (contains? children-by-parent root)
          (visit root)))

      (let [pre-vec @pre-order
            post-vec @post-order]
        {:pre (into {} (map-indexed (fn [idx id] [id idx]) pre-vec))
         :post (into {} (map-indexed (fn [idx id] [id idx]) post-vec))
         :id-by-pre (into {} (map-indexed (fn [idx id] [idx id]) pre-vec))}))))

(defn derive-indexes
  "Recompute all derived maps from canonical DB state. O(n) operation."
  [db]
  (let [{:keys [children-by-parent roots]} db
        parent-of (compute-parent-of children-by-parent)
        index-of (compute-index-of children-by-parent)
        {:keys [prev-id-of next-id-of]} (compute-siblings children-by-parent)
        {:keys [pre post id-by-pre]} (compute-traversal children-by-parent roots)]

    (assoc db :derived
           {:parent-of parent-of
            :index-of index-of
            :prev-id-of prev-id-of
            :next-id-of next-id-of
            :pre pre
            :post post
            :id-by-pre id-by-pre})))

(defn validate
  "Validate database invariants. Returns {:ok? bool :errors [...]}"
  [db]
  (let [{:keys [nodes children-by-parent roots derived]} db

        errors (concat
                (for [[parent children] children-by-parent
                      child children
                      :when (not (contains? nodes child))]
                  #?(:clj (format "Child %s of parent %s does not exist in :nodes" child parent)
                     :cljs (gstr/format "Child %s of parent %s does not exist in :nodes" child parent)))

                (for [[parent children] children-by-parent
                      :when (not= (count children) (count (set children)))]
                  #?(:clj (format "Parent %s has duplicate children: %s" parent children)
                     :cljs (gstr/format "Parent %s has duplicate children: %s" parent children)))

                (let [child->parents (group-by first
                                               (for [[parent children] children-by-parent
                                                     child children]
                                                 [child parent]))]
                  (for [[child parent-entries] child->parents
                        :when (> (count parent-entries) 1)]
                    #?(:clj (format "Child %s has multiple parents: %s" child (map second parent-entries))
                       :cljs (gstr/format "Child %s has multiple parents: %s" child (map second parent-entries)))))

                (for [parent (keys children-by-parent)
                      :when (not (or (contains? roots parent)
                                     (contains? nodes parent)))]
                  #?(:clj (format "Parent %s is neither in :roots nor :nodes" parent)
                     :cljs (gstr/format "Parent %s is neither in :roots nor :nodes" parent)))

                (let [has-cycle? (fn has-cycle? [id visited]
                                   (cond
                                     (contains? visited id) true
                                     (contains? roots id) false
                                     :else (let [parent (get-in derived [:parent-of id])]
                                             (if parent
                                               (has-cycle? parent (conj visited id))
                                               false))))]
                  (for [id (keys nodes)
                        :when (has-cycle? id #{})]
                    #?(:clj (format "Node %s is part of a cycle" id)
                       :cljs (gstr/format "Node %s is part of a cycle" id))))

                (for [[child parent] (get derived :parent-of)
                      :when (= child parent)]
                  #?(:clj (format "Node %s is its own parent" child)
                     :cljs (gstr/format "Node %s is its own parent" child)))

                (let [recomputed-derived (:derived (derive-indexes (assoc db :derived {})))]
                  (when (not= derived recomputed-derived)
                    [":derived is stale - does not match recomputed version"])))]

    {:ok? (empty? errors)
     :errors (vec errors)}))