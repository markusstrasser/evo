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
  (let [ids-vec     (if (coll? ids) (vec ids) [ids])
        new-focus   (last ids-vec)
        anchor      (:anchor current-state)
        single-id?  (= 1 (count ids-vec))
        range-mode? (and anchor single-id?)
        range-set   (when range-mode? (tree/doc-range db anchor new-focus))
        new-nodes   (or range-set (set/union (:nodes current-state) (set ids-vec)))]
    {:nodes  new-nodes
     :focus  new-focus
     :anchor (or anchor new-focus)}))

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

(defn- get-sibling-fn
  "Return the sibling function for the given direction."
  [direction]
  (case direction
    :next tree/next-sibling
    :prev tree/prev-sibling))

(defn- calc-navigate-props
  "Pure: calculate props for navigating in a direction.
   direction: :next or :prev
   extend?: if true, extends selection; if false, replaces selection"
  [db state direction extend?]
  (when-let [current (tree/focus db)]
    (when-let [sibling-id ((get-sibling-fn direction) db current)]
      (if extend?
        (calc-extend-props db state sibling-id)
        (calc-select-props sibling-id)))))

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
   - :extend-next - Extend selection to include next sibling
   - :extend-prev - Extend selection to include previous sibling
   - :parent    - Select parent of selection (if unique)
   - :all-siblings - Select all siblings of focus
   - :all-in-view - Select all blocks in current page/zoom level

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
          [:extend-next [:map
                         [:type [:= :selection]]
                         [:mode [:= :extend-next]]]]
          [:extend-prev [:map
                         [:type [:= :selection]]
                         [:mode [:= :extend-prev]]]]
          [:parent [:map
                    [:type [:= :selection]]
                    [:mode [:= :parent]]]]
          [:all-siblings [:map
                          [:type [:= :selection]]
                          [:mode [:= :all-siblings]]]]
          [:all-in-view [:map
                         [:type [:= :selection]]
                         [:mode [:= :all-in-view]]]]]
   :handler (fn [db {:keys [mode ids]}]
              (let [is-editing? (tree/editing? db)
                    state (get-selection-state db)
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
                            :next (calc-navigate-props db state :next false)
                            :prev (calc-navigate-props db state :prev false)
                            :extend-next (calc-navigate-props db state :next true)
                            :extend-prev (calc-navigate-props db state :prev true)
                            :parent (let [selection (tree/selection db)
                                          parents (set (keep #(tree/parent-of db %) selection))]
                                      (when (= 1 (count parents))
                                        (calc-select-props (first parents))))
                            :all-siblings (when-let [current (tree/focus db)]
                                            (when-let [parent (tree/parent-of db current)]
                                              (let [all-siblings (tree/children db parent)]
                                                (calc-select-props all-siblings))))
                            :all-in-view (let [root-id (or (get-in db [:nodes const/session-ui-id :props :zoom-id])
                                                          (get-in db [:nodes const/session-ui-id :props :current-page]))
                                              all-blocks (when root-id
                                                          (->> (tree-seq
                                                                (fn [id] (seq (tree/children db id)))
                                                                (fn [id] (tree/children db id))
                                                                root-id)
                                                              (filter #(= :block (get-in db [:nodes % :type])))))]
                                          (when (seq all-blocks)
                                            (calc-select-props all-blocks))))]
                (when props
                  (cond-> []
                    ;; INVARIANT: Exit edit mode before applying selection
                    is-editing? (conj {:op :update-node
                                       :id const/session-ui-id
                                       :props {:editing-block-id nil}})
                    ;; Then apply the selection change
                    true (conj {:op :update-node
                                :id const/session-selection-id
                                :props props})))))})
