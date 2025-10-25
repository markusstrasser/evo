(ns kernel.db
  "Canonical DB shape, derive function, invariants, and tree utilities for the three-op kernel."
  (:require [medley.core :as m]
            [clojure.set :as set]
            [kernel.constants :as const]
            [plugins.registry :as plugins])
  #?(:cljs (:require [goog.string :as gstr]
                     [goog.string.format])))

(defn empty-db
  "Create an empty database with canonical shape."
  []
  {:nodes {"session"                  {:type :session-root :props {}}
           const/session-selection-id {:type :selection    :props {:nodes #{} :focus nil :anchor nil}}}
   :children-by-parent {const/root-session [const/session-selection-id]}
   :roots const/roots
   :ui {:editing-block-id nil
        :cursor {}}  ; Map of block-id -> {:first-row? bool :last-row? bool}
   :derived {:parent-of {}
             :index-of {}
             :prev-id-of {}
             :next-id-of {}
             :pre {}
             :post {}
             :id-by-pre {}
             :doc/pre {}
             :doc/id-by-pre {}}})

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

(defn- under-doc-root?
  "Check if a node ID is under the :doc root by walking up parent chain."
  [parent-of id]
  (loop [current id]
    (cond
      (= current const/root-doc) true
      (keyword? current) false  ; reached a different root
      (nil? current) false       ; no parent found
      :else (recur (get parent-of current)))))

(defn- filter-doc-traversal
  "Derive doc-only traversal from all-roots traversal by filtering.

   Filters to nodes under :doc root and renumbers them sequentially."
  [parent-of id-by-pre]
  (let [;; Get all [idx id] pairs from id-by-pre, already sorted by index (key)
        all-ids (sort-by first (seq id-by-pre))
        ;; Filter to only nodes under :doc (walk up parent chain)
        doc-ids (filter (fn [[_idx id]] (under-doc-root? parent-of id)) all-ids)
        ;; Renumber filtered IDs sequentially
        renumbered (map-indexed (fn [new-idx [_old-idx id]] [id new-idx]) doc-ids)]
    {:doc/pre (into {} renumbered)
     :doc/id-by-pre (into {} (map (fn [[id idx]] [idx id]) renumbered))}))

(defn derive-indexes
  "Recompute all derived maps from canonical DB state. O(n) operation.

   Computes core derived indexes (parent-of, index-of, etc.) and then
   merges in results from registered plugins."
  [db]
  (let [{:keys [children-by-parent roots]} db
        ;; Core derived indexes
        parent-of (compute-parent-of children-by-parent)
        ;; Compute single canonical traversal over all roots
        all-roots-traversal (compute-traversal children-by-parent roots)
        ;; Derive doc-only indexes by filtering the main traversal
        doc-indexes (filter-doc-traversal parent-of (:id-by-pre all-roots-traversal))

        core-derived (merge {:parent-of parent-of
                             :index-of (compute-index-of children-by-parent)}
                            (compute-siblings children-by-parent)
                            all-roots-traversal
                            doc-indexes)
        db-with-core (assoc db :derived core-derived)]
    (assoc db :derived (merge core-derived (plugins/run-all db-with-core)))))

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
  (let [valid-parents (set/union (set roots) (set (keys nodes)))]
    (for [parent (keys children-by-parent)
          :when (not (contains? valid-parents parent))]
      (fmt "Parent %s is neither in :roots nor :nodes" parent))))

(defn- detect-cycle
  "Check if id has a cycle by walking up the parent chain.
   Returns true if cycle detected, false otherwise."
  [id parent-of roots]
  (let [roots-set (set roots)]
    (loop [current id
           visited #{}]
      (cond
        (contains? visited current) true
        (contains? roots-set current) false
        :else (if-some [parent (get parent-of current)]
                (recur parent (conj visited current))
                false)))))

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
  (let [db-for-recompute (assoc db :derived {})
        recomputed-db (derive-indexes db-for-recompute)
        recomputed-derived (:derived recomputed-db)]
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
                    (mapcat identity)
                    (remove nil?)
                    vec)
        result {:ok? (empty? errors)
                :errors errors}]
    result))