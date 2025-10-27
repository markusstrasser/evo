(ns plugins.selection
  "Selection state management via session nodes.

   READER GUIDE:
   ─────────────
   This is the unified selection reducer. One intent (:selection) with modes.
   Modes: :replace, :extend, :deselect, :toggle, :clear, :next, :prev, :parent, :all-siblings
   Selection stored in session/selection node {:nodes #{...} :focus id :anchor id}
   All changes emit :update-node ops → enables undo/redo of selection.

   ONE LAW: Selection changes are pure state transitions (current-state, mode, ids) → new-props.

   Special behavior: :extend with single ID triggers range selection (doc-range from anchor to new-focus)"
  (:require [clojure.set :as set]
            [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as tree]))

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
   - :replace   - Replace selection with given IDs (last becomes focus) [requires :ids]
   - :extend    - Add IDs to selection (supports range if anchor exists) [requires :ids]
   - :deselect  - Remove IDs from selection [requires :ids]
   - :toggle    - Toggle ID (add if not selected, remove if selected) [requires :ids]
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
   ;; Mode-conditional validation: :ids required for certain modes
   :spec [:multi {:dispatch :mode}
          [:replace [:map
                     [:type [:= :selection]]
                     [:mode [:= :replace]]
                     [:ids [:or :string [:vector :string]]]]]
          [:extend [:map
                    [:type [:= :selection]]
                    [:mode [:= :extend]]
                    [:ids [:or :string [:vector :string]]]]]
          [:deselect [:map
                      [:type [:= :selection]]
                      [:mode [:= :deselect]]
                      [:ids [:or :string [:vector :string]]]]]
          [:toggle [:map
                    [:type [:= :selection]]
                    [:mode [:= :toggle]]
                    [:ids :string]]]  ;; toggle expects single ID
          [:clear [:map
                   [:type [:= :selection]]
                   [:mode [:= :clear]]]]
          [:next [:map
                  [:type [:= :selection]]
                  [:mode [:= :next]]]]
          [:prev [:map
                  [:type [:= :selection]]
                  [:mode [:= :prev]]]]
          [:parent [:map
                    [:type [:= :selection]]
                    [:mode [:= :parent]]]]
          [:all-siblings [:map
                          [:type [:= :selection]]
                          [:mode [:= :all-siblings]]]]]
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

;; ── Helper Intents (for keybindings & backward compat) ────────────────────────

(intent/register-intent! :select
  {:doc "Select blocks by IDs. Wrapper for unified :selection intent with :mode :replace."
   :spec [:map
          [:type [:= :select]]
          [:ids [:or :string [:vector :string]]]]
   :handler (fn [_db {:keys [ids]}]
              (let [ids-vec (if (coll? ids) (vec ids) [ids])
                    ids-set (set ids-vec)
                    new-focus (last ids-vec)]
                [{:op :update-node
                  :id const/session-selection-id
                  :props {:nodes ids-set :focus new-focus :anchor new-focus}}]))})

(intent/register-intent! :extend-selection
  {:doc "Extend selection to include given IDs. Wrapper for unified :selection intent."
   :spec [:map
          [:type [:= :extend-selection]]
          [:ids [:or :string [:vector :string]]]]
   :handler (fn [db {:keys [ids]}]
              (let [state (get-selection-state db)]
                [{:op :update-node
                  :id const/session-selection-id
                  :props (calc-extend-props db state ids)}]))})

(intent/register-intent! :select-next-sibling
  {:doc "Select next sibling of focused node. Wrapper for unified :selection intent."
   :spec [:map [:type [:= :select-next-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [next-id (tree/next-sibling db current)]
                  [{:op :update-node
                    :id const/session-selection-id
                    :props (calc-select-props next-id)}])))})

(intent/register-intent! :select-prev-sibling
  {:doc "Select previous sibling of focused node. Wrapper for unified :selection intent."
   :spec [:map [:type [:= :select-prev-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [prev-id (tree/prev-sibling db current)]
                  [{:op :update-node
                    :id const/session-selection-id
                    :props (calc-select-props prev-id)}])))})

(intent/register-intent! :extend-to-next-sibling
  {:doc "Extend selection to include next sibling of focused node."
   :spec [:map [:type [:= :extend-to-next-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [next-id (tree/next-sibling db current)]
                  (let [state (get-selection-state db)]
                    [{:op :update-node
                      :id const/session-selection-id
                      :props (calc-extend-props db state next-id)}]))))})

(intent/register-intent! :extend-to-prev-sibling
  {:doc "Extend selection to include previous sibling of focused node."
   :spec [:map [:type [:= :extend-to-prev-sibling]]]
   :handler (fn [db _]
              (when-let [current (tree/focus db)]
                (when-let [prev-id (tree/prev-sibling db current)]
                  (let [state (get-selection-state db)]
                    [{:op :update-node
                      :id const/session-selection-id
                      :props (calc-extend-props db state prev-id)}]))))})

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
