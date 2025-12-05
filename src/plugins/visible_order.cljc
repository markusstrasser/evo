(ns plugins.visible-order
  "Visible-order derived index plugin.

   Computes a filtered view of the outline tree that respects:
   - Folding state (children of folded nodes are hidden)
   - Zoom level (only descendants of zoom root are included)

   This index enables efficient navigation without recalculating visibility
   on every keystroke.

   Structure:
   {:visible-order {:by-parent {parent-id [visible-child-ids]}}}

   Example:
   Given:
   - :doc has children [\"b1\" \"b3\" \"b4\"]
   - \"b1\" has children [\"b2\"] and is NOT folded
   - \"b3\" has children [\"b3a\"] and IS folded
   - \"b4\" has no children

   Result:
   {:visible-order {:by-parent {:doc [\"b1\" \"b3\" \"b4\"]
                                \"b1\" [\"b2\"]
                                \"b3\" []     ; folded, so children hidden
                                \"b4\" []}}}

   Note: Unlike :children-by-parent (canonical ordering), :visible-order
   changes dynamically based on ephemeral session state (fold/zoom)."
  (:require [kernel.constants :as const]
            [plugins.registry :as registry]))

;; ── Private Helpers ───────────────────────────────────────────────────────────

;; ── Session State Access ──────────────────────────────────────────────────────
;;
;; ARCHITECTURAL NOTE (Phase 6):
;; This plugin computes :visible-order as a derived index during transaction.
;; However, fold/zoom state lives in session (not DB), and the derive-indexes
;; phase doesn't have access to session.
;;
;; Current workaround: Return empty/nil (all blocks visible, no zoom boundary)
;; This means navigation works but doesn't respect folding or zoom.
;;
;; Future options:
;; 1. Pass session to derive-indexes (breaking change to transaction pipeline)
;; 2. Compute visibility lazily in UI layer (remove this derived index)
;; 3. Make fold/zoom state part of DB (architectural regression)
;;
;; For now, the UI works because navigation falls back to tree traversal.

(defn- get-folded-set
  "Get the set of folded block IDs.

   NOTE: Returns empty until session access is added to derive-indexes."
  [_db]
  #{})

(defn- get-zoom-root
  "Get the current zoom root (rendering root block ID).

   NOTE: Returns nil until session access is added to derive-indexes."
  [_db]
  nil)

(defn- get-current-page
  "Get the current active page ID.

   NOTE: Returns nil until session access is added to derive-indexes."
  [_db]
  nil)

(defn- active-outline-root
  "Get the active outline root for navigation and rendering.

   LOGSEQ PARITY: Determines which subtree is 'visible' for navigation.
   Priority order:
   1. Zoom root (when zoomed into a block)
   2. Current page (when a page is selected)
   3. Document root (fallback)

   This ensures navigation stays within the rendered outline, preventing
   arrow keys from jumping across pages or into hidden subtrees."
  [db]
  (or (get-zoom-root db)
      (get-current-page db)
      :doc))

(defn- under-subtree?
  "Check if a node ID is under a given subtree root by walking parent chain.
   Returns true if the node is a descendant of subtree-root or IS subtree-root."
  [parent-of subtree-root node-id]
  (cond
    (= node-id subtree-root) true
    (keyword? node-id) false         ; reached a root
    (nil? node-id) false              ; no parent
    :else (recur parent-of subtree-root (get parent-of node-id))))

(defn- compute-visible-children
  "Compute visible children for a single parent.

   Returns:
   - Empty vector if parent is folded (children hidden)
   - Filtered children if outline root is active (only show in-scope descendants)
   - All children otherwise

   LOGSEQ PARITY: Respects current page as implicit zoom root (§4.1).
   When on Projects page, navigation only sees Projects subtree."
  [db parent-of parent-id outline-root folded-set]
  (let [children (get-in db [:children-by-parent parent-id] [])]
    (cond
      ;; Parent is folded → no visible children
      (contains? folded-set parent-id)
      []

      ;; Outline scope is active (zoom or page) → filter out children not under outline root
      (and outline-root
           (not= outline-root :doc)  ; :doc means no scope restriction
           (not= parent-id outline-root))
      (filterv #(under-subtree? parent-of outline-root %) children)

      ;; Default → all children are visible
      :else
      children)))

;; ── Plugin Implementation ─────────────────────────────────────────────────────

(defn compute-visible-order
  "Compute the :visible-order derived index.

   This is called automatically after every transaction via the plugin system.

   Returns:
   {:visible-order {:by-parent {parent-id [visible-child-ids]}}}

   The index is recomputed from scratch on every transaction. This is acceptable
   because:
   1. Fold/zoom changes are rare (not on every keystroke)
   2. Index computation is O(n) where n = number of parents
   3. Correctness > Performance (project philosophy)

   LOGSEQ PARITY: Uses active-outline-root (zoom → page → doc) to determine
   visibility scope (§4.1)."
  [db]
  (let [parent-of (get-in db [:derived :parent-of])
        outline-root (active-outline-root db)
        folded-set (get-folded-set db)
        all-parents (keys (:children-by-parent db))

        visible-children
        (into {}
              (map (fn [parent-id]
                     [parent-id (compute-visible-children db parent-of parent-id outline-root folded-set)])
                   all-parents))]

    {:visible-order {:by-parent visible-children}}))

;; ── Plugin Registration ───────────────────────────────────────────────────────

(registry/register! :visible-order compute-visible-order)

(comment
  ;; Example usage in REPL

  (require '[kernel.db :as db])
  (require '[kernel.api :as api])

  ;; Create a simple tree
  (def db0 (db/empty-db))
  (def {:keys [db]} (api/dispatch db0 nil
                      {:type :create-node :id "b1" :node-type :block :text "Parent" :under :doc}))

  ;; Query visible order
  (get-in db [:derived :visible-order :by-parent :doc])
  ;; => ["b1"]

  ;; Note: Folding and zoom are now controlled via shell.session atom, not DB operations.
  ;; Use (session/swap-session! assoc-in [:ui :folded] #{"b1"}) to fold blocks.
  )
