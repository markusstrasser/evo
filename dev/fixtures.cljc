(ns fixtures
  "Generic test fixtures and utilities for kernel testing.
   NO application-specific assumptions - only builds core/db shapes.")

;; =============================================================================
;; Generic DB builders
;; =============================================================================

(defn make-db
  "Build a kernel db from nodes map and children-by-parent map.
   Automatically derives :derived indices.

   Usage:
     (make-db {\"a\" {:type :div} \"b\" {:type :span}}
              {\"a\" [\"b\"]})"
  [nodes children-by-parent]
  (let [parent-id-of (reduce-kv
                      (fn [m parent-id child-ids]
                        (reduce #(assoc %1 %2 parent-id) m child-ids))
                      {}
                      children-by-parent)
        index-of (reduce-kv
                  (fn [m parent-id child-ids]
                    (merge m (into {} (map-indexed (fn [i id] [id i]) child-ids))))
                  {}
                  children-by-parent)
        roots (reduce disj
                      (set (keys nodes))
                      (apply concat (vals children-by-parent)))]
    {:nodes nodes
     :children-by-parent children-by-parent
     :roots roots
     :derived {:parent-id-of parent-id-of
               :index-of index-of}}))

(defn random-id
  "Generate a random ID string for testing."
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))))

(defn gen-node
  "Generate a node with given type and optional props."
  [type & {:as props}]
  {:type type
   :props props})

;; =============================================================================
;; Tree generators
;; =============================================================================

(defn gen-linear-tree
  "Generate a linear tree: root -> n1 -> n2 -> ... -> nN.
   Returns {:db db :ids [root-id n1-id ... nN-id]}."
  [depth]
  (let [ids (vec (repeatedly (inc depth) random-id))
        nodes (into {} (map #(vector % (gen-node :div)) ids))
        children-by-parent (into {} (map (fn [i]
                                           [(nth ids i) [(nth ids (inc i))]])
                                         (range (dec (count ids)))))
        db (make-db nodes children-by-parent)]
    {:db db :ids ids}))

(defn gen-flat-tree
  "Generate a flat tree: root with N direct children.
   Returns {:db db :root-id id :child-ids [id1 id2 ...]}."
  [num-children]
  (let [root-id (random-id)
        child-ids (vec (repeatedly num-children random-id))
        nodes (assoc (into {} (map #(vector % (gen-node :div)) child-ids))
                     root-id (gen-node :div))
        children-by-parent {root-id child-ids}
        db (make-db nodes children-by-parent)]
    {:db db :root-id root-id :child-ids child-ids}))

(defn gen-balanced-tree
  "Generate a balanced tree with given depth and branching factor.
   Returns {:db db :root-id id}."
  [depth branching-factor]
  (letfn [(build-subtree [d]
            (let [id (random-id)]
              (if (zero? d)
                {:nodes {id (gen-node :div)}
                 :children-by-parent {}
                 :root id}
                (let [children (repeatedly branching-factor #(build-subtree (dec d)))
                      child-ids (mapv :root children)
                      merged-nodes (apply merge {id (gen-node :div)} (map :nodes children))
                      merged-children (apply merge {id child-ids} (mapcat :children-by-parent children))]
                  {:nodes merged-nodes
                   :children-by-parent merged-children
                   :root id}))))]
    (let [{:keys [nodes children-by-parent root]} (build-subtree depth)
          db (make-db nodes children-by-parent)]
      {:db db :root-id root})))

;; =============================================================================
;; Predefined fixtures
;; =============================================================================

(def empty-db
  "Minimal empty database."
  (make-db {} {}))

(def single-node-db
  "Database with a single root node."
  (let [id (random-id)]
    {:db (make-db {id (gen-node :div)} {})
     :id id}))

(def simple-tree
  "Simple 3-node tree: root -> [a, b]."
  (let [root "root"
        a "a"
        b "b"]
    {:db (make-db {root (gen-node :div)
                   a (gen-node :span)
                   b (gen-node :p)}
                  {root [a b]})
     :root-id root
     :child-ids [a b]}))

(comment
  ;; Build a custom tree
  (make-db {"root" {:type :div}
            "child" {:type :span}}
           {"root" ["child"]})

  ;; Generate random trees
  (gen-linear-tree 5)
  (gen-flat-tree 10)
  (gen-balanced-tree 3 2)

  ;; Use predefined fixtures
  (:db simple-tree)
  )