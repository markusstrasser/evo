(ns kernel.query
  "Read-only query layer for database access.

   Single source of truth for all DB reads. Use these instead of direct
   get-in calls to ensure consistent data access patterns.

   Split by domain:
   - Selection: queries on session/selection node (undoable)
   - Edit/Cursor: queries on :ui map (ephemeral, not in history)
   - Tree: queries on :derived indexes and :children-by-parent"
  (:require [kernel.constants :as const]))

;; ── Selection Queries (Undoable) ──────────────────────────────────────────────

(defn selection-state
  "Returns the selection state map from session/selection node."
  [db]
  (get-in db [:nodes const/session-selection-id :props]
          {:nodes #{} :focus nil :anchor nil}))

(defn selection
  "Returns the set of selected node IDs (possibly empty)."
  [db]
  (:nodes (selection-state db) #{}))

(defn focus
  "Returns the focused node ID (the 'current' node for navigation), or nil."
  [db]
  (:focus (selection-state db)))

(defn anchor
  "Returns the anchor node ID (starting point for range selection), or nil."
  [db]
  (:anchor (selection-state db)))

(defn selected?
  "Returns true if the given node ID is in the selection."
  [db id]
  (contains? (selection db) id))

(defn selection-count
  "Returns the number of selected nodes."
  [db]
  (count (selection db)))

(defn has-selection?
  "Returns true if any nodes are selected."
  [db]
  (pos? (selection-count db)))

;; ── Edit/Cursor Queries (Ephemeral) ───────────────────────────────────────────

(defn editing-block-id
  "Get currently editing block ID (nil if not editing).
   Ephemeral state - not recorded in history."
  [db]
  (get-in db [:ui :editing-block-id]))

(defn editing?
  "Returns true if currently in edit mode."
  [db]
  (some? (editing-block-id db)))

(defn cursor-state
  "Get cursor state for a block (first-row?, last-row?).
   Ephemeral state - not recorded in history."
  [db block-id]
  (get-in db [:ui :cursor block-id]))

(defn cursor-first-row?
  "Check if cursor is on first row of the given block.
   Ephemeral state - not recorded in history."
  [db block-id]
  (get-in db [:ui :cursor block-id :first-row?] false))

(defn cursor-last-row?
  "Check if cursor is on last row of the given block.
   Ephemeral state - not recorded in history."
  [db block-id]
  (get-in db [:ui :cursor block-id :last-row?] false))

;; ── Tree Queries (Derived Indexes) ────────────────────────────────────────────

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

(defn children
  "Get ordered vector of child IDs for a parent, or empty vector if none."
  [db parent]
  (get-in db [:children-by-parent parent] []))

(defn descendants-of
  "Return all descendant node IDs of the given parent (recursive).

   Does not include the parent itself, only its descendants.
   Returns empty vector if parent has no children."
  [db parent]
  (let [children-by-parent (:children-by-parent db)]
    (letfn [(collect [node-id]
              (let [children (get children-by-parent node-id [])]
                (if (seq children)
                  (concat children (mapcat collect children))
                  [])))]
      (vec (collect parent)))))

(defn doc-range
  "Return set of node IDs between a and b (inclusive) in document order.

   Uses doc-only pre-order traversal indexes (excludes session/trash nodes).
   Returns nil if either node lacks traversal metadata (e.g., not in tree)."
  [db a b]
  (let [pre (get-in db [:derived :doc/pre])
        id-by-pre (get-in db [:derived :doc/id-by-pre])]
    (when (and (contains? pre a) (contains? pre b))
      (let [a-idx (get pre a)
            b-idx (get pre b)
            [start end] (if (<= a-idx b-idx) [a-idx b-idx] [b-idx a-idx])]
        (->> (range start (inc end))
             (map id-by-pre)
             (remove nil?)
             set)))))
