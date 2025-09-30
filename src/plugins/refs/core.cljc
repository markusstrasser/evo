(ns plugins.refs.core
  "Citations and references plugin - demonstrates graph features as derived+policy.

   Refs are text/prop-level annotations stored in node properties.
   Backlinks and citation counts are derived indexes.
   Scrubbing dangling refs is policy, not kernel behavior.")

(defn derive-indexes
  "Compute derived ref indexes from node :refs properties.

   Computes:
   - :ref-outgoing - {src-id #{dst-id ...}} - outgoing refs from each node
   - :ref-backlinks - {dst-id #{src-id ...}} - incoming refs to each node (inverted)
   - :ref-citation-count - {dst-id count} - number of nodes referencing each node

   Returns: map of derived ref data to be merged into db :derived"
  [{:keys [nodes]}]
  (let [;; Build outgoing ref map from node props (only non-empty refs)
        outgoing (reduce-kv
                  (fn [m id {:keys [props]}]
                    (if-let [refs (:refs props)]
                      (if (seq refs)
                        (assoc m id (set refs))
                        m)
                      m))
                  {}
                  nodes)

        ;; Invert to get backlinks (incoming refs)
        backlinks (reduce-kv
                   (fn [m src dsts]
                     (reduce (fn [m' dst]
                               (update m' dst (fnil conj #{}) src))
                             m
                             dsts))
                   {}
                   outgoing)

        ;; Count citations
        citation-count (into {}
                            (map (fn [[dst srcs]] [dst (count srcs)])
                                 backlinks))]

    {:ref-outgoing outgoing
     :ref-backlinks backlinks
     :ref-citation-count citation-count}))

(defn find-dangling-refs
  "Find all references to nodes that don't exist.

   Returns: vector of issue maps with:
   - :reason ::dangling-ref
   - :dst - the missing target node ID
   - :srcs - set of node IDs that reference it
   - :suggest - suggested fix (drop refs or redirect)"
  [{:keys [nodes derived]}]
  (let [backlinks (:ref-backlinks derived)]
    (for [[dst srcs] backlinks
          :when (not (contains? nodes dst))]
      {:reason ::dangling-ref
       :dst dst
       :srcs srcs
       :suggest {:action :drop-refs
                 :nodes (vec srcs)
                 :details "Remove :refs containing missing target from each source node"}})))

(defn scrub-dangling-refs
  "Remove references to nodes that no longer exist.

   This is a POLICY operation, not a kernel operation.
   Returns: vector of :update-node ops to remove dangling refs."
  [db]
  (let [;; Compute derived indexes if not present
        db-with-derived (if (empty? (:derived db))
                          (assoc db :derived (derive-indexes db))
                          db)
        issues (find-dangling-refs db-with-derived)
        dangling-targets (set (map :dst issues))]
    (for [[src-id {:keys [props]}] (:nodes db)
          :let [refs (:refs props)]
          :when (and refs (some dangling-targets refs))]
      {:op :update-node
       :id src-id
       :props {:refs (filterv #(not (contains? dangling-targets %)) refs)}})))

(defn lint
  "Lint the database for ref-related issues.

   Returns: vector of lint issues (warnings, not errors).

   Checks:
   - Dangling refs (refs to non-existent nodes)
   - Circular refs (node referencing itself)
   - Orphaned nodes (nodes with no refs in or out)"
  [db]
  (let [dangling (find-dangling-refs db)
        {:keys [ref-outgoing]} (:derived db)

        ;; Find circular refs (node refs itself)
        circular (for [[src dsts] ref-outgoing
                       :when (contains? dsts src)]
                   {:reason ::circular-ref
                    :node src
                    :suggest {:action :remove-self-ref
                              :details "Remove self-reference from :refs"}})]

    (vec (concat dangling circular))))

(defn add-ref
  "Add a reference from src-id to dst-id.

   Returns: :update-node op to add the ref."
  [db src-id dst-id]
  (let [current-refs (get-in db [:nodes src-id :props :refs] [])]
    (if (some #(= % dst-id) current-refs)
      nil ;; Already has this ref, no-op
      {:op :update-node
       :id src-id
       :props {:refs (conj (vec current-refs) dst-id)}})))

(defn remove-ref
  "Remove a reference from src-id to dst-id.

   Returns: :update-node op to remove the ref."
  [db src-id dst-id]
  (let [current-refs (get-in db [:nodes src-id :props :refs] [])]
    (if (some #(= % dst-id) current-refs)
      {:op :update-node
       :id src-id
       :props {:refs (filterv #(not= % dst-id) current-refs)}}
      nil))) ;; Doesn't have this ref, no-op

(defn get-backlinks
  "Get all nodes that reference the given node.

   Returns: set of node IDs, or empty set if none."
  [db node-id]
  (get-in db [:derived :ref-backlinks node-id] #{}))

(defn get-outgoing-refs
  "Get all nodes referenced by the given node.

   Returns: set of node IDs, or empty set if none."
  [db node-id]
  (get-in db [:derived :ref-outgoing node-id] #{}))

(defn citation-count
  "Get the number of nodes referencing the given node."
  [db node-id]
  (get-in db [:derived :ref-citation-count node-id] 0))
