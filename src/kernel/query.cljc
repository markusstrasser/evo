(ns kernel.query
  "Read-only query layer for database access.

   Single source of truth for all DB reads. Use these instead of direct
   get-in calls to ensure consistent data access patterns.

   ═══════════════════════════════════════════════════════════════════════════════
   FUNCTION SIGNATURES - CRITICAL
   ═══════════════════════════════════════════════════════════════════════════════

   ClojureScript doesn't check arity at compile time - passing wrong args
   silently uses the wrong value as a parameter, causing mysterious nil returns.

   SIGNATURE CATEGORIES:

   SESSION-ONLY [session] - Ephemeral UI state queries:
     selection-state, selection, focus, anchor, selected?, selection-count,
     has-selection?, editing-block-id, editing?, cursor-state, cursor-position,
     cursor-first-row?, cursor-last-row?, folded-set, folded?, zoom-stack,
     zoom-root, current-page, active-outline-root, zoom-level, in-zoom?

   DB-ONLY [db ...] - Persistent document graph queries:
     parent-of, page-of, prev-sibling, next-sibling, index-of, children,
     descendants-of, doc-range, block-text, all-pages, page-title,
     find-page-by-name, page-empty?, tombstone?, trashed-pages, trashed-at,
     created-at, updated-at, page-block-count, page-word-count, page-metadata,
     next-block-dom-order, first-block-dom-order, last-block-dom-order

   BOTH [db session ...] - Combined queries (always db first!):
     visible-blocks, visible-next-block, visible-prev-block, visible-range,
     visible-children, same-page?

   NAMING CONVENTION:
   - visible-* prefix = requires session for fold/zoom/page visibility
   - No prefix = check signature category above

   ═══════════════════════════════════════════════════════════════════════════════"
  (:require [kernel.navigation :as nav]
            [clojure.string :as str]
            [medley.core :as m]))

;; ── Selection Queries ─────────────────────────────────────────────────────────
;; SIGNATURE: [session] or [session id] - Session-only, no db parameter
;; These functions query ephemeral selection state from session atom.

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

;; ── Edit/Cursor Queries ───────────────────────────────────────────────────────
;; SIGNATURE: [session] or [session block-id] - Session-only, no db parameter

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

;; ── Fold/Zoom Queries ─────────────────────────────────────────────────────────
;; SIGNATURE: [session] or [session block-id] - Session-only, no db parameter

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
;; SIGNATURE: [db id] or [db] - DB-only, no session parameter

(defn parent-of
  "Get parent ID of a node (returns keyword root or string node ID)."
  [db id]
  (get-in db [:derived :parent-of id]))

