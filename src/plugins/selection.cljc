(ns plugins.selection
  "Selection state management via session nodes.

   Selection is stored as a node under :session root.
   Structure: session/selection node with :props {:nodes #{id1 id2} :focus id2 :anchor id1}

   All selection changes emit ops (:update-node on session/selection).
   This enables full undo/redo of selection state.

   Implements intent->ops multimethod from core.intent."
  (:require [clojure.set :as set]
            [core.intent :as intent]))

;; ── Selection state accessors ────────────────────────────────────────────────

(defn- get-selection-state
  "Returns the selection state map from session/selection node."
  [DB]
  (get-in DB [:nodes "session/selection" :props] {:nodes #{} :focus nil :anchor nil}))

(defn get-selection
  "Returns the set of selected node IDs (possibly empty)."
  [DB]
  (:nodes (get-selection-state DB) #{}))

(defn get-focus
  "Returns the focused node ID (the 'current' node for navigation), or nil."
  [DB]
  (:focus (get-selection-state DB)))

(defn get-anchor
  "Returns the anchor node ID (starting point for range selection), or nil."
  [DB]
  (:anchor (get-selection-state DB)))

(defn selected?
  "Returns true if the given node ID is in the selection."
  [DB id]
  (contains? (get-selection DB) id))

(defn selection-count
  "Returns the number of selected nodes."
  [DB]
  (count (get-selection DB)))

(defn has-selection?
  "Returns true if any nodes are selected."
  [DB]
  (pos? (selection-count DB)))

(defn get-selected-nodes
  "Returns set of currently selected node IDs (alias for get-selection)."
  [DB]
  (get-selection DB))

;; ── Selection modification ───────────────────────────────────────────────────

(defn select
  "Replace selection with the given node IDs (can be single ID or collection).
   Sets focus to the last ID in the collection (or the single ID)."
  [DB ids]
  (let [ids-vec (if (coll? ids) (vec ids) [ids])
        ids-set (set ids-vec)
        new-focus (last ids-vec)]
    (assoc DB :selection {:nodes ids-set
                          :focus new-focus
                          :anchor new-focus})))

(defn- doc-range
  "Return the set of node IDs between a and b (inclusive) in document order.
   Falls back to nil if either node is missing traversal metadata."
  [DB a b]
  (let [pre (get-in DB [:derived :pre])
        id-by-pre (get-in DB [:derived :id-by-pre])]
    (when (and (contains? pre a)
               (contains? pre b))
      (let [a-idx (get pre a)
            b-idx (get pre b)
            [start end] (if (<= a-idx b-idx)
                          [a-idx b-idx]
                          [b-idx a-idx])]
        (->> (range start (inc end))
             (map id-by-pre)
             (remove nil?)
             set)))))

(defn extend-selection
  "Add node ID(s) to the current selection.
   Supports range selection (Shift+Click) by selecting all nodes between the
   anchor and the newly focused node."
  [DB ids]
  (let [state (get-selection-state DB)
        ids-vec (if (coll? ids) (vec ids) [ids])
        ids-set (set ids-vec)
        new-focus (last ids-vec)
        existing-anchor (:anchor state)
        range-set (when (and existing-anchor (= 1 (count ids-vec)))
                    (doc-range DB existing-anchor new-focus))
        new-anchor (or existing-anchor new-focus)
        new-nodes (or range-set (set/union (:nodes state) ids-set))]
    (assoc DB :selection {:nodes new-nodes
                          :focus new-focus
                          :anchor new-anchor})))

(defn deselect
  "Remove node ID(s) from the current selection.
   If focus is removed, sets focus to first remaining node."
  [DB ids]
  (let [state (get-selection-state DB)
        ids-set (set (if (coll? ids) ids [ids]))
        new-nodes (set/difference (:nodes state) ids-set)
        old-focus (:focus state)
        new-focus (if (contains? ids-set old-focus)
                    (first new-nodes)  ;; Focus was removed, pick first remaining
                    old-focus)]
    (assoc DB :selection {:nodes new-nodes
                          :focus new-focus
                          :anchor (:anchor state)})))

(defn clear
  "Clear all selection."
  [DB]
  (assoc DB :selection {:nodes #{} :focus nil :anchor nil}))

(defn toggle
  "Toggle selection of node ID (add if not selected, remove if selected).
   If adding, sets focus to this node."
  [DB id]
  (if (selected? DB id)
    (deselect DB id)
    (extend-selection DB id)))

;; ── Selection navigation helpers ─────────────────────────────────────────────

(defn select-next-sibling
  "Select the next sibling of the focused node.
   Uses :focus to determine which node to navigate from.
   Clears selection and selects next sibling if it exists."
  [DB]
  (if-let [current (get-focus DB)]
    (if-let [next-id (get-in DB [:derived :next-id-of current])]
      (select DB next-id)
      DB)  ;; No next sibling, keep current selection
    DB))  ;; No focus, nothing to do

(defn select-prev-sibling
  "Select the previous sibling of the focused node.
   Uses :focus to determine which node to navigate from.
   Clears selection and selects previous sibling if it exists."
  [DB]
  (if-let [current (get-focus DB)]
    (if-let [prev-id (get-in DB [:derived :prev-id-of current])]
      (select DB prev-id)
      DB)  ;; No prev sibling, keep current selection
    DB))  ;; No focus, nothing to do

(defn extend-to-next-sibling
  "Extend selection to include next sibling of focused node.
   Like Shift+Down in most editors."
  [DB]
  (if-let [current (get-focus DB)]
    (if-let [next-id (get-in DB [:derived :next-id-of current])]
      (extend-selection DB next-id)
      DB)
    DB))

(defn extend-to-prev-sibling
  "Extend selection to include previous sibling of focused node.
   Like Shift+Up in most editors."
  [DB]
  (if-let [current (get-focus DB)]
    (if-let [prev-id (get-in DB [:derived :prev-id-of current])]
      (extend-selection DB prev-id)
      DB)
    DB))

(defn select-parent
  "Select the parent of the selected node(s).
   If multiple nodes selected, only selects parent if they all share the same parent.
   Otherwise does nothing (invalid state - nodes from different branches)."
  [DB]
  (let [selection (get-selection DB)
        parents (set (keep #(get-in DB [:derived :parent-of %]) selection))]
    (cond
      (= 1 (count parents)) (select DB (first parents))
      (> (count parents) 1) DB  ;; Multiple parents, invalid - keep selection
      :else DB)))  ;; No selection or no parents

(defn select-all-siblings
  "Select all siblings of the focused node.
   Extends selection to include all children of the same parent."
  [DB]
  (if-let [current (get-focus DB)]
    (if-let [parent (get-in DB [:derived :parent-of current])]
      (let [all-siblings (get-in DB [:children-by-parent parent] [])]
        (select DB all-siblings))
      DB)
    DB))

;; ── Intent → Ops ──────────────────────────────────────────────────────────────

(defmethod intent/intent->ops :select
  [DB {:keys [ids]}]
  (let [ids-vec (if (coll? ids) (vec ids) [ids])
        ids-set (set ids-vec)
        new-focus (last ids-vec)]
    [{:op :update-node
      :id "session/selection"
      :props {:nodes ids-set :focus new-focus :anchor new-focus}}]))

(defmethod intent/intent->ops :extend-selection
  [DB {:keys [ids]}]
  (let [state (get-selection-state DB)
        ids-vec (if (coll? ids) (vec ids) [ids])
        ids-set (set ids-vec)
        new-focus (last ids-vec)
        existing-anchor (:anchor state)
        range-set (when (and existing-anchor (= 1 (count ids-vec)))
                    (doc-range DB existing-anchor new-focus))
        new-anchor (or existing-anchor new-focus)
        new-nodes (or range-set (set/union (:nodes state) ids-set))]
    [{:op :update-node
      :id "session/selection"
      :props {:nodes new-nodes :focus new-focus :anchor new-anchor}}]))

(defmethod intent/intent->ops :deselect
  [DB {:keys [ids]}]
  (let [state (get-selection-state DB)
        ids-set (set (if (coll? ids) ids [ids]))
        new-nodes (set/difference (:nodes state) ids-set)
        old-focus (:focus state)
        new-focus (if (contains? ids-set old-focus)
                    (first new-nodes)
                    old-focus)]
    [{:op :update-node
      :id "session/selection"
      :props {:nodes new-nodes :focus new-focus :anchor (:anchor state)}}]))

(defmethod intent/intent->ops :clear-selection
  [_DB _]
  [{:op :update-node
    :id "session/selection"
    :props {:nodes #{} :focus nil :anchor nil}}])

(defmethod intent/intent->ops :toggle-selection
  [DB {:keys [id]}]
  (if (selected? DB id)
    (intent/intent->ops DB {:type :deselect :ids id})
    (intent/intent->ops DB {:type :extend-selection :ids id})))

(defmethod intent/intent->ops :select-next-sibling
  [DB _]
  (when-let [current (get-focus DB)]
    (when-let [next-id (get-in DB [:derived :next-id-of current])]
      (intent/intent->ops DB {:type :select :ids next-id}))))

(defmethod intent/intent->ops :select-prev-sibling
  [DB _]
  (when-let [current (get-focus DB)]
    (when-let [prev-id (get-in DB [:derived :prev-id-of current])]
      (intent/intent->ops DB {:type :select :ids prev-id}))))

(defmethod intent/intent->ops :extend-to-next-sibling
  [DB _]
  (when-let [current (get-focus DB)]
    (when-let [next-id (get-in DB [:derived :next-id-of current])]
      (intent/intent->ops DB {:type :extend-selection :ids next-id}))))

(defmethod intent/intent->ops :extend-to-prev-sibling
  [DB _]
  (when-let [current (get-focus DB)]
    (when-let [prev-id (get-in DB [:derived :prev-id-of current])]
      (intent/intent->ops DB {:type :extend-selection :ids prev-id}))))

(defmethod intent/intent->ops :select-parent
  [DB _]
  (let [selection (get-selection DB)
        parents (set (keep #(get-in DB [:derived :parent-of %]) selection))]
    (when (= 1 (count parents))
      (intent/intent->ops DB {:type :select :ids (first parents)}))))

(defmethod intent/intent->ops :select-all-siblings
  [DB _]
  (when-let [current (get-focus DB)]
    (when-let [parent (get-in DB [:derived :parent-of current])]
      (let [all-siblings (get-in DB [:children-by-parent parent] [])]
        (intent/intent->ops DB {:type :select :ids all-siblings})))))
