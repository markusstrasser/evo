(ns plugins.refs
  "Typed refs plugin for links, citations, and highlights.

   Computes derived views over refs whose :target is in :doc tree.
   Provides lint (warnings) and scrub (ops to fix dangling refs).

   Note: Selection is handled separately via plugins.selection
   which uses boolean :selected? properties (see ADR-012)."
  (:require [core.db :as db]
            [core.tree :as tree]
            [plugins.registry :as registry]))

;; =============================================================================
;; Normalization
;; =============================================================================

(defn normalize-ref
  "Normalize ref to canonical map form.
   
   String refs become {:target id :kind :link}
   Map refs are validated and returned as-is.
   Invalid refs return nil."
  [r]
  (cond
    (string? r)
    {:target r :kind :link}

    (and (map? r) (:target r) (keyword? (:kind r)))
    r

    :else
    nil))

;; =============================================================================
;; Doc Scope
;; =============================================================================

(defn doc-node-ids
  "Return set of all node IDs that are descendants of :doc root."
  [db]
  (set (tree/descendants-of db :doc)))

;; =============================================================================
;; Derived Indexes - Helper Functions
;; =============================================================================

(defn- valid-refs-for-node
  "Extract valid, normalized refs from a node that target doc nodes.
   Returns set of ref maps with :target and :kind."
  [node doc-ids]
  (->> (:refs (:props node))
       (keep normalize-ref)
       (filter #(doc-ids (:target %)))
       set))

(defn- build-outgoing-refs
  "Build map of source-id to set of typed refs: {source-id #{ref-map}}
   Only includes refs whose targets exist in doc tree."
  [nodes doc-ids]
  (into {}
        (keep (fn [[source-id node]]
                (let [refs (valid-refs-for-node node doc-ids)]
                  (when (seq refs)
                    [source-id refs]))))
        nodes))

(defn- refs-to-backlinks-entries
  "Convert source-id and its refs to backlink entries.
   Returns seq of [kind target source-id] tuples for grouping."
  [source-id refs]
  (for [{:keys [kind target]} refs]
    [kind target source-id]))

(defn- group-backlinks-by-kind
  "Transform outgoing refs into backlinks grouped by kind.
   Returns: {kind {target-id #{source-id}}}"
  [outgoing-typed]
  (->> outgoing-typed
       (mapcat (fn [[source-id refs]]
                 (refs-to-backlinks-entries source-id refs)))
       (group-by first) ;; Group by kind
       (into {}
             (map (fn [[kind entries]]
                    [kind (reduce (fn [acc [_ target source]]
                                    (update acc target (fnil conj #{}) source))
                                  {}
                                  entries)])))))

(defn- calculate-citation-counts
  "Count total citations per target across all ref kinds.
   Returns: {target-id total-citation-count}"
  [backlinks-by-kind]
  (reduce (fn [citations [_kind target-to-sources]]
            (reduce (fn [acc [target sources]]
                      (update acc target (fnil + 0) (count sources)))
                    citations
                    target-to-sources))
          {}
          backlinks-by-kind))

(defn- simplify-outgoing-to-targets
  "Convert typed outgoing refs to simple target ID sets.
   Returns: {source-id #{target-id}}"
  [outgoing-typed]
  (into {}
        (map (fn [[source-id refs]]
               [source-id (set (map :target refs))]))
        outgoing-typed))

;; =============================================================================
;; Derived Indexes
;; =============================================================================

(defn derive-indexes
  "Build refs-derived maps. Only count refs whose :target is in :doc.
   
   Returns map with keys:
   - :ref/outgoing          {source-id #{target-id}}
   - :ref/backlinks-by-kind {kind {target-id #{source-id}}}
   - :ref/citations         {target-id count}
   - :link/backlinks        {target-id #{source-id}}
   - :highlight/backlinks   {target-id #{source-id}}"
  [db]
  (let [doc-ids (doc-node-ids db)
        nodes (:nodes db)

        ;; Build outgoing refs per source (typed, with full ref data)
        outgoing-typed (build-outgoing-refs nodes doc-ids)

        ;; Transform into backlinks grouped by kind
        backlinks-by-kind (group-backlinks-by-kind outgoing-typed)

        ;; Calculate citation counts across all kinds
        citations (calculate-citation-counts backlinks-by-kind)

        ;; Simplify outgoing to just target IDs
        outgoing-ids (simplify-outgoing-to-targets outgoing-typed)]

    {:ref/outgoing outgoing-ids
     :ref/backlinks-by-kind backlinks-by-kind
     :ref/citations citations
     :link/backlinks (get backlinks-by-kind :link {})
     :highlight/backlinks (get backlinks-by-kind :highlight {})}))

;; =============================================================================
;; Hygiene: Lint & Scrub
;; =============================================================================

(defn- extract-raw-target-ids
  "Extract all target IDs from a node's refs, regardless of validity.
   Used for lint checks to find all references."
  [node]
  (->> (:refs (:props node))
       (keep normalize-ref)
       (map :target)
       set))

(defn- find-dangling-refs
  "Find refs that point to non-existent or out-of-scope targets.
   Returns seq of issue maps."
  [nodes doc-ids all-node-ids]
  (for [[source-id node] nodes
        :let [targets (extract-raw-target-ids node)
              missing-from-doc (seq (remove doc-ids targets))
              missing-completely (seq (remove all-node-ids targets))
              missing (or missing-completely missing-from-doc)]
        :when missing]
    {:reason :dangling-ref
     :source source-id
     :missing (set missing)}))

(defn- find-circular-refs
  "Find refs where source references itself.
   Returns seq of issue maps."
  [nodes]
  (for [[source-id node] nodes
        :let [targets (extract-raw-target-ids node)]
        :when (contains? targets source-id)]
    {:reason :circular-ref
     :node source-id}))

(defn lint
  "Return warnings about ref integrity (no mutation).
   
   Checks for:
   - Dangling refs (target not in :doc tree or doesn't exist)
   - Circular refs (source refs itself)
   
   Returns vector of issue maps:
   [{:reason :dangling-ref :source id :missing #{id}}
    {:reason :circular-ref :node id}]"
  [db]
  (let [nodes (:nodes db)
        doc-ids (doc-node-ids db)
        all-node-ids (set (keys nodes))

        dangling (find-dangling-refs nodes doc-ids all-node-ids)
        circular (find-circular-refs nodes)]

    (vec (concat dangling circular))))

(defn scrub-dangling-ops
  "Return :update-node ops to remove refs whose :target no longer exists in :doc tree.
   
   Filters :refs prop to only include refs with valid targets in :doc.
   Returns empty vector if no scrubbing needed."
  [db]
  (let [doc-ids (doc-node-ids db)]
    (vec
     (keep
      (fn [[source-id node]]
        (let [refs (:refs (:props node))
              valid-refs (->> refs
                              (keep normalize-ref)
                              (filter #(contains? doc-ids (:target %)))
                              vec)]
          (when (not= (count (keep normalize-ref refs)) (count valid-refs))
            {:op :update-node
             :id source-id
             :props {:refs valid-refs}})))
      (:nodes db)))))

;; =============================================================================
;; Intent Compilers (High-level → Ops)
;; =============================================================================

(defn add-link-op
  "Return :update-node op to add link from source to target.
   
   Returns nil if link already exists."
  [db source-id target-id]
  (let [current-refs (vec (get-in db [:nodes source-id :props :refs] []))
        link-ref {:target target-id :kind :link}
        link-exists? (some #(= link-ref (normalize-ref %)) current-refs)]
    (when-not link-exists?
      {:op :update-node
       :id source-id
       :props {:refs (conj current-refs link-ref)}})))

(defn add-highlight-op
  "Return :update-node op to add highlight from source to target with anchor.
   
   Anchor is optional map with implementation-defined keys (e.g., :path, :range, :coords)."
  [db source-id target-id anchor]
  (let [current-refs (vec (get-in db [:nodes source-id :props :refs] []))
        highlight-ref (cond-> {:target target-id :kind :highlight}
                        anchor (assoc :anchor anchor))]
    {:op :update-node
     :id source-id
     :props {:refs (conj current-refs highlight-ref)}}))

;; =============================================================================
;; Plugin Registration
;; =============================================================================

;; Auto-register on namespace load
(registry/register! ::refs derive-indexes)