(defn page-of
  "Find the page ancestor of a block (the page it belongs to).
   Walks up the parent chain until finding a node with :type :page.
   Returns nil if block is at doc root level (no page ancestor)."
  [db block-id]
  (->> (iterate #(parent-of db %) block-id)
       (rest) ;; Skip the block itself
       (take-while #(and (some? %) (not (keyword? %))))
       (m/find-first #(= :page (get-in db [:nodes % :type])))))

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

;; ── Visibility Queries (Require Session) ─────────────────────────────────────
;; SIGNATURE: [db session ...] - Both parameters required, db first!
;; These functions combine tree structure (db) with visibility state (session).

(defn visible-blocks
  "Get all visible blocks in DOM/visual order (pre-order traversal).

   NAMING: Functions prefixed with `visible-` require session state
   for fold/zoom/page visibility. Pure tree queries (parent-of, children, etc.)
   only need db.

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
   - session: Session state map (required for fold/zoom/page visibility)"
  [db session]
  {:pre [(map? db) (map? session)
         (contains? db :nodes) (contains? db :children-by-parent)]}
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

(defn first-visible-block
  "Return the first visible node in the active outline."
  [db session]
  (first (visible-blocks db session)))

(defn last-visible-block
  "Return the last visible node in the active outline."
  [db session]
  (last (visible-blocks db session)))

(defn visible-block-count
  "Return the count of visible nodes in the active outline."
  [db session]
  (count (visible-blocks db session)))

(defn visible-children
  "Return visible children for PARENT-ID, respecting fold state."
  [db session parent-id]
  (if (folded? session parent-id)
    []
    (children db parent-id)))

(def ^:private non-selectable-types
  #{:doc :page})

(defn selectable-block?
  "True when NODE-ID is a selectable outline item.

   Pages and keyword roots are render/navigation containers, not block
   selections."
  [db node-id]
  (and (string? node-id)
       (not (contains? non-selectable-types
                       (get-in db [:nodes node-id :type])))))

(defn selectable-visible-blocks
  "Return visible nodes that can participate in block selection."
  [db session]
  (filterv #(selectable-block? db %) (visible-blocks db session)))

(defn first-selectable-visible-block
  [db session]
  (first (selectable-visible-blocks db session)))

(defn last-selectable-visible-block
  [db session]
  (last (selectable-visible-blocks db session)))

(defn next-selectable-visible-block
  "Return next selectable visible block on the same page as CURRENT-ID."
  [db session current-id]
  (let [visible (visible-blocks db session)
        current-page-id (current-page session)
        same-current-page? (fn [candidate-id]
                             (or (nil? current-page-id)
                                 (= (page-of db current-id)
                                    (page-of db candidate-id))))
        idx (.indexOf visible current-id)]
    (when (not= -1 idx)
      (->> (subvec visible (inc idx))
           (m/find-first #(and (selectable-block? db %)
                               (same-current-page? %)))))))

(defn prev-selectable-visible-block
  "Return previous selectable visible block on the same page as CURRENT-ID."
  [db session current-id]
  (let [visible (visible-blocks db session)
        current-page-id (current-page session)
        same-current-page? (fn [candidate-id]
                             (or (nil? current-page-id)
                                 (= (page-of db current-id)
                                    (page-of db candidate-id))))
        idx (.indexOf visible current-id)]
    (when (not= -1 idx)
      (->> (subvec visible 0 idx)
           rseq
           (m/find-first #(and (selectable-block? db %)
                               (same-current-page? %)))))))

(defn visible-next-block
  "Get the next visible block in DOM/visual order (pre-order traversal).

   NAMING: Functions prefixed with `visible-` require session state
   for fold/zoom/page visibility. Pure tree queries only need db.

   LOGSEQ PARITY: This matches Logseq's navigation which uses
   get-next-block-non-collapsed to traverse in DOM order, respecting
   fold state, zoom, and current page boundaries.

   Returns nil if at last visible block."
  [db session current-id]
  {:pre [(map? db) (map? session) (or (string? current-id) (keyword? current-id))]}
  (nav/next-visible-block db session current-id))

(defn visible-prev-block
  "Get the previous visible block in DOM/visual order (pre-order traversal).

   NAMING: Functions prefixed with `visible-` require session state
   for fold/zoom/page visibility. Pure tree queries only need db.

   LOGSEQ PARITY: This matches Logseq's navigation which uses
   get-prev-block-non-collapsed to traverse in DOM order, respecting
   fold state, zoom, and current page boundaries.

   Returns nil if at first visible block."
  [db session current-id]
  {:pre [(map? db) (map? session) (or (string? current-id) (keyword? current-id))]}
  (nav/prev-visible-block db session current-id))

(defn doc-range
  "Return set of node IDs between a and b (inclusive) in document order.

   Uses doc-only pre-order traversal indexes (excludes session/trash nodes).
   Returns nil if either node lacks traversal metadata (e.g., not in tree)."
  [db a b]
  (let [pre (get-in db [:derived :doc/pre])
        id-by-pre (get-in db [:derived :doc/id-by-pre])]
    (when (and (contains? pre a) (contains? pre b))
      (let [[start end] (sort [(get pre a) (get pre b)])]
        (->> (range start (inc end))
             (keep id-by-pre)
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
  {:pre [(map? db) (map? session)]}
  (let [visible (visible-blocks db session)
        a-idx (.indexOf visible a)
        b-idx (.indexOf visible b)]
    (if (and (not= a-idx -1) (not= b-idx -1))
      (let [[start end] (sort [a-idx b-idx])]
        (set (subvec visible start (inc end))))
      #{})))

;; ── Block Text & Page Queries ─────────────────────────────────────────────────
;; SIGNATURE: [db ...] - DB-only (except same-page? which needs session)

(defn block-text
  "Get text content of a block, or empty string if not found.

   This is the canonical way to read block text across plugins.
   Note: For editing contexts with uncommitted buffer text,
   plugins.context-editing uses its own version that checks intent."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

(defn same-page?
  "Check if two blocks are on the same page.

   Returns true if:
   - No current-page is set (not in page-scoped mode)
   - Both blocks have the same page ancestor
   - Either block has no page ancestor (doc root level)

   Used by navigation and selection plugins to respect page boundaries."
  [db session block-a block-b]
  (or (nil? (current-page session))
      (= (page-of db block-a) (page-of db block-b))))

(defn all-pages
  "Get list of all page IDs (direct children of :doc root)."
  [db]
  (children db :doc))

(defn page-title
  "Get title of a page by ID."
  [db page-id]
  (get-in db [:nodes page-id :props :title] "Untitled"))

(defn find-page-by-name
  "Find page ID by title (case-insensitive).

   Returns nil if page not found."
  [db page-name]
  (when page-name
    (let [normalize (comp str/lower-case str/trim)
          normalized-name (normalize page-name)]
      (->> (all-pages db)
           (m/find-first #(= normalized-name (normalize (page-title db %))))))))

(defn page-empty?
  "Check if a page has no meaningful content.
   A page is empty if all its child blocks have blank/empty text.
   Returns true if page has no children or all children are blank."
  [db page-id]
  (->> (children db page-id)
       (every? (comp (some-fn nil? str/blank?)
                     #(block-text db %)))))

(defn tombstone?
  "Check if a node is marked as a tombstone (permanently deleted)."
  [db node-id]
  (get-in db [:nodes node-id :props :tombstone?]))

(defn trashed-pages
  "Get list of all page IDs in trash (excludes tombstoned nodes)."
  [db]
  (->> (children db :trash)
       (remove (partial tombstone? db))))

(defn trashed-at
  "Get the timestamp when a page was trashed, or nil if not set."
  [db page-id]
  (get-in db [:nodes page-id :props :trashed-at]))

;; ── Page Metadata Queries ─────────────────────────────────────────────────────
;; SIGNATURE: [db ...] - DB-only, no session parameter

(defn created-at
  "Get the creation timestamp of a node (page or block)."
  [db node-id]
  (get-in db [:nodes node-id :props :created-at]))

(defn updated-at
  "Get the last update timestamp of a node (page or block)."
  [db node-id]
  (get-in db [:nodes node-id :props :updated-at]))

(defn page-block-count
  "Count total blocks under a page (recursive, all descendants)."
  [db page-id]
  (count (descendants-of db page-id)))

(defn page-word-count
  "Count total words across all blocks under a page."
  [db page-id]
  (letfn [(count-words [text]
            (if (str/blank? text)
              0
              (count (str/split (str/trim text) #"\s+"))))]
    (->> (cons page-id (descendants-of db page-id))
         (map (comp count-words #(block-text db %)))
         (reduce + 0))))

(defn page-metadata
  "Get all metadata for a page in one call.
   Returns {:title :created-at :updated-at :block-count :word-count :favorite? :journal?}."
  [db page-id favorites-set]
  (let [title (page-title db page-id)
        created (created-at db page-id)
        journal? (boolean
                  (some #(re-matches % title)
                        [#"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th), \d{4}"
                         #"\d{4}-\d{2}-\d{2}"]))]
    {:id page-id
     :title title
     :created-at created
     :updated-at (or (updated-at db page-id) created)
     :block-count (page-block-count db page-id)
     :word-count (page-word-count db page-id)
     :favorite? (contains? favorites-set page-id)
     :journal? journal?}))
