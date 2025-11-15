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
  (get-in db [:nodes const/session-ui-id :props :editing-block-id]))

(defn editing?
  "Returns true if currently in edit mode."
  [db]
  (some? (editing-block-id db)))

(defn cursor-state
  "Get cursor state for a block (first-row?, last-row?).
   Ephemeral state - not recorded in history."
  [db block-id]
  (get-in db [:nodes const/session-ui-id :props :cursor block-id]))

(defn cursor-position
  "Get cursor position hint (:start or :end) for entering edit mode.
   Ephemeral state - not recorded in history."
  [db]
  (get-in db [:nodes const/session-ui-id :props :cursor-position]))

(defn cursor-first-row?
  "Check if cursor is on first row of the given block.
   Ephemeral state - not recorded in history."
  [db block-id]
  (get-in db [:nodes const/session-ui-id :props :cursor block-id :first-row?] false))

(defn cursor-last-row?
  "Check if cursor is on last row of the given block.
   Ephemeral state - not recorded in history."
  [db block-id]
  (get-in db [:nodes const/session-ui-id :props :cursor block-id :last-row?] false))

;; ── Fold/Zoom Queries (Ephemeral) ─────────────────────────────────────────────

(defn folded-set
  "Get the set of folded block IDs.
   Ephemeral state - not recorded in history."
  [db]
  (get-in db [:nodes const/session-ui-id :props :folded] #{}))

(defn folded?
  "Check if a block is currently folded (children hidden).
   Ephemeral state - not recorded in history."
  [db block-id]
  (contains? (folded-set db) block-id))

(defn zoom-stack
  "Get the zoom navigation stack.
   Ephemeral state - not recorded in history."
  [db]
  (get-in db [:nodes const/session-ui-id :props :zoom-stack] []))

(defn zoom-root
  "Get the current zoom root (rendering root block ID).
   Returns nil if at document root.
   Ephemeral state - not recorded in history."
  [db]
  (get-in db [:nodes const/session-ui-id :props :zoom-root]))

(defn current-page
  "Get ID of the currently active page from session/ui state.
   Returns nil if no page is selected.
   Ephemeral state - not recorded in history."
  [db]
  (get-in db [:nodes const/session-ui-id :props :current-page]))

(defn active-outline-root
  "Get the active outline root for navigation and rendering.
   
   LOGSEQ PARITY: Determines which subtree is 'visible' for navigation.
   Priority order:
   1. Zoom root (when zoomed into a block)
   2. Current page (when a page is selected)
   3. Document root (fallback)
   
   This ensures navigation stays within the rendered outline, preventing
   arrow keys from jumping across pages or into hidden subtrees."
  [db]
  (or (zoom-root db)
      (current-page db)
      :doc))

(defn zoom-level
  "Get current zoom level (0 = root, 1+ = zoomed in).
   Ephemeral state - not recorded in history."
  [db]
  (count (zoom-stack db)))

(defn in-zoom?
  "Check if currently zoomed into a block.
   Ephemeral state - not recorded in history."
  [db]
  (pos? (zoom-level db)))

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

(defn- get-child-ids
  "Get child IDs for a node from children-by-parent map."
  [children-by-parent node-id]
  (get children-by-parent node-id []))

(defn descendants-of
  "Return all descendant node IDs of the given parent (recursive).

   Does not include the parent itself, only its descendants.
   Returns empty vector if parent has no children."
  [db parent]
  (let [children-by-parent (:children-by-parent db)
        get-children (partial get-child-ids children-by-parent)]
    (->> (tree-seq (comp seq get-children) get-children parent)
         (rest) ;; Remove the parent itself
         vec)))

(defn visible-blocks-in-dom-order
  "Get all visible blocks in DOM/visual order (pre-order traversal).
   
   LOGSEQ PARITY: This matches Logseq's `get-blocks-noncollapse` which returns
   all visible .ls-block elements in the order they appear in the DOM.
   
   DOM order = pre-order traversal order:
   - Parent comes before its children
   - Siblings in order
   - Respects folding (collapsed children excluded)
   - Respects zoom (only blocks under zoom root)
   - Respects current page (only blocks on active page when not zoomed)
   
   Example tree:
     A
       A1
       A2
     B
       B1
   
   DOM order: [A, A1, A2, B, B1]
   (NOT sibling order: [A, B] or depth-first: [A1, A2, A, B1, B])"
  [db]
  (let [root (active-outline-root db)
        folded (folded-set db)

        ;; Pre-order traversal: parent, then children (if not folded)
        traverse (fn traverse [node-id]
                   (when node-id
                     (let [is-folded (contains? folded node-id)
                           child-ids (when-not is-folded (children db node-id))]
                       (cons node-id
                             (when (seq child-ids)
                               (mapcat traverse child-ids))))))]

    ;; Start from active outline root's children
    ;; (zoom root, current page, or doc root)
    (vec (mapcat traverse (children db root)))))

(defn next-block-dom-order
  "Get the next block in DOM/visual order (pre-order traversal).
   
   LOGSEQ PARITY: This matches Logseq's navigation which uses
   get-next-block-non-collapsed to traverse in DOM order, respecting
   fold state, zoom, and current page boundaries.
   
   Returns nil if at last visible block."
  [db current-id]
  (let [all-blocks (visible-blocks-in-dom-order db)
        idx (.indexOf all-blocks current-id)]
    (when (>= idx 0)
      (get all-blocks (inc idx)))))

(defn prev-block-dom-order
  "Get the previous block in DOM/visual order (pre-order traversal).
   
   LOGSEQ PARITY: This matches Logseq's navigation which uses
   get-prev-block-non-collapsed to traverse in DOM order, respecting
   fold state, zoom, and current page boundaries.
   
   Returns nil if at first visible block."
  [db current-id]
  (let [all-blocks (visible-blocks-in-dom-order db)
        idx (.indexOf all-blocks current-id)]
    (when (> idx 0)
      (get all-blocks (dec idx)))))

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

(defn visible-range
  "Return set of visible block IDs between a and b (inclusive) in DOM order.
   
   LOGSEQ PARITY: Unlike doc-range, this respects:
   - Fold state (excludes folded descendants)
   - Zoom/page boundaries (only blocks in active outline)
   
   Used for Shift+Click range selection to prevent selecting hidden blocks.
   Returns empty set if either node is not visible in current context."
  [db a b]
  (let [visible (visible-blocks-in-dom-order db)
        a-idx (.indexOf visible a)
        b-idx (.indexOf visible b)]
    (if (and (>= a-idx 0) (>= b-idx 0))
      (let [[start end] (if (<= a-idx b-idx) [a-idx b-idx] [b-idx a-idx])]
        (set (subvec visible start (inc end))))
      #{})))
