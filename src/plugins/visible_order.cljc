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

(defn- get-folded-set
  "Get the set of folded block IDs from session state."
  [db]
  (get-in db [:nodes const/session-ui-id :props :folded] #{}))

(defn- get-zoom-root
  "Get the current zoom root (rendering root block ID)."
  [db]
  (get-in db [:nodes const/session-ui-id :props :zoom-root]))

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
   - Filtered children if zoom is active (only show in-scope descendants)
   - All children otherwise"
  [db parent-of parent-id zoom-root folded-set]
  (let [children (get-in db [:children-by-parent parent-id] [])]
    (cond
      ;; Parent is folded → no visible children
      (contains? folded-set parent-id)
      []

      ;; Zoom is active → filter out children not under zoom root
      (and zoom-root (not= parent-id zoom-root))
      (filterv #(under-subtree? parent-of zoom-root %) children)

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
   3. Correctness > Performance (project philosophy)"
  [db]
  (let [parent-of (get-in db [:derived :parent-of])
        zoom-root (get-zoom-root db)
        folded-set (get-folded-set db)
        all-parents (keys (:children-by-parent db))

        visible-children
        (into {}
              (map (fn [parent-id]
                     [parent-id (compute-visible-children db parent-of parent-id zoom-root folded-set)])
                   all-parents))]

    {:visible-order {:by-parent visible-children}}))

;; ── Plugin Registration ───────────────────────────────────────────────────────

(registry/register! :visible-order compute-visible-order)

(comment
  ;; Example usage in REPL

  (require '[kernel.db :as db])
  (require '[kernel.api :as api])
  (require '[plugins.visible-order :as vo])

  ;; Create a simple tree
  (def db0 (db/empty-db))
  (def [db1 _] (api/transact db0
                 [{:op :create-node :id "b1" :type :block :props {:text "Parent"}}
                  {:op :place :id "b1" :under :doc :at :last}
                  {:op :create-node :id "b2" :type :block :props {:text "Child"}}
                  {:op :place :id "b2" :under "b1" :at :last}
                  {:op :create-node :id "b3" :type :block :props {:text "Sibling"}}
                  {:op :place :id "b3" :under :doc :at :last}]))

  ;; Initially, all children are visible
  (get-in db1 [:derived :visible-order :by-parent :doc])
  ;; => ["b1" "b3"]

  (get-in db1 [:derived :visible-order :by-parent "b1"])
  ;; => ["b2"]

  ;; Fold "b1" to hide its children
  (def [db2 _] (api/transact db1
                 [{:op :update-node
                   :id const/session-ui-id
                   :props {:folded #{"b1"}}}]))

  (get-in db2 [:derived :visible-order :by-parent "b1"])
  ;; => []  ; children hidden because b1 is folded

  ;; Zoom into "b1"
  (def [db3 _] (api/transact db2
                 [{:op :update-node
                   :id const/session-ui-id
                   :props {:zoom-root "b1"}}]))

  (get-in db3 [:derived :visible-order :by-parent :doc])
  ;; => ["b1"]  ; only b1 and its descendants are visible when zoomed

  )
