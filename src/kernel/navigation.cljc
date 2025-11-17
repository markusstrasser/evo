(ns kernel.navigation
  "Navigation helpers using :visible-order derived index.

   These functions use the precomputed :visible-order index to efficiently
   navigate the visible outline (respecting folding and zoom state).

   This replaces runtime filtering with index lookups for better performance
   and simpler code.

   All functions work with the VISIBLE outline only - folded/zoomed-out nodes
   are automatically excluded."
  (:require [kernel.constants :as const]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- visible-children
  "Get visible children of a parent from the :visible-order index."
  [db parent-id]
  (get-in db [:derived :visible-order :by-parent parent-id] []))

(defn- parent-of
  "Get parent of a node from :parent-of index."
  [db node-id]
  (get-in db [:derived :parent-of node-id]))

(defn- index-in-siblings
  "Get the index of a node within its visible siblings."
  [db node-id]
  (let [parent (parent-of db node-id)
        siblings (visible-children db parent)]
    (.indexOf siblings node-id)))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn visible-siblings
  "Get all visible siblings of a node (including the node itself).
   Returns a vector of node IDs in order."
  [db node-id]
  (let [parent (parent-of db node-id)]
    (visible-children db parent)))

(defn prev-visible-sibling
  "Get the previous visible sibling of a node.
   Returns nil if the node is the first visible child."
  [db node-id]
  (let [siblings (visible-siblings db node-id)
        idx (index-in-siblings db node-id)]
    (when (and (>= idx 0) (pos? idx))
      (nth siblings (dec idx) nil))))

(defn next-visible-sibling
  "Get the next visible sibling of a node.
   Returns nil if the node is the last visible child."
  [db node-id]
  (let [siblings (visible-siblings db node-id)
        idx (index-in-siblings db node-id)]
    (when (and (>= idx 0) (< idx (dec (count siblings))))
      (nth siblings (inc idx) nil))))

(defn first-visible-child
  "Get the first visible child of a node.
   Returns nil if the node has no visible children."
  [db parent-id]
  (first (visible-children db parent-id)))

(defn last-visible-child
  "Get the last visible child of a node.
   Returns nil if the node has no visible children."
  [db parent-id]
  (last (visible-children db parent-id)))

(defn has-visible-children?
  "Check if a node has any visible children."
  [db parent-id]
  (seq (visible-children db parent-id)))

(defn visible?
  "Check if a node is visible (not hidden by folding/zoom).

   A node is visible if:
   1. It appears in its parent's visible children list
   2. OR it's a root node"
  [db node-id]
  (or (contains? (set const/roots) node-id)
      (let [parent (parent-of db node-id)
            siblings (visible-children db parent)]
        (some #{node-id} siblings))))

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
  [db node-id]
  (if-let [prev-sib (prev-visible-sibling db node-id)]
    ;; Go to previous sibling's last descendant
    (loop [current prev-sib]
      (if-let [last-child (last-visible-child db current)]
        (recur last-child)
        current))
    ;; No previous sibling, go to parent
    (let [parent (parent-of db node-id)]
      (when (and parent (not (contains? (set const/roots) parent)))
        parent))))

(defn next-visible-block
  "Get the next visible block in document order.

   Navigation order:
   1. First visible child (if has children)
   2. Next sibling (if no children)
   3. Parent's next sibling (if no next sibling)
   4. Continue up until finding next sibling or reaching root
   5. nil (if no more blocks)

   Examples:
   - From B1 → B1.1 (if has children)
   - From B1.1 → B1.2 (next sibling)
   - From B1 (no children) → B2"
  [db node-id]
  (or
   ;; Try first visible child
   (first-visible-child db node-id)

   ;; Try next sibling
   (next-visible-sibling db node-id)

   ;; Walk up to find parent's next sibling
   (loop [current node-id]
     (let [parent (parent-of db current)]
       (when (and parent (not (contains? (set const/roots) parent)))
         (or (next-visible-sibling db parent)
             (recur parent)))))))

(defn ancestor-chain
  "Get all visible ancestors of a node, from immediate parent to root.
   Returns a vector of node IDs."
  [db node-id]
  (loop [current node-id
         ancestors []]
    (if-let [parent (parent-of db current)]
      (if (contains? (set const/roots) parent)
        ancestors
        (recur parent (conj ancestors parent)))
      ancestors)))

(defn first-visible-block
  "Get the first visible block in the outline.
   Respects zoom root if active."
  [db]
  (let [zoom-root (get-in db [:nodes const/session-ui-id :props :zoom-root])
        root (or zoom-root const/root-doc)]
    (first-visible-child db root)))

(defn last-visible-block
  "Get the last visible block in the outline.
   Navigates to the deepest last descendant."
  [db]
  (let [zoom-root (get-in db [:nodes const/session-ui-id :props :zoom-root])
        root (or zoom-root const/root-doc)]
    (loop [current (last-visible-child db root)]
      (if-let [last-child (last-visible-child db current)]
        (recur last-child)
        current))))

(defn visible-block-count
  "Count total number of visible blocks in the outline."
  [db]
  (let [zoom-root (get-in db [:nodes const/session-ui-id :props :zoom-root])
        root (or zoom-root const/root-doc)]
    (letfn [(count-descendants [node-id]
              (let [children (visible-children db node-id)]
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

  ;; Test navigation
  (nav/visible-siblings db1 "b2")
  ;; => ["b2" "b3"]

  (nav/prev-visible-sibling db1 "b3")
  ;; => "b2"

  (nav/next-visible-sibling db1 "b2")
  ;; => "b3"

  (nav/first-visible-child db1 "b1")
  ;; => "b2"

  (nav/prev-visible-block db1 "b2")
  ;; => "b1"

  (nav/next-visible-block db1 "b1")
  ;; => "b2"

  (nav/next-visible-block db1 "b3")
  ;; => "b4"

  (nav/visible-block-count db1)
  ;; => 4

  )
