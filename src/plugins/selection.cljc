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
)

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

(intent/register-intent! :select
  {:doc "Replace selection with given node ID(s). Last ID becomes focus."
   :spec [:map [:type [:= :select]] [:ids [:or :string [:vector :string]]]]
   :handler (fn [_db {:keys [ids]}]
              [{:op :update-node
                :id const/session-selection-id
                :props (calc-select-props ids)}])})

(intent/register-intent! :extend-selection
  {:doc "Add node ID(s) to selection. Supports range selection if single ID."
   :spec [:map [:type [:= :extend-selection]] [:ids [:or :string [:vector :string]]]]
   :handler (fn [db {:keys [ids]}]
              (let [state (get-selection-state db)]
                [{:op :update-node
                  :id const/session-selection-id
                  :props (calc-extend-props db state ids)}]))})

(intent/register-intent! :deselect
  {:doc "Remove node ID(s) from selection."
   :spec [:map [:type [:= :deselect]] [:ids [:or :string [:vector :string]]]]
   :handler (fn [db {:keys [ids]}]
              (let [state (get-selection-state db)]
                [{:op :update-node
                  :id const/session-selection-id
                  :props (calc-deselect-props state ids)}]))})

(intent/register-intent! :clear-selection
  {:doc "Clear all selection."
   :spec [:map [:type [:= :clear-selection]]]
   :handler (fn [_db _]
              [{:op :update-node
                :id const/session-selection-id
                :props (calc-clear-props)}])})

(intent/register-intent! :toggle-selection
  {:doc "Toggle selection of node (add if not selected, remove if selected)."
   :spec [:map [:type [:= :toggle-selection]] [:id :string]]
   :handler (fn [db {:keys [id]}]
              (if (tree/selected? db id)
                (intent/intent->ops db {:type :deselect :ids id})
                (intent/intent->ops db {:type :extend-selection :ids id})))})

(intent/register-intent! :select-next-sibling
  {:doc "Select next sibling of focused node."
   :spec [:map [:type [:= :select-next-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [next-id (tree/next-sibling db current)]
                  (intent/intent->ops db {:type :select :ids next-id}))))})

(intent/register-intent! :select-prev-sibling
  {:doc "Select previous sibling of focused node."
   :spec [:map [:type [:= :select-prev-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [prev-id (tree/prev-sibling db current)]
                  (intent/intent->ops db {:type :select :ids prev-id}))))})

(intent/register-intent! :extend-to-next-sibling
  {:doc "Extend selection to include next sibling."
   :spec [:map [:type [:= :extend-to-next-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [next-id (tree/next-sibling db current)]
                  (intent/intent->ops db {:type :extend-selection :ids next-id}))))})

(intent/register-intent! :extend-to-prev-sibling
  {:doc "Extend selection to include previous sibling."
   :spec [:map [:type [:= :extend-to-prev-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [prev-id (tree/prev-sibling db current)]
                  (intent/intent->ops db {:type :extend-selection :ids prev-id}))))})

(intent/register-intent! :select-parent
  {:doc "Select parent of selected node(s)."
   :spec [:map [:type [:= :select-parent]]]
   :handler (fn [db _]
              (let [selection (tree/selection db)
                    parents (set (keep #(tree/parent-of db %) selection))]
                (when (= 1 (count parents))
                  (intent/intent->ops db {:type :select :ids (first parents)}))))})

(intent/register-intent! :select-all-siblings
  {:doc "Select all siblings of focused node."
   :spec [:map [:type [:= :select-all-siblings]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [parent (tree/parent-of db current)]
                  (let [all-siblings (tree/children db parent)]
                    (intent/intent->ops db {:type :select :ids all-siblings})))))})

;; ── Navigation Intents ────────────────────────────────────────────────────────

(intent/register-intent! :navigate-up
  {:doc "Navigate to previous visible block. Updates selection focus only."
   :spec [:map [:type [:= :navigate-up]] [:block-id {:optional true} :string]]
   :handler (fn [db {:keys [block-id]}]
              (when-let [prev-id (tree/prev-sibling db (or block-id (tree/focus db)))]
                [{:op :update-node :id const/session-selection-id :props {:focus prev-id}}]))})

(intent/register-intent! :navigate-down
  {:doc "Navigate to next visible block. Updates selection focus only."
   :spec [:map [:type [:= :navigate-down]] [:block-id {:optional true} :string]]
   :handler (fn [db {:keys [block-id]}]
              (when-let [next-id (tree/next-sibling db (or block-id (tree/focus db)))]
                [{:op :update-node :id const/session-selection-id :props {:focus next-id}}]))})
