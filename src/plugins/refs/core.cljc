(ns plugins.refs.core
  "Typed refs plugin for links, selections, and highlights.
   
   Computes derived views over refs whose :target is in :doc tree.
   Provides lint (warnings) and scrub (ops to fix dangling refs)."
  (:require [clojure.set :as set]
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
;; Derived Indexes
;; =============================================================================

(defn derive-indexes
  "Build refs-derived maps. Only count refs whose :target is in :doc.
   
   Returns map with keys:
   - :ref/outgoing          {source-id #{target-id}}
   - :ref/backlinks-by-kind {kind {target-id #{source-id}}}
   - :ref/citations         {target-id count}
   - :selection/outgoing    {source-id #{target-id}}
   - :selection/backlinks   {target-id #{source-id}}
   - :highlight/backlinks   {target-id #{source-id}}"
  [db]
  (let [doc-ids (doc-node-ids db)
        nodes (:nodes db)

        ;; Build outgoing refs per source (typed, with full ref data)
        outgoing-typed
        (reduce-kv
         (fn [acc source-id {:keys [props]}]
           (let [refs (->> (:refs props)
                           (keep normalize-ref)
                           (filter #(doc-ids (:target %))))]
             (if (seq refs)
               (assoc acc source-id (set refs))
               acc)))
         {}
         nodes)

        ;; Build backlinks by kind: {kind {target #{source}}}
        backlinks-by-kind
        (reduce-kv
         (fn [acc source-id refs]
           (reduce
            (fn [acc' ref]
              (let [{:keys [target kind]} ref]
                (update-in acc' [kind target] (fnil conj #{}) source-id)))
            acc
            refs))
         {}
         outgoing-typed)

        ;; Citation counts (count refs across all kinds, from all sources)
        citations
        (reduce-kv
         (fn [acc _kind target-map]
           (reduce-kv
            (fn [acc' target sources]
              (update acc' target (fnil + 0) (count sources)))
            acc
            target-map))
         {}
         backlinks-by-kind)

        ;; Simplified outgoing (just target IDs, no kind)
        outgoing-ids
        (into {}
              (for [[source-id refs] outgoing-typed]
                [source-id (set (map :target refs))]))]

    {:ref/outgoing outgoing-ids
     :ref/backlinks-by-kind backlinks-by-kind
     :ref/citations citations
     :selection/outgoing (into {}
                               (for [[source-id refs] outgoing-typed]
                                 [source-id (->> refs
                                                 (filter #(= :selection (:kind %)))
                                                 (map :target)
                                                 set)]))
     :selection/backlinks (get backlinks-by-kind :selection {})
     :highlight/backlinks (get backlinks-by-kind :highlight {})}))

;; =============================================================================
;; Hygiene: Lint & Scrub
;; =============================================================================

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

        ;; Build outgoing from raw props (not derived, to catch all refs)
        outgoing-raw
        (reduce-kv
         (fn [acc source-id {:keys [props]}]
           (let [refs (->> (:refs props)
                           (keep normalize-ref)
                           (map :target)
                           set)]
             (if (seq refs)
               (assoc acc source-id refs)
               acc)))
         {}
         nodes)

        dangling
        (for [[source-id targets] outgoing-raw
              :let [missing-from-doc (seq (remove doc-ids targets))
                    missing-completely (seq (remove all-node-ids targets))
                    missing (or missing-completely missing-from-doc)]
              :when missing]
          {:reason :dangling-ref
           :source source-id
           :missing (set missing)})

        circular
        (for [[source-id targets] outgoing-raw
              :when (contains? targets source-id)]
          {:reason :circular-ref
           :node source-id})]

    (vec (concat dangling circular))))

(defn scrub-dangling-ops
  "Return :update-node ops to remove refs whose :target no longer exists in :doc tree.
   
   Filters :refs prop to only include refs with valid targets in :doc.
   Returns empty vector if no scrubbing needed."
  [db]
  (let [doc-ids (doc-node-ids db)]
    (vec
     (keep
      (fn [[source-id {:keys [props]}]]
        (let [refs (:refs props)
              refs-normalized (keep normalize-ref refs)
              refs-valid (filter #(contains? doc-ids (:target %)) refs-normalized)
              refs-valid-vec (vec refs-valid)]
          (when (not= (count refs-normalized) (count refs-valid))
            {:op :update-node
             :id source-id
             :props {:refs refs-valid-vec}})))
      (:nodes db)))))

;; =============================================================================
;; Intent Compilers (High-level → Ops)
;; =============================================================================

(defn add-selection-op
  "Return :update-node op to add selection from source to target.
   
   Returns nil if selection already exists."
  [db source-id target-id]
  (let [current-refs (vec (get-in db [:nodes source-id :props :refs] []))
        selection-ref {:target target-id :kind :selection}
        exists? (some #(= selection-ref (normalize-ref %)) current-refs)]
    (when-not exists?
      {:op :update-node
       :id source-id
       :props {:refs (conj current-refs selection-ref)}})))

(defn remove-selection-op
  "Return :update-node op to remove selection from source to target.
   
   Returns nil if selection doesn't exist."
  [db source-id target-id]
  (let [current-refs (vec (get-in db [:nodes source-id :props :refs] []))
        selection-ref {:target target-id :kind :selection}
        filtered-refs (vec (remove #(= selection-ref (normalize-ref %)) current-refs))]
    (when (not= (count current-refs) (count filtered-refs))
      {:op :update-node
       :id source-id
       :props {:refs filtered-refs}})))

(defn toggle-selection-op
  "Return :update-node op to toggle selection from source to target.
   
   If selection exists, removes it. Otherwise adds it."
  [db source-id target-id]
  (or (remove-selection-op db source-id target-id)
      (add-selection-op db source-id target-id)))

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
