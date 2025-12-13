(ns kernel.db
  "Canonical DB shape, derive function, invariants, and tree utilities for the three-op kernel."
  (:require [medley.core :as m]
            [clojure.set :as set]
            [kernel.constants :as const]
            [plugins.registry :as plugins])
  #?(:cljs (:require [goog.string :as gstr]
                     [goog.string.format])))

(defn- compute-parent-of [children-by-parent]
  (into {}
        (for [[parent children] children-by-parent
              child children]
          [child parent])))

(defn- compute-index-of [children-by-parent]
  (into {}
        (for [[_parent children] children-by-parent
              [idx child] (map-indexed vector children)]
          [child idx])))

(defn- compute-siblings [children-by-parent]
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
            (let [state-with-pre (update state :pre-order conj id)
                  children (get children-by-parent id [])
                  state-with-children (reduce visit-node state-with-pre children)]
              (update state-with-children :post-order conj id)))]

    (let [{:keys [pre-order post-order]}
          (reduce (fn [state root]
                    (if (contains? children-by-parent root)
                      (visit-node state root)
                      state))
                  {:pre-order [] :post-order []}
                  roots)

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
  "Derive doc-only traversal from all-roots traversal by filtering."
  [parent-of id-by-pre]
  (let [all-ids (sort-by first (seq id-by-pre))
        doc-ids (filter (fn [[_idx id]] (under-doc-root? parent-of id)) all-ids)
        renumbered (map-indexed (fn [new-idx [_old-idx id]] [id new-idx]) doc-ids)]
    {:doc/pre (into {} renumbered)
     :doc/id-by-pre (into {} (map (fn [[id idx]] [idx id]) renumbered))}))

(defn derive-indexes
  "Recompute all derived maps from canonical DB state. O(n) operation.

   Computes core derived indexes (parent-of, index-of, etc.) and then
   merges in results from registered plugins."
  [db]
  (let [{:keys [children-by-parent roots]} db
        parent-of (compute-parent-of children-by-parent)
        all-roots-traversal (compute-traversal children-by-parent roots)
        doc-indexes (filter-doc-traversal parent-of (:id-by-pre all-roots-traversal))

        core-derived (merge {:parent-of parent-of
                             :index-of (compute-index-of children-by-parent)}
                            (compute-siblings children-by-parent)
                            all-roots-traversal
                            doc-indexes)
        db-with-core (assoc db :derived core-derived)]
    (assoc db :derived (merge core-derived (plugins/run-all db-with-core)))))

;; =============================================================================
;; Empty DB Constructor
;; =============================================================================

(defn- empty-db-skeleton
  "Create empty DB skeleton without derived indexes.
   Internal - use empty-db which computes derived."
  []
  {:nodes {}
   :children-by-parent {}
   :roots const/roots
   :derived {}})

(defn empty-db
  "Create an empty database with canonical shape.

   Phases 4 & 5: Session nodes removed - ephemeral state (cursor, selection,
   fold, zoom, buffer) lives purely in shell.view-state atom.

   DB now contains only the persistent document graph.

   Derived indexes are computed dynamically by derive-indexes, which includes
   any registered plugins. This ensures empty-db always validates correctly."
  []
  (derive-indexes (empty-db-skeleton)))

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

;; =============================================================================
;; Public tree utilities (used by transaction, struct, etc.)
;; =============================================================================

(defn check-parent-of-consistency
  "DEBUG: Check if :derived :parent-of matches :children-by-parent.

   Returns nil if consistent, or a map describing the inconsistency.
   Use this when debugging validation errors caused by stale derived indexes."
  [db]
  (let [expected-parent-of (compute-parent-of (:children-by-parent db))
        actual-parent-of (get-in db [:derived :parent-of])
        mismatches (for [[child expected-parent] expected-parent-of
                         :let [actual-parent (get actual-parent-of child)]
                         :when (not= expected-parent actual-parent)]
                     {:child child
                      :expected-parent expected-parent
                      :actual-parent actual-parent})
        ;; Also check for entries in actual that shouldn't exist
        orphans (for [[child actual-parent] actual-parent-of
                      :when (and (not (contains? expected-parent-of child))
                                 (not (nil? actual-parent)))]
                  {:child child
                   :orphan-parent actual-parent})]
    (when (or (seq mismatches) (seq orphans))
      {:mismatches (vec mismatches)
       :orphans (vec orphans)})))

(defn valid-parent?
  "Check if parent is valid (either a root or an existing node)."
  [db parent]
  (or (some #{parent} (:roots db))
      (contains? (:nodes db) parent)))

(defn descendant-of?
  "Check if potential-descendant is a descendant of potential-ancestor.
   Walks up the parent chain from :derived :parent-of map.

   Note: Assumes :derived indexes are up-to-date."
  [db potential-ancestor potential-descendant]
  (let [parent-of (get-in db [:derived :parent-of])
        roots (set (:roots db))]
    (loop [current potential-descendant]
      (cond
        (nil? current) false
        (= current potential-ancestor) true
        (contains? roots current) false
        :else (recur (get parent-of current))))))

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