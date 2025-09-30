(ns core.db
  "Canonical DB shape, derive function, and invariants for the three-op kernel."
  (:require [medley.core :as m]
            [clojure.set :as set]
            [plugins.registry :as plugins])
  #?(:cljs (:require [goog.string :as gstr]
                     [goog.string.format])))

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
                        [prev-sib curr next-sib] (partition 3 1 (concat [nil] children [nil]))
                        :when curr]
                    [curr prev-sib next-sib])]
    {:prev-id-of (into {} (map (fn [[curr prev-sib _]] [curr prev-sib]) prev-next))
     :next-id-of (into {} (map (fn [[curr _ next-sib]] [curr next-sib]) prev-next))}))

(defn- compute-traversal
  "Compute pre-order and post-order traversal indexes.

   Returns a map with:
   - :pre      - map of id->index in pre-order traversal
   - :post     - map of id->index in post-order traversal
   - :id-by-pre - map of index->id for pre-order lookup"
  [children-by-parent roots]
  (letfn [(visit-node [state id]
            ;; Pre-order: record node BEFORE visiting children
            (let [state-with-pre (update state :pre-order conj id)
                  children (get children-by-parent id [])
                  ;; Recursively visit all children, threading state
                  state-with-children (reduce visit-node state-with-pre children)]
              ;; Post-order: record node AFTER visiting children
              (update state-with-children :post-order conj id)))]

    ;; 1. Traverse tree from all roots, accumulating pre/post vectors
    (let [{:keys [pre-order post-order]}
          (reduce (fn [state root]
                    ;; Only visit roots that have children or are leaves
                    (if (contains? children-by-parent root)
                      (visit-node state root)
                      state))
                  {:pre-order [] :post-order []}
                  roots)

          ;; 2. Convert traversal vectors to lookup maps
          ;; For pre/post: id -> index (e.g., "n1" -> 1)
          ;; For id-by-pre: index -> id (e.g., 1 -> "n1")
          id->pre-idx (->> pre-order
                           m/indexed
                           (map (fn [[idx id]] [id idx]))
                           (into {}))
          id->post-idx (->> post-order
                            m/indexed
                            (map (fn [[idx id]] [id idx]))
                            (into {}))
          pre-idx->id (into {} (m/indexed pre-order))]

      {:pre id->pre-idx
       :post id->post-idx
       :id-by-pre pre-idx->id})))

(defn derive-indexes
  "Recompute all derived maps from canonical DB state. O(n) operation.

   Computes core derived indexes (parent-of, index-of, etc.) and then
   merges in results from registered plugins."
  [db]
  (let [{:keys [children-by-parent roots]} db
        parent-of (compute-parent-of children-by-parent)
        index-of (compute-index-of children-by-parent)
        {:keys [prev-id-of next-id-of]} (compute-siblings children-by-parent)
        {:keys [pre post id-by-pre]} (compute-traversal children-by-parent roots)
        core-derived {:parent-of parent-of
                      :index-of index-of
                      :prev-id-of prev-id-of
                      :next-id-of next-id-of
                      :pre pre
                      :post post
                      :id-by-pre id-by-pre}
        ;; Run plugins to get additional derived data
        db-with-core-derived (assoc db :derived core-derived)
        plugin-data (plugins/run-all db-with-core-derived)]

    ;; Merge plugin data into derived (plugins can add new keys but not override core)
    (assoc db :derived (merge core-derived plugin-data))))

;; =============================================================================
;; Validation Helpers
;; =============================================================================

(defn- fmt
  "Cross-platform format helper."
  [template & args]
  (apply #?(:clj format :cljs gstr/format) template args))

(defn- validate-children-exist
  "Check that all children in :children-by-parent exist in :nodes."
  [nodes children-by-parent]
  (for [[parent children] children-by-parent
        child children
        :when (not (contains? nodes child))]
    (fmt "Child %s of parent %s does not exist in :nodes" child parent)))

(defn- validate-no-duplicate-children
  "Check that no parent has duplicate children."
  [children-by-parent]
  (for [[parent children] children-by-parent
        :let [duplicates (m/filter-vals #(> % 1) (frequencies children))]
        :when (seq duplicates)]
    (fmt "Parent %s has duplicate children: %s" parent (keys duplicates))))

(defn- validate-single-parent
  "Check that each child has at most one parent."
  [children-by-parent]
  (let [child->parents (->> children-by-parent
                            (mapcat (fn [[parent children]]
                                      (map #(vector % parent) children)))
                            (group-by first)
                            (m/filter-vals #(> (count %) 1)))]
    (for [[child parent-entries] child->parents]
      (fmt "Child %s has multiple parents: %s" child (map second parent-entries)))))

(defn- validate-parents-exist
  "Check that all parents are either roots or nodes."
  [children-by-parent roots nodes]
  (let [valid-parents (set/union roots (set (keys nodes)))]
    (for [parent (keys children-by-parent)
          :when (not (contains? valid-parents parent))]
      (fmt "Parent %s is neither in :roots nor :nodes" parent))))

(defn- detect-cycle
  "Check if id has a cycle by walking up the parent chain.
   Returns true if cycle detected, false otherwise."
  [id parent-of roots]
  (loop [current id
         visited #{}]
    (cond
      (contains? visited current) true
      (contains? roots current) false
      :else (if-some [parent (get parent-of current)]
              (recur parent (conj visited current))
              false))))

(defn- validate-no-cycles
  "Check that no node is part of a cycle."
  [nodes derived roots]
  (let [parent-of (get derived :parent-of)]
    (for [id (keys nodes)
          :when (detect-cycle id parent-of roots)]
      (fmt "Node %s is part of a cycle" id))))

(defn- validate-no-self-parent
  "Check that no node is its own parent."
  [derived]
  (for [[child parent] (get derived :parent-of)
        :when (= child parent)]
    (fmt "Node %s is its own parent" child)))

(defn- validate-derived-fresh
  "Check that :derived matches a fresh recomputation."
  [db]
  (let [recomputed-derived (:derived (derive-indexes (assoc db :derived {})))]
    (when (not= (:derived db) recomputed-derived)
      [":derived is stale - does not match recomputed version"])))

(defn validate
  "Validate database invariants. Returns {:ok? bool :errors [...]}.

   Checks performed:
   - All children in :children-by-parent exist in :nodes
   - No parent has duplicate children
   - Each child has at most one parent
   - All parents are either in :roots or :nodes
   - No cycles in the parent chain
   - No node is its own parent
   - :derived indexes are fresh"
  [db]
  (let [{:keys [nodes children-by-parent roots derived]} db

        errors (->> [(validate-children-exist nodes children-by-parent)
                     (validate-no-duplicate-children children-by-parent)
                     (validate-single-parent children-by-parent)
                     (validate-parents-exist children-by-parent roots nodes)
                     (validate-no-cycles nodes derived roots)
                     (validate-no-self-parent derived)
                     (validate-derived-fresh db)]
                    (apply concat)
                    vec)]

    {:ok? (empty? errors)
     :errors errors}))