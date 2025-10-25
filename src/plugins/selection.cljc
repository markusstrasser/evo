(ns plugins.selection
  "Selection state management via session nodes.

   Selection is stored as a node under :session root.
   Structure: session/selection node with :props {:nodes #{id1 id2} :focus id2 :anchor id1}

   All selection changes emit ops (:update-node on session/selection).
   This enables full undo/redo of selection state.

   Implements intent->ops multimethod from core.intent."
  (:require [clojure.set :as set]
            [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as tree])
  #?(:clj (:require [kernel.intent :refer [defintent]]))
  #?(:cljs (:require-macros [kernel.intent :refer [defintent]])))

;; Private helper for internal use
(defn- get-selection-state [db] (tree/selection-state db))

;; ── Pure Selection Property Calculators ──────────────────────────────────────

(defn- calc-select-props
  "Pure: calculate props for replacing selection with given IDs."
  [ids]
  (let [ids-vec (if (coll? ids) (vec ids) [ids])
        ids-set (set ids-vec)
        new-focus (last ids-vec)]
    {:nodes ids-set :focus new-focus :anchor new-focus}))

(defn- calc-extend-props
  "Pure: calculate props for extending selection with given IDs.
   Supports range selection when single ID provided and anchor exists."
  [db current-state ids]
  (let [ids-vec (if (coll? ids) (vec ids) [ids])
        ids-set (set ids-vec)
        new-focus (last ids-vec)
        existing-anchor (:anchor current-state)
        range-set (when (and existing-anchor (= 1 (count ids-vec)))
                    (tree/doc-range db existing-anchor new-focus))
        new-anchor (or existing-anchor new-focus)
        new-nodes (or range-set (set/union (:nodes current-state) ids-set))]
    {:nodes new-nodes :focus new-focus :anchor new-anchor}))

(defn- calc-deselect-props
  "Pure: calculate props for removing IDs from selection."
  [current-state ids]
  (let [ids-set (set (if (coll? ids) ids [ids]))
        new-nodes (set/difference (:nodes current-state) ids-set)
        old-focus (:focus current-state)
        new-focus (if (contains? ids-set old-focus)
                    (first new-nodes)
                    old-focus)]
    {:nodes new-nodes :focus new-focus :anchor (:anchor current-state)}))

(defn- calc-clear-props
  "Pure: calculate props for clearing selection."
  []
  {:nodes #{} :focus nil :anchor nil})

;; ── Intent → Ops ──────────────────────────────────────────────────────────────

(defintent :select
  {:sig [_db {:keys [ids]}]
   :doc "Replace selection with given node ID(s). Last ID becomes focus."
   :spec [:map [:type [:= :select]] [:ids [:or :string [:vector :string]]]]
   :ops [{:op :update-node
          :id const/session-selection-id
          :props (calc-select-props ids)}]})

(defintent :extend-selection
  {:sig [db {:keys [ids]}]
   :doc "Add node ID(s) to selection. Supports range selection if single ID."
   :spec [:map [:type [:= :extend-selection]] [:ids [:or :string [:vector :string]]]]
   :ops (let [state (get-selection-state db)]
          [{:op :update-node
            :id const/session-selection-id
            :props (calc-extend-props db state ids)}])})

(defintent :deselect
  {:sig [db {:keys [ids]}]
   :doc "Remove node ID(s) from selection."
   :spec [:map [:type [:= :deselect]] [:ids [:or :string [:vector :string]]]]
   :ops (let [state (get-selection-state db)]
          [{:op :update-node
            :id const/session-selection-id
            :props (calc-deselect-props state ids)}])})

(defintent :clear-selection
  {:sig [_db _]
   :doc "Clear all selection."
   :spec [:map [:type [:= :clear-selection]]]
   :ops [{:op :update-node
          :id const/session-selection-id
          :props (calc-clear-props)}]})

(defintent :toggle-selection
  {:sig [db {:keys [id]}]
   :doc "Toggle selection of node (add if not selected, remove if selected)."
   :spec [:map [:type [:= :toggle-selection]] [:id :string]]
   :ops (if (tree/selected? db id)
          (intent/intent->ops db {:type :deselect :ids id})
          (intent/intent->ops db {:type :extend-selection :ids id}))})

(defintent :select-next-sibling
  {:sig [db _]
   :doc "Select next sibling of focused node."
   :spec [:map [:type [:= :select-next-sibling]]]
   :ops (when-let [current (tree/focus db)]
          (when-let [next-id (tree/next-sibling db current)]
            (intent/intent->ops db {:type :select :ids next-id})))})

(defintent :select-prev-sibling
  {:sig [db _]
   :doc "Select previous sibling of focused node."
   :spec [:map [:type [:= :select-prev-sibling]]]
   :ops (when-let [current (tree/focus db)]
          (when-let [prev-id (tree/prev-sibling db current)]
            (intent/intent->ops db {:type :select :ids prev-id})))})

(defintent :extend-to-next-sibling
  {:sig [db _]
   :doc "Extend selection to include next sibling."
   :spec [:map [:type [:= :extend-to-next-sibling]]]
   :ops (when-let [current (tree/focus db)]
          (when-let [next-id (tree/next-sibling db current)]
            (intent/intent->ops db {:type :extend-selection :ids next-id})))})

(defintent :extend-to-prev-sibling
  {:sig [db _]
   :doc "Extend selection to include previous sibling."
   :spec [:map [:type [:= :extend-to-prev-sibling]]]
   :ops (when-let [current (tree/focus db)]
          (when-let [prev-id (tree/prev-sibling db current)]
            (intent/intent->ops db {:type :extend-selection :ids prev-id})))})

(defintent :select-parent
  {:sig [db _]
   :doc "Select parent of selected node(s)."
   :spec [:map [:type [:= :select-parent]]]
   :ops (let [selection (tree/selection db)
              parents (set (keep #(tree/parent-of db %) selection))]
          (when (= 1 (count parents))
            (intent/intent->ops db {:type :select :ids (first parents)})))})

(defintent :select-all-siblings
  {:sig [db _]
   :doc "Select all siblings of focused node."
   :spec [:map [:type [:= :select-all-siblings]]]
   :ops (when-let [current (tree/focus db)]
          (when-let [parent (tree/parent-of db current)]
            (let [all-siblings (tree/children db parent)]
              (intent/intent->ops db {:type :select :ids all-siblings}))))})

;; ── Navigation Intents ────────────────────────────────────────────────────────

(defintent :navigate-up
  {:sig [db {:keys [block-id]}]
   :doc "Navigate to previous visible block. Updates selection focus only."
   :spec [:map [:type [:= :navigate-up]] [:block-id {:optional true} :string]]
   :ops (when-let [prev-id (tree/prev-sibling db (or block-id (tree/focus db)))]
          [{:op :update-node :id const/session-selection-id :props {:focus prev-id}}])})

(defintent :navigate-down
  {:sig [db {:keys [block-id]}]
   :doc "Navigate to next visible block. Updates selection focus only."
   :spec [:map [:type [:= :navigate-down]] [:block-id {:optional true} :string]]
   :ops (when-let [next-id (tree/next-sibling db (or block-id (tree/focus db)))]
          [{:op :update-node :id const/session-selection-id :props {:focus next-id}}])})
