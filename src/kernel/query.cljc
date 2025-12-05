(ns kernel.query
  "Read-only query layer for database access.

   Single source of truth for all DB reads. Use these instead of direct
   get-in calls to ensure consistent data access patterns.

   Split by domain:
   - Selection: queries on session/selection node (undoable)
   - Edit/Cursor: queries on :ui map (ephemeral, not in history)
   - Tree: queries on :derived indexes and :children-by-parent"
  (:require [kernel.navigation :as nav]))

;; ── Selection Queries (Session-based after Phases 4-5) ────────────────────────
;; These functions now query session state instead of DB.
;; Tests must pass session explicitly.

(defn selection-state
  "Returns the selection state map from session.

   Args:
   - session: Session state map (required for tests)

   Example:
     (selection-state {:selection {:nodes #{\"a\"} :focus \"a\" :anchor nil}})"
  [session]
  (get session :selection {:nodes #{} :focus nil :anchor nil}))

(defn selection
  "Returns the set of selected node IDs (possibly empty).

   Args:
   - session: Session state map (required for tests)"
  [session]
  (:nodes (selection-state session) #{}))

(defn focus
  "Returns the focused node ID (the 'current' node for navigation), or nil.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (:focus (selection-state session)))

(defn anchor
  "Returns the anchor node ID (starting point for range selection), or nil.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (:anchor (selection-state session)))

(defn selected?
  "Returns true if the given node ID is in the selection.

   Args:
   - session: Session state map (required for tests)
   - id: Block ID to check"
  [session id]
  (contains? (selection session) id))

(defn selection-count
  "Returns the number of selected nodes.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (count (selection session)))

(defn has-selection?
  "Returns true if any nodes are selected.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (pos? (selection-count session)))

;; ── Edit/Cursor Queries (Session-based after Phases 4-5) ──────────────────────

(defn editing-block-id
  "Get currently editing block ID (nil if not editing).

   Args:
   - session: Session state map (required for tests)"
  [session]
  (get-in session [:ui :editing-block-id]))

(defn editing?
  "Returns true if currently in edit mode.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (some? (editing-block-id session)))

(defn cursor-state
  "Get cursor state for a block (first-row?, last-row?).

   Args:
   - session: Session state map (required for tests)
   - block-id: Block ID to check"
  [session block-id]
  (get-in session [:ui :cursor block-id]))

(defn cursor-position
  "Get cursor position hint (:start or :end) for entering edit mode.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (get-in session [:ui :cursor-position]))

(defn cursor-first-row?
  "Check if cursor is on first row of the given block.

   Args:
   - session: Session state map (required for tests)
   - block-id: Block ID to check"
  [session block-id]
  (get-in session [:ui :cursor block-id :first-row?] false))

(defn cursor-last-row?
  "Check if cursor is on last row of the given block.

   Args:
   - session: Session state map (required for tests)
   - block-id: Block ID to check"
  [session block-id]
  (get-in session [:ui :cursor block-id :last-row?] false))

;; ── Fold/Zoom Queries (Session-based after Phases 4-5) ────────────────────────

(defn folded-set
  "Get the set of folded block IDs.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (get-in session [:ui :folded] #{}))

(defn folded?
  "Check if a block is currently folded (children hidden).

   Args:
   - session: Session state map (required for tests)
   - block-id: Block ID to check"
  [session block-id]
  (contains? (folded-set session) block-id))

(defn zoom-stack
  "Get the zoom navigation stack.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (get-in session [:ui :zoom-stack] []))

(defn zoom-root
  "Get the current zoom root (rendering root block ID).
   Returns nil if at document root.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (get-in session [:ui :zoom-root]))

(defn current-page
  "Get ID of the currently active page from session/ui state.
   Returns nil if no page is selected.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (get-in session [:ui :current-page]))

(defn active-outline-root
  "Get the active outline root for navigation and rendering.

   LOGSEQ PARITY: Determines which subtree is 'visible' for navigation.
   Priority order:
   1. Zoom root (when zoomed into a block)
   2. Current page (when a page is selected)
   3. Document root (fallback)

   This ensures navigation stays within the rendered outline, preventing
   arrow keys from jumping across pages or into hidden subtrees.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (or (zoom-root session)
      (current-page session)
      :doc))

(defn zoom-level
  "Get current zoom level (0 = root, 1+ = zoomed in).

   Args:
   - session: Session state map (required for tests)"
  [session]
  (count (zoom-stack session)))

(defn in-zoom?
  "Check if currently zoomed into a block.

   Args:
   - session: Session state map (required for tests)"
  [session]
  (pos? (zoom-level session)))

;; ── Tree Queries (Derived Indexes) ────────────────────────────────────────────

(defn parent-of
  "Get parent ID of a node (returns keyword root or string node ID)."
  [db id]
  (get-in db [:derived :parent-of id]))

(defn page-of
  "Find the page ancestor of a block (the page it belongs to).
   Walks up the parent chain until finding a node with :type :page.
   Returns nil if block is at doc root level (no page ancestor)."
  [db block-id]
  (loop [current block-id]
    (let [parent (parent-of db current)]
      (cond
        ;; Reached root or no parent - no page ancestor
        (or (nil? parent)
            (keyword? parent)) nil
        ;; Parent is a page - found it
        (= :page (get-in db [:nodes parent :type])) parent
        ;; Keep walking up
        :else (recur parent)))))

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
   (NOT sibling order: [A, B] or depth-first: [A1, A2, A, B1, B])

   Args:
   - db: Database
   - session: Session state map (required for tests)"
  [db session]
  (let [root (active-outline-root session)
        folded-blocks (folded-set session)

        ;; Get children from canonical children-by-parent
        ;; (not visible-order, which doesn't have session access)
        get-children (fn [parent-id]
                       (get-in db [:children-by-parent parent-id] []))

        ;; Pre-order traversal: parent, then children (if not folded)
        traverse (fn traverse [node-id]
                   (when node-id
                     (let [children (if (contains? folded-blocks node-id)
                                      [] ;; Folded - skip children
                                      (get-children node-id))]
                       (cons node-id
                             (when (seq children)
                               (mapcat traverse children))))))]

    ;; Start from active outline root's children
    ;; (zoom root, current page, or doc root)
    (vec (mapcat traverse (get-children root)))))

(defn next-block-dom-order
  "Get the next block in DOM/visual order (pre-order traversal).
   
   LOGSEQ PARITY: This matches Logseq's navigation which uses
   get-next-block-non-collapsed to traverse in DOM order, respecting
   fold state, zoom, and current page boundaries.
   
   Returns nil if at last visible block."
  [db current-id]
  (nav/next-visible-block db current-id))

(defn prev-block-dom-order
  "Get the previous block in DOM/visual order (pre-order traversal).
   
   LOGSEQ PARITY: This matches Logseq's navigation which uses
   get-prev-block-non-collapsed to traverse in DOM order, respecting
   fold state, zoom, and current page boundaries.
   
   Returns nil if at first visible block."
  [db current-id]
  (nav/prev-visible-block db current-id))

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
   Returns empty set if either node is not visible in current context.

   Args:
   - db: Database
   - session: Session state map (required for tests)
   - a: First block ID
   - b: Second block ID"
  [db session a b]
  (let [visible (visible-blocks-in-dom-order db session)
        a-idx (.indexOf visible a)
        b-idx (.indexOf visible b)]
    (if (and (>= a-idx 0) (>= b-idx 0))
      (let [[start end] (if (<= a-idx b-idx) [a-idx b-idx] [b-idx a-idx])]
        (set (subvec visible start (inc end))))
      #{})))
