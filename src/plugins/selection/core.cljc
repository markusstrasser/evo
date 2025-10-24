(ns plugins.selection.core
  "Selection state management plugin (ADR-015 pattern).

   Manages the :selection namespace at DB root with focus tracking.
   Structure: {:selection {:nodes #{id1 id2} :focus id2 :anchor id1}}

   This supersedes ADR-012's boolean property approach."
  (:require [clojure.set :as set]))

;; ── Selection state accessors ────────────────────────────────────────────────

(defn- get-selection-state
  "Returns the selection state map."
  [DB]
  (get DB :selection {:nodes #{} :focus nil :anchor nil}))

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

(defn extend-selection
  "Add node ID(s) to the current selection.
   Updates focus to the last added ID, keeps existing anchor."
  [DB ids]
  (let [state (get-selection-state DB)
        ids-vec (if (coll? ids) (vec ids) [ids])
        ids-set (set ids-vec)
        new-focus (last ids-vec)
        existing-anchor (:anchor state)
        new-anchor (or existing-anchor new-focus)]
    (assoc DB :selection {:nodes (set/union (:nodes state) ids-set)
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
