(ns core.tree
  "Shared tree traversal and navigation utilities.

   Provides consistent accessors for derived indexes and tree operations.
   Use these instead of direct :derived access to centralize tree logic.")

;; ── Derived Index Accessors ──────────────────────────────────────────────────

(defn parent-of
  "Get parent ID of a node (returns keyword root or string node ID)."
  [db id]
  (get-in db [:derived :parent-of id]))

(defn prev-sibling
  "Get previous sibling ID of a node, or nil if first/none."
  [db id]
  (get-in db [:derived :prev-id-of id]))

(defn next-sibling
  "Get next sibling ID of a node, or nil if last/none."
  [db id]
  (get-in db [:derived :next-id-of id]))

(defn index-of
  "Get 0-based index of node within its parent's children, or nil."
  [db id]
  (get-in db [:derived :index-of id]))

;; ── Tree Traversal ────────────────────────────────────────────────────────────

(defn doc-range
  "Return set of node IDs between a and b (inclusive) in document order.

   Uses pre-order traversal indexes. Returns nil if either node lacks
   traversal metadata (e.g., not in tree)."
  [db a b]
  (let [pre (get-in db [:derived :pre])
        id-by-pre (get-in db [:derived :id-by-pre])]
    (when (and (contains? pre a) (contains? pre b))
      (let [a-idx (get pre a)
            b-idx (get pre b)
            [start end] (if (<= a-idx b-idx) [a-idx b-idx] [b-idx a-idx])]
        (->> (range start (inc end))
             (map id-by-pre)
             (remove nil?)
             set)))))

;; ── Children Access ───────────────────────────────────────────────────────────

(defn children
  "Get ordered vector of child IDs for a parent, or empty vector if none."
  [db parent]
  (get-in db [:children-by-parent parent] []))
