(ns kernel.navigation
  "Navigation helpers with fold-aware visibility.

   These functions compute visibility at call-time using (db, session),
   respecting folding and zoom state from session.

   All functions work with the VISIBLE outline only - folded/zoomed-out nodes
   are automatically excluded.

   DESIGN NOTE: Visibility is computed ad-hoc rather than via derived index
   because fold/zoom state lives in session (ephemeral) and changes don't
   trigger derive-indexes. This matches the pattern in kernel.query/visible-blocks-in-dom-order."
  (:require [kernel.constants :as const]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- folded-set
  "Get the set of folded block IDs from session."
  [session]
  (get-in session [:ui :folded] #{}))

(defn- zoom-root
  "Get the current zoom root from session, or nil if not zoomed."
  [session]
  (get-in session [:ui :zoom-root]))

(defn- visible-children
  "Get visible children of a parent, respecting fold state.
   
   If parent is folded, returns empty vector (children hidden).
   Otherwise returns all children from canonical children-by-parent."
  [db session parent-id]
  (if (contains? (folded-set session) parent-id)
    [] ;; Folded - children hidden
    (get-in db [:children-by-parent parent-id] [])))

(defn- parent-of
  "Get parent of a node from :parent-of index."
  [db node-id]
  (get-in db [:derived :parent-of node-id]))

(defn- index-in-siblings
  "Get the index of a node within its visible siblings."
  [db session node-id]
  (let [parent (parent-of db node-id)
        siblings (visible-children db session parent)]
    (.indexOf siblings node-id)))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn visible-siblings
  "Get all visible siblings of a node (including the node itself).
   Returns a vector of node IDs in order."
  [db session node-id]
  (let [parent (parent-of db node-id)]
    (visible-children db session parent)))

(defn prev-visible-sibling
  "Get the previous visible sibling of a node.
   Returns nil if the node is the first visible child."
  [db session node-id]
  (let [siblings (visible-siblings db session node-id)
        idx (index-in-siblings db session node-id)]
    (when (and (>= idx 0) (pos? idx))
      (nth siblings (dec idx) nil))))

(defn next-visible-sibling
  "Get the next visible sibling of a node.
   Returns nil if the node is the last visible child."
  [db session node-id]
  (let [siblings (visible-siblings db session node-id)
        idx (index-in-siblings db session node-id)]
    (when (and (>= idx 0) (< idx (dec (count siblings))))
      (nth siblings (inc idx) nil))))

(defn first-visible-child
  "Get the first visible child of a node.
   Returns nil if the node has no visible children (or is folded)."
  [db session parent-id]
  (first (visible-children db session parent-id)))

(defn last-visible-child
  "Get the last visible child of a node.
   Returns nil if the node has no visible children (or is folded)."
  [db session parent-id]
  (last (visible-children db session parent-id)))

(defn has-visible-children?
  "Check if a node has any visible children (not folded and has children)."
  [db session parent-id]
  (boolean (seq (visible-children db session parent-id))))

(defn visible?
  "Check if a node is visible (not hidden by folding/zoom).

   A node is visible if:
   1. It's a root node, OR
   2. None of its ancestors are folded"
  [db session node-id]
  (or (contains? (set const/roots) node-id)
      ;; Check if any ancestor is folded
      (loop [current (parent-of db node-id)]
        (cond
          (nil? current) true
          (contains? (set const/roots) current) true
          (contains? (folded-set session) current) false
          :else (recur (parent-of db current))))))

(defn prev-visible-block
  "Get the previous visible block in document order.

   Navigation order:
   1. Previous sibling's last visible descendant (depth-first)
   2. Previous sibling (if no descendants)
   3. Parent (if no previous sibling)
   4. nil (if at document root)

   Examples:
   - From B2 → B1
   - From B1.1 → B1
   - From B1 → (parent or nil)"
  [db session node-id]
  (if-let [prev-sib (prev-visible-sibling db session node-id)]
    ;; Go to previous sibling's last descendant
    (loop [current prev-sib]
      (if-let [last-child (last-visible-child db session current)]
        (recur last-child)
        current))
    ;; No previous sibling, go to parent
    (let [parent (parent-of db node-id)]
      (when (and parent (not (contains? (set const/roots) parent)))
        parent))))

(defn next-visible-block
  "Get the next visible block in document order.

   Navigation order:
   1. First visible child (if has children and not folded)
   2. Next sibling (if no children or folded)
   3. Parent's next sibling (if no next sibling)
   4. Continue up until finding next sibling or reaching root
   5. nil (if no more blocks)

   Examples:
   - From B1 → B1.1 (if has children and not folded)
   - From B1.1 → B1.2 (next sibling)
   - From B1 (no children) → B2"
  [db session node-id]
  (or
   ;; Try first visible child (respects folding)
   (first-visible-child db session node-id)

   ;; Try next sibling
   (next-visible-sibling db session node-id)

   ;; Walk up to find parent's next sibling
   (loop [current node-id]
     (let [parent (parent-of db current)]
       (when (and parent (not (contains? (set const/roots) parent)))
         (or (next-visible-sibling db session parent)
             (recur parent)))))))

(defn ancestor-chain
  "Get all ancestors of a node, from immediate parent to root.
   Returns a vector of node IDs.
   
   Note: This is visibility-independent (shows structural ancestors)."
  [db node-id]
  (loop [current node-id
         chain []]
    (if-let [parent (parent-of db current)]
      (if (contains? (set const/roots) parent)
        chain
        (recur parent (conj chain parent)))
      chain)))

(defn first-visible-block
  "Get the first visible block in the outline.
   Respects zoom root if active."
  [db session]
  (let [root (or (zoom-root session) const/root-doc)]
    (first-visible-child db session root)))

(defn last-visible-block
  "Get the last visible block in the outline.
   Navigates to the deepest last descendant."
  [db session]
  (let [root (or (zoom-root session) const/root-doc)]
    (loop [current (last-visible-child db session root)]
      (if-let [last-child (last-visible-child db session current)]
        (recur last-child)
        current))))

(defn visible-block-count
  "Count total number of visible blocks in the outline."
  [db session]
  (let [root (or (zoom-root session) const/root-doc)]
    (letfn [(count-descendants [node-id]
              (let [children (visible-children db session node-id)]
                (+ (count children)
                   (reduce + 0 (map count-descendants children)))))]
      (count-descendants root))))

(comment
  ;; Example usage in REPL

  (require '[kernel.db :as db])
  (require '[kernel.transaction :as tx])
  (require '[kernel.navigation :as nav])

  ;; Create a simple tree
  (def db0 (db/empty-db))
  (def result (tx/interpret db0
                            [{:op :create-node :id "b1" :type :block :props {:text "Parent"}}
                             {:op :place :id "b1" :under :doc :at :last}
                             {:op :create-node :id "b2" :type :block :props {:text "Child 1"}}
                             {:op :place :id "b2" :under "b1" :at :last}
                             {:op :create-node :id "b3" :type :block :props {:text "Child 2"}}
                             {:op :place :id "b3" :under "b1" :at :last}
                             {:op :create-node :id "b4" :type :block :props {:text "Sibling"}}
                             {:op :place :id "b4" :under :doc :at :last}]))
  (def db1 (:db result))
  (def session {:ui {:folded #{} :zoom-root nil}})

  ;; Test navigation (now requires session)
  (nav/visible-siblings db1 session "b2")
  ;; => ["b2" "b3"]

  (nav/prev-visible-sibling db1 session "b3")
  ;; => "b2"

  (nav/next-visible-sibling db1 session "b2")
  ;; => "b3"

  (nav/first-visible-child db1 session "b1")
  ;; => "b2"

  (nav/prev-visible-block db1 session "b2")
  ;; => "b1"

  (nav/next-visible-block db1 session "b1")
  ;; => "b2"

  (nav/next-visible-block db1 session "b3")
  ;; => "b4"

  ;; Test with folding
  (def session-folded {:ui {:folded #{"b1"} :zoom-root nil}})

  (nav/first-visible-child db1 session-folded "b1")
  ;; => nil (b1 is folded, children hidden)

  (nav/next-visible-block db1 session-folded "b1")
  ;; => "b4" (skips folded children, goes to next sibling)

  (nav/visible-block-count db1 session)
  ;; => 4

  (nav/visible-block-count db1 session-folded)
  ;; => 2 (only b1 and b4 visible)
  )
