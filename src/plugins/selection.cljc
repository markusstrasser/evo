(ns plugins.selection
  "Selection state management via session atom.

   READER GUIDE:
   ─────────────
   This is the unified selection reducer. One intent (:selection) with modes.
   Modes: :replace, :extend, :deselect, :toggle, :clear, :next, :prev, :parent, :all-siblings
   Selection stored in session atom {:selection {:nodes #{...} :focus id :anchor id}}

   Phase 6 Architecture:
   - Handler receives (db session intent)
   - Handler returns {:session-updates {:selection new-props}}
   - Caller (shell layer) applies session-updates to session atom

   ONE LAW: Selection changes are pure state transitions (current-state, mode, ids) → new-props."
  (:require [clojure.set :as set]
            [kernel.intent :as intent]
            [kernel.query :as q]))

;; Sentinel for DCE prevention - referenced by spec.runner

;; ── Pure Selection Property Calculators ──────────────────────────────────────

(defn- calc-select-props
  "Pure: calculate props for replacing selection with given IDs.
   Explicitly clears :direction to prevent stale extend-selection state."
  [ids]
  (let [ids-vec (if (coll? ids) (vec ids) [ids])
        ids-set (set ids-vec)
        new-focus (last ids-vec)]
    {:nodes ids-set :focus new-focus :anchor new-focus :direction nil}))

(defn- calc-extend-props
  "Pure: calculate props for extending selection with given IDs."
  [current-state ids]
  (let [ids-vec (if (coll? ids) (vec ids) [ids])
        new-focus (last ids-vec)
        anchor (:anchor current-state)
        new-nodes (set/union (:nodes current-state) (set ids-vec))]
    {:nodes new-nodes
     :focus new-focus
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
  "Pure: calculate props for clearing selection.

   Preserves focus so typing after Escape still works (Logseq parity).
   Clears anchor and direction since there's no active selection."
  [current-state]
  {:nodes #{} :focus (:focus current-state) :anchor nil :direction nil})

;; NOTE: get-dom-nav-fn removed - now inline calls with session param

(def ^:private container-types
  "Node types that are containers and should not be included in block selection."
  #{:doc :page})

(defn- is-selectable-block?
  "Check if a node is a selectable block (not a container like :doc or :page).

   Non-container types (:block, :p, :heading, etc.) are selectable.
   Keyword roots (:doc, :trash, etc.) are never selectable."
  [db node-id]
  (if (keyword? node-id)
    false ;; Keyword roots are never selectable
    (let [node-type (get-in db [:nodes node-id :type])]
      (not (contains? container-types node-type)))))

(defn- next-selectable-block
  "Get the next selectable block in DOM order, skipping containers.

   Continues navigation until finding a :block type node or reaching boundary.
   Respects page boundaries - won't navigate out of current page (Logseq parity)."
  [db session current-id direction]
  (loop [current current-id]
    (when-let [next-id (case direction
                         :next (q/visible-next-block db session current)
                         :prev (q/visible-prev-block db session current))]
      (if (and (is-selectable-block? db next-id)
               (q/same-page? db session current-id next-id))
        next-id
        ;; Keep searching only if still on same page
        (when (q/same-page? db session current-id next-id)
          (recur next-id))))))

(defn- get-first-last-visible-block
  "Get the first or last visible block in the current page/zoom.
   direction: :next (first) or :prev (last)

   Uses session for zoom-root and current-page (spec §3.4)."
  [db session direction]
  (let [root-id (or (q/zoom-root session)
                    (q/current-page session)
                    :doc)
        children (q/children db root-id)]
    (when (seq children)
      (case direction
        :next (first children)
        :prev (last children)))))

(defn- calc-start-fresh-selection
  "Start a fresh selection when no focus exists."
  [db session direction]
  (when-let [first-or-last (get-first-last-visible-block db session direction)]
    {:nodes #{first-or-last}
     :focus first-or-last
     :anchor first-or-last
     :direction direction}))

(defn- make-selection-props
  "Unified result constructor for selection state.
   All calculator functions return this canonical shape."
  [{:keys [nodes focus anchor direction]}]
  {:nodes nodes
   :focus focus
   :anchor anchor
   :direction direction})

(defn- calc-first-extend
  "Handle first Shift+Arrow: set direction and anchor."
  [{:keys [nodes focus]} direction next-block]
  (when next-block
    (make-selection-props
      {:nodes (conj nodes next-block)
       :focus next-block
       :anchor focus
       :direction direction})))

(defn- calc-contract-selection
  "Remove trailing block when moving opposite to current direction.

   reverse-nav-fn: Function to navigate opposite to current direction
                   (injected dependency for testability)."
  [reverse-nav-fn {:keys [nodes focus anchor direction]} contracting-direction next-block]
  (if (> (count nodes) 1)
    ;; Remove current focus, move toward anchor
    (let [new-nodes (disj nodes focus)
          new-focus (reverse-nav-fn focus)]
      (make-selection-props
        {:nodes new-nodes
         :focus (or new-focus anchor)
         :anchor anchor
         :direction direction}))
    ;; Only anchor remains → flip direction and start extending
    (when next-block
      (make-selection-props
        {:nodes #{anchor next-block}
         :focus next-block
         :anchor anchor
         :direction contracting-direction}))))

(defn- calc-expand-selection
  "Add next block when extending in same direction."
  [{:keys [nodes anchor direction]} next-block]
  (when next-block
    (make-selection-props
      {:nodes (conj nodes next-block)
       :focus next-block
       :anchor anchor
       :direction direction})))

(defn- calc-extend-navigate-props
  "Calculate props for incremental selection extension.

   Incremental extension (Logseq parity):
   - First Shift+Arrow: set anchor and direction
   - Same direction: add next visible block (expand selection)
   - Opposite direction: remove trailing block (shrink selection)
   - When only one block remains, flip direction and start extending other way"
  [db session {:keys [focus direction] :as state} nav-direction]
  (let [contracting? (and direction (not= direction nav-direction))
        next-block (when focus
                     (next-selectable-block db session focus nav-direction))
        ;; Extract reverse-navigation logic for dependency injection
        reverse-nav (fn [id]
                      (case direction
                        :next (q/visible-prev-block db session id)
                        :prev (q/visible-next-block db session id)))]
    (cond
      ;; No focus → start fresh selection
      (nil? focus)
      (calc-start-fresh-selection db session nav-direction)

      ;; No direction yet → first Shift+Arrow
      (nil? direction)
      (calc-first-extend state nav-direction next-block)

      ;; Contracting (opposite direction) → remove trailing block
      contracting?
      (calc-contract-selection reverse-nav state nav-direction next-block)

      ;; Extending (same direction) → add next block
      :else
      (calc-expand-selection state next-block))))

(defn- calc-simple-navigate-props
  "Calculate props for plain arrow navigation (no extension).

   Falls back to dom-adjacent-id for cross-page navigation in journals view.
   When no focus exists, selects first/last visible block."
  [db session {:keys [focus]} direction dom-adjacent-id]
  (if-let [current focus]
    (when-let [target-id (or (next-selectable-block db session current direction)
                             dom-adjacent-id)]
      (calc-select-props target-id))
    (when-let [first-or-last (get-first-last-visible-block db session direction)]
      (calc-select-props first-or-last))))

(defn- calc-navigate-props
  "Pure: calculate props for navigating in a direction with incremental selection.
   direction: :next or :prev
   extend?: if true, extends selection incrementally; if false, replaces selection
   dom-adjacent-id: optional fallback for cross-page navigation (journals view)

   Incremental extension (Logseq parity):
   - First Shift+Arrow: set anchor and direction
   - Same direction: add next visible block (expand selection)
   - Opposite direction: remove trailing block (shrink selection)
   - When only one block remains, flip direction and start extending other way

   Non-extend mode: When no block is focused, select first/last visible block."
  [db session state direction extend? & [{:keys [dom-adjacent-id]}]]
  (if extend?
    (calc-extend-navigate-props db session state direction)
    (calc-simple-navigate-props db session state direction dom-adjacent-id)))

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

                          ;; FR Citations (Spec-as-Database pattern)
                          :fr/ids #{:fr.selection/edit-view-exclusive
                                    :fr.selection/extend-boundary
                                    :fr.nav/view-arrows
                                    :fr.nav/idle-first-last}

                          ;; Mode-conditional validation
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
                                           [:ids :string]]]
                                 [:clear [:map
                                          [:type [:= :selection]]
                                          [:mode [:= :clear]]]]
                                 [:next [:map
                                         [:type [:= :selection]]
                                         [:mode [:= :next]]
                                         [:dom-adjacent-id {:optional true} [:maybe :string]]]]
                                 [:prev [:map
                                         [:type [:= :selection]]
                                         [:mode [:= :prev]]
                                         [:dom-adjacent-id {:optional true} [:maybe :string]]]]
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

                          :handler (fn [db session {:keys [mode ids dom-adjacent-id]}]
                                     ;; Get current selection state from session
                                     (let [state (q/selection-state session)
                                           ;; Options for calc-navigate-props (cross-page fallback)
                                           nav-opts {:dom-adjacent-id dom-adjacent-id}

                                           ;; Calculate new selection props based on mode
                                           props (case mode
                                                   :replace (calc-select-props ids)
                                                   :extend (calc-extend-props state ids)
                                                   :deselect (calc-deselect-props state ids)
                                                   :toggle (let [id ids
                                                                 selected? (contains? (:nodes state) id)]
                                                             (if selected?
                                                               (calc-deselect-props state id)
                                                               (calc-extend-props state id)))
                                                   :clear (calc-clear-props state)
                                                   :next (calc-navigate-props db session state :next false nav-opts)
                                                   :prev (calc-navigate-props db session state :prev false nav-opts)
                                                   :extend-next (calc-navigate-props db session state :next true nav-opts)
                                                   :extend-prev (calc-navigate-props db session state :prev true nav-opts)
                                                   :parent (let [selection (:nodes state)
                                                                 parents (set (keep #(q/parent-of db %) selection))]
                                                             (when (and (= 1 (count parents))
                                                                        (is-selectable-block? db (first parents)))
                                                               (calc-select-props (first parents))))
                                                   :all-siblings (when-let [current (:focus state)]
                                                                   (when-let [parent (q/parent-of db current)]
                                                                     (calc-select-props (q/children db parent))))
                                                   :all-in-view (let [root-id (or (q/zoom-root session)
                                                                                  (q/current-page session)
                                                                                  :doc)
                                                                      all-blocks (->> (tree-seq
                                                                                       (fn [id] (seq (q/children db id)))
                                                                                       (fn [id] (q/children db id))
                                                                                       root-id)
                                                                                      (filter #(= :block (get-in db [:nodes % :type]))))]
                                                                  (when (seq all-blocks)
                                                                    (calc-select-props all-blocks))))]

                                       ;; Return session updates (selection state goes to session, not DB)
                                       (when props
                                         {:session-updates {:selection props}})))})

(intent/register-intent! :select-all-cycle
                         {:doc "Cmd+A cycle behavior (Logseq parity).

   LOGSEQ_SPEC §7.2: Cmd+A cycles through selection levels:
   1. First press (editing) → select all text (handled by browser, not this intent)
   2. Second press (all text selected) → exit edit, select the block
   3. Third press (block selected) → select parent
   4. Fourth press (parent/multiple selected) → select all visible

   This intent handles steps 2-4. Step 1 is handled in shell.global-keyboard
   by allowing the browser's default Cmd+A behavior.

   The :from-editing? flag indicates we're transitioning from editing with
   all text selected (step 2 → exit edit and select block)."
                          :fr/ids #{:fr.selection/cmd-a-cycle}
                          :spec [:map
                                 [:type [:= :select-all-cycle]]
                                 [:from-editing? {:optional true} :boolean]
                                 [:block-id {:optional true} :string]]

                          :handler (fn [db session {:keys [from-editing? block-id]}]
                                     (let [state (q/selection-state session)
                                           selection-nodes (:nodes state)
                                           _focus-id (:focus state) ; Extracted for future use in multi-step selection
                                           root-id (or (q/zoom-root session)
                                                       (q/current-page session)
                                                       :doc)]

                                       (cond
                                         ;; Step 2: From editing with all text selected → select the block
                                         from-editing?
                                         (when block-id
                                           {:session-updates
                                            {:selection (calc-select-props block-id)
                                             :ui {:editing-block-id nil}}})

                                         ;; Step 3: Single block selected → try parent, else all-in-view
                                         (= 1 (count selection-nodes))
                                         (let [current-id (first selection-nodes)
                                               parent-id (q/parent-of db current-id)]
                                           (if (and parent-id
                                                    (not (contains? #{:doc :page} parent-id))
                                                    (is-selectable-block? db parent-id))
                                             ;; Has selectable parent → select it
                                             {:session-updates {:selection (calc-select-props parent-id)}}
                                             ;; No parent or at root → select all visible
                                             (let [all-blocks (->> (tree-seq
                                                                    (fn [id] (seq (q/children db id)))
                                                                    (fn [id] (q/children db id))
                                                                    root-id)
                                                                   (filter #(is-selectable-block? db %)))]
                                               (when (seq all-blocks)
                                                 {:session-updates {:selection (calc-select-props all-blocks)}}))))

                                         ;; Step 4: Multiple blocks or parent selected → select all-in-view
                                         (seq selection-nodes)
                                         (let [all-blocks (->> (tree-seq
                                                                (fn [id] (seq (q/children db id)))
                                                                (fn [id] (q/children db id))
                                                                root-id)
                                                               (filter #(is-selectable-block? db %)))]
                                           (when (seq all-blocks)
                                             {:session-updates {:selection (calc-select-props all-blocks)}}))

                                         ;; Nothing selected → select first block
                                         :else
                                         (when-let [first-block (get-first-last-visible-block db session :next)]
                                           {:session-updates {:selection (calc-select-props first-block)}}))))})

;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
