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

;; ── Unified Selection Intent ─────────────────────────────────────────────────

(intent/register-intent! :selection
  {:doc "Unified selection reducer with modes.

   Modes:
   - :replace   - Replace selection with given IDs (last becomes focus)
   - :extend    - Add IDs to selection (supports range if anchor exists)
   - :deselect  - Remove IDs from selection
   - :toggle    - Toggle ID (add if not selected, remove if selected)
   - :clear     - Clear all selection
   - :next      - Select next sibling of focus
   - :prev      - Select previous sibling of focus
   - :parent    - Select parent of selection (if unique)
   - :all-siblings - Select all siblings of focus

   Examples:
     {:type :selection :mode :replace :ids [\"a\" \"b\"]}
     {:type :selection :mode :extend :ids \"c\"}
     {:type :selection :mode :next}
     {:type :selection :mode :clear}"
   :spec [:map
          [:type [:= :selection]]
          [:mode [:enum :replace :extend :deselect :toggle :clear :next :prev :parent :all-siblings]]
          [:ids {:optional true} [:or :string [:vector :string]]]]
   :handler (fn [db {:keys [mode ids]}]
              (let [state (get-selection-state db)
                    props (case mode
                            :replace (calc-select-props ids)
                            :extend (calc-extend-props db state ids)
                            :deselect (calc-deselect-props state ids)
                            :toggle (let [id ids  ;; toggle expects single ID
                                          selected? (contains? (:nodes state) id)]
                                      (if selected?
                                        (calc-deselect-props state id)
                                        (calc-extend-props db state id)))
                            :clear (calc-clear-props)
                            :next (when-let [current (tree/focus db)]
                                    (when-let [next-id (tree/next-sibling db current)]
                                      (calc-select-props next-id)))
                            :prev (when-let [current (tree/focus db)]
                                    (when-let [prev-id (tree/prev-sibling db current)]
                                      (calc-select-props prev-id)))
                            :parent (let [selection (tree/selection db)
                                          parents (set (keep #(tree/parent-of db %) selection))]
                                      (when (= 1 (count parents))
                                        (calc-select-props (first parents))))
                            :all-siblings (when-let [current (tree/focus db)]
                                            (when-let [parent (tree/parent-of db current)]
                                              (let [all-siblings (tree/children db parent)]
                                                (calc-select-props all-siblings)))))]
                (when props
                  [{:op :update-node
                    :id const/session-selection-id
                    :props props}])))})

;; ── Legacy Intent Compatibility (forward to :selection) ──────────────────────

(intent/register-intent! :select
  {:doc "DEPRECATED: Use {:type :selection :mode :replace :ids ...} instead."
   :spec [:map [:type [:= :select]] [:ids [:or :string [:vector :string]]]]
   :handler (fn [db {:keys [ids]}]
              (intent/intent->ops db {:type :selection :mode :replace :ids ids}))})

(intent/register-intent! :extend-selection
  {:doc "DEPRECATED: Use {:type :selection :mode :extend :ids ...} instead."
   :spec [:map [:type [:= :extend-selection]] [:ids [:or :string [:vector :string]]]]
   :handler (fn [db {:keys [ids]}]
              (intent/intent->ops db {:type :selection :mode :extend :ids ids}))})

(intent/register-intent! :deselect
  {:doc "DEPRECATED: Use {:type :selection :mode :deselect :ids ...} instead."
   :spec [:map [:type [:= :deselect]] [:ids [:or :string [:vector :string]]]]
   :handler (fn [db {:keys [ids]}]
              (intent/intent->ops db {:type :selection :mode :deselect :ids ids}))})

(intent/register-intent! :clear-selection
  {:doc "DEPRECATED: Use {:type :selection :mode :clear} instead."
   :spec [:map [:type [:= :clear-selection]]]
   :handler (fn [db _]
              (intent/intent->ops db {:type :selection :mode :clear}))})

(intent/register-intent! :toggle-selection
  {:doc "DEPRECATED: Use {:type :selection :mode :toggle :ids id} instead."
   :spec [:map [:type [:= :toggle-selection]] [:id :string]]
   :handler (fn [db {:keys [id]}]
              (intent/intent->ops db {:type :selection :mode :toggle :ids id}))})

(intent/register-intent! :select-next-sibling
  {:doc "DEPRECATED: Use {:type :selection :mode :next} instead."
   :spec [:map [:type [:= :select-next-sibling]]]
   :handler (fn [db _]
              (intent/intent->ops db {:type :selection :mode :next}))})

(intent/register-intent! :select-prev-sibling
  {:doc "DEPRECATED: Use {:type :selection :mode :prev} instead."
   :spec [:map [:type [:= :select-prev-sibling]]]
   :handler (fn [db _]
              (intent/intent->ops db {:type :selection :mode :prev}))})

(intent/register-intent! :extend-to-next-sibling
  {:doc "DEPRECATED: Use :navigate-down or custom logic instead."
   :spec [:map [:type [:= :extend-to-next-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [next-id (tree/next-sibling db current)]
                  (intent/intent->ops db {:type :selection :mode :extend :ids next-id}))))})

(intent/register-intent! :extend-to-prev-sibling
  {:doc "DEPRECATED: Use :navigate-up or custom logic instead."
   :spec [:map [:type [:= :extend-to-prev-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [prev-id (tree/prev-sibling db current)]
                  (intent/intent->ops db {:type :selection :mode :extend :ids prev-id}))))})

(intent/register-intent! :select-parent
  {:doc "DEPRECATED: Use {:type :selection :mode :parent} instead."
   :spec [:map [:type [:= :select-parent]]]
   :handler (fn [db _]
              (intent/intent->ops db {:type :selection :mode :parent}))})

(intent/register-intent! :select-all-siblings
  {:doc "DEPRECATED: Use {:type :selection :mode :all-siblings} instead."
   :spec [:map [:type [:= :select-all-siblings]]]
   :handler (fn [db _]
              (intent/intent->ops db {:type :selection :mode :all-siblings}))})

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
