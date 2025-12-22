(ns plugins.structural
  "Structural-edit and movement intent compiler → core ops.

   Includes:
   - Symmetric indent/outdent operations using shared validation
   - Extracted common tree-traversal patterns
   - DRY multi-select logic"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q]
            [kernel.position :as pos]
            [kernel.db :as db]))

;; ── Shared Validation & Tree Navigation ──────────────────────────────────────

(defn- within-zoom-scope?
  "Check if target-id is within the current zoom scope.
   FR-Scope-02: Prevents operations from moving blocks outside zoom root."
  [db session target-id]
  (if-let [zoom-root (q/zoom-root session)]
    (or (= target-id zoom-root)
        (db/descendant-of? db zoom-root target-id))
    true))

(defn- can-change-depth?
  "Check if block can move up/down in hierarchy.

   Returns map with:
   - :valid? - boolean
   - :parent - parent ID (if valid)
   - :grandparent - grandparent ID (if valid for outdent)
   - :reason - keyword explaining why invalid (if not valid)"
  [db session id direction]
  (let [p (q/parent-of db id)
        gp (when p (q/parent-of db p))
        roots (set (:roots db const/roots))
        parent-type (when p (get-in db [:nodes p :type]))]
    (case direction
      :indent
      (if-let [prev-sib (q/prev-sibling db id)]
        {:valid? true :parent p :target prev-sib}
        {:valid? false :reason :no-prev-sibling})

      :outdent
      (cond
        (not p)
        {:valid? false :reason :no-parent}

        (not gp)
        {:valid? false :reason :no-grandparent}

        (contains? roots p)
        {:valid? false :reason :parent-is-root}

        (= parent-type :page)
        {:valid? false :reason :parent-is-page}

        (not (within-zoom-scope? db session gp))
        {:valid? false :reason :grandparent-outside-zoom}

        :else
        {:valid? true :parent p :grandparent gp}))))

(defn- make-place-ops
  "Create place operations for nodes at a target location.

   Strategies:
   - :simple - All nodes use the same anchor (for indent - all to :last)
   - :chain - Chain nodes with {:after prev} (for outdent - sequential placement)"
  ([nodes parent anchor]
   (make-place-ops nodes parent anchor :chain))
  ([nodes parent anchor strategy]
   (case strategy
     :simple
     ;; All nodes placed at same anchor
     (mapv (fn [id] {:op :place :id id :under parent :at anchor}) nodes)

     :chain
     ;; Chain placement: first at anchor, rest after previous
     (loop [remaining nodes
            prev-anchor (if (map? anchor) (:after anchor) anchor)
            ops []]
       (if-let [id (first remaining)]
         (recur (rest remaining)
                id
                (conj ops {:op :place
                           :id id
                           :under parent
                           :at (if (keyword? prev-anchor)
                                 prev-anchor
                                 {:after prev-anchor})}))
         ops)))))

;; ── Core Indent/Outdent Operations ───────────────────────────────────────────

(defn delete-ops
  "Move block to :trash (children come along automatically).
   Signature: [db session id] - uniform for apply-to-active-targets."
  [db _session id]
  [{:op :place :id id :under const/root-trash :at :last}])

(defn indent-ops
  "Indent block under its previous sibling.
   Returns map with :ops and optional :unfold-target.
   Signature: [db session id] - uniform for apply-to-active-targets."
  [db session id]
  (let [{:keys [valid? target]} (can-change-depth? db session id :indent)]
    (if valid?
      (let [is-collapsed (q/folded? session target)]
        {:ops [{:op :place :id id :under target :at :last}]
         :unfold-target (when is-collapsed target)})
      {:ops []})))

(defn outdent-ops
  "Outdent block to sibling of parent (logical outdenting).
   Places block immediately after its parent.
   Signature: [db session id] - uniform for apply-to-active-targets."
  [db session id]
  (let [{:keys [valid? parent grandparent]} (can-change-depth? db session id :outdent)]
    (if valid?
      [{:op :place :id id :under grandparent :at {:after parent}}]
      [])))

;; ── Multi-Select Operations ──────────────────────────────────────────────────

(defn- indent-multi-ops
  "Indent multiple selected blocks under first block's previous sibling.
   All blocks become siblings at the same level under the new parent."
  [db session targets]
  (when (seq targets)
    (let [first-id (first targets)
          {:keys [valid? target]} (can-change-depth? db session first-id :indent)]
      (if valid?
        (let [is-collapsed (q/folded? session target)]
          {:ops (make-place-ops targets target :last :simple)
           :unfold-target (when is-collapsed target)})
        {:ops [] :unfold-target nil}))))

(defn- outdent-multi-ops
  "Outdent multiple selected blocks to siblings of their parent.
   All blocks move to position after parent, maintaining relative order."
  [db session targets]
  (when (seq targets)
    (let [first-id (first targets)
          {:keys [valid? parent grandparent]} (can-change-depth? db session first-id :outdent)]
      (when valid?
        (make-place-ops targets grandparent {:after parent} :chain)))))

;; ── Selection & Filtering Utilities ──────────────────────────────────────────

(defn- sort-by-doc-order
  "Sort node IDs by pre-order traversal."
  [db ids]
  (sort-by #(get-in db [:derived :pre %] ##Inf) ids))

(defn- active-targets
  "Return selected node IDs or the currently editing block (vector, doc-ordered)."
  [db session]
  (let [selected (q/selection session)
        editing-id (q/editing-block-id session)]
    (-> (cond
          (seq selected) selected
          editing-id [editing-id]
          :else [])
        (->> (sort-by-doc-order db))
        vec)))

(defn- filter-top-level-targets
  "Filter to only 'top-level' blocks (remove children of selected parents).
   LOGSEQ PARITY: Moving parent also moves children."
  [db targets]
  (let [target-set (set targets)
        parent-ids-in-selection (set (keep #(when (target-set (q/parent-of db %))
                                               (q/parent-of db %))
                                            targets))]
    (vec (remove #(contains? parent-ids-in-selection (q/parent-of db %))
                 targets))))

(defn- same-parent?
  "Check that all ids share the same parent. Returns parent ID or nil."
  [db ids]
  (when (seq ids)
    (let [parent (q/parent-of db (first ids))]
      (when (and parent (every? #(= parent (q/parent-of db %)) (rest ids)))
        parent))))

(defn- consecutive-siblings?
  "Check if all ids are consecutive siblings (no gaps).
   LOGSEQ PARITY: Rejects non-consecutive selections."
  [db ids]
  (when-let [parent (same-parent? db ids)]
    (let [children (get-in db [:children-by-parent parent] [])
          id-set (set ids)
          indices (keep-indexed (fn [idx child] (when (id-set child) idx)) children)]
      ;; Either < 2 elements, or all indices consecutive
      (or (< (count indices) 2)
          (->> indices
               sort
               (partition 2 1)
               (every? (fn [[a b]] (= b (inc a)))))))))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- collect-right-siblings
  "Collect all right siblings of a node in document order."
  [db id]
  (->> id
       (iterate #(q/next-sibling db %))
       (drop 1)  ; Skip the node itself
       (take-while some?)
       vec))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- consolidate-to-consecutive
  "Fill gaps in non-consecutive selection to make it consecutive.
   LOGSEQ PARITY: Consolidates to full range from first to last selected sibling."
  [db ids]
  (when-let [parent (same-parent? db ids)]
    (let [children (get-in db [:children-by-parent parent] [])
          id-set (set ids)
          indices (keep-indexed (fn [idx child] (when (id-set child) idx)) children)
          [min-idx max-idx] ((juxt #(apply min %) #(apply max %)) indices)]
      (vec (subvec children min-idx (inc max-idx))))))

;; ── Movement/Climbing Operations ──────────────────────────────────────────────

(defn- can-climb-or-descend?
  "Check if selection can climb (move-up at boundary) or descend (move-down at boundary).

   Returns:
   - {:valid? true :new-parent ... :anchor ...} if valid
   - {:valid? false :reason ...} if invalid"
  [db session targets direction]
  (let [first-id (first targets)
        last-id (last targets)
        parent (same-parent? db targets)
        roots (set (:roots db const/roots))
        parent-type (when parent (get-in db [:nodes parent :type]))]

    (case direction
      :climb
      (let [prev (when first-id (q/prev-sibling db first-id))]
        (if prev
          {:valid? false :reason :has-prev-sibling}
          ;; No prev sibling - check if can climb
          (let [grandparent (when parent (q/parent-of db parent))]
            (cond
              (not grandparent)
              {:valid? false :reason :no-grandparent}

              (contains? roots parent)
              {:valid? false :reason :parent-is-root}

              (= parent-type :page)
              {:valid? false :reason :parent-is-page}

              (not (within-zoom-scope? db session grandparent))
              {:valid? false :reason :grandparent-outside-zoom}

              :else
              (let [parent-prev (q/prev-sibling db parent)]
                {:valid? true
                 :new-parent grandparent
                 :anchor (if parent-prev {:after parent-prev} :first)})))))

      :descend
      (let [next-sib (when last-id (get-in db [:derived :next-id-of last-id]))]
        (if next-sib
          {:valid? false :reason :has-next-sibling}
          ;; No next sibling - check if can descend
          (let [parent-next (when parent (q/next-sibling db parent))]
            (cond
              (not parent-next)
              {:valid? false :reason :no-parent-next}

              (= parent-type :page)
              {:valid? false :reason :parent-is-page}

              (not (within-zoom-scope? db session parent-next))
              {:valid? false :reason :target-outside-zoom}

              :else
              {:valid? true
               :new-parent parent-next
               :anchor :first})))))))

(defn- move-selected-ops
  "Unified helper for moving selected nodes up or down one sibling position.
   Implements 'climb out' (up) and 'descend into' (down) semantics at boundaries.

   Direction-specific behavior:
   - :up: Uses first target, checks prev sibling, may climb to grandparent
   - :down: Uses last target, checks next sibling, may descend into parent's next sibling"
  [db session direction]
  (let [raw-targets (active-targets db session)
        targets (filter-top-level-targets db raw-targets)
        parent (same-parent? db targets)
        consecutive? (consecutive-siblings? db targets)]

    (cond
      ;; Early exits: empty or non-consecutive
      (empty? targets)
      []

      (and parent (not consecutive?))
      []

      ;; Direction-specific logic
      :else
      (let [;; Select boundary ID and sibling based on direction
            boundary-id (case direction :up (first targets) :down (last targets))
            adjacent-sib (case direction
                           :up (when boundary-id (q/prev-sibling db boundary-id))
                           :down (when boundary-id (get-in db [:derived :next-id-of boundary-id])))]

        (cond
          ;; Normal case: has adjacent sibling to swap with
          (and parent adjacent-sib)
          (let [anchor (case direction
                         :up (if-let [before-prev (q/prev-sibling db adjacent-sib)]
                               {:after before-prev}
                               :first)
                         :down {:after adjacent-sib})]
            (intent/intent->ops db session
                                {:type :move
                                 :selection targets
                                 :parent parent
                                 :anchor anchor}))

          ;; Boundary case: no adjacent sibling, try climb/descend
          (and parent (not adjacent-sib) boundary-id)
          (let [boundary-direction (case direction :up :climb :down :descend)
                {:keys [valid? new-parent anchor]}
                (can-climb-or-descend? db session targets boundary-direction)]
            (if valid?
              (intent/intent->ops db session
                                  {:type :move
                                   :selection targets
                                   :parent new-parent
                                   :anchor anchor})
              []))

          :else [])))))

(defn- move-selected-up-ops
  "Move selected nodes up one sibling position.
   Implements 'climb out' semantics at boundaries."
  [db session]
  (move-selected-ops db session :up))

(defn- move-selected-down-ops
  "Move selected nodes down one sibling position.
   Implements 'descend into' semantics at boundaries."
  [db session]
  (move-selected-ops db session :down))

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(intent/register-intent! :delete
  {:doc "Delete node by moving to :trash. LOGSEQ PARITY: Focus moves to previous block."
   :fr/ids #{:fr.struct/delete-block}
   :allowed-states #{:editing}
   :spec [:map [:type [:= :delete]] [:id :string]]
   :handler (fn [db session {:keys [id]}]
              (let [prev-block (q/visible-prev-block db session id)
                    next-block (when-not prev-block (q/visible-next-block db session id))
                    new-focus (or prev-block next-block)
                    prev-text-len (when prev-block
                                    (count (get-in db [:nodes prev-block :props :text] "")))]
                {:ops (delete-ops db session id)
                 :session-updates
                 (if new-focus
                   {:selection {:nodes #{new-focus} :focus new-focus :anchor new-focus}
                    :ui {:editing-block-id new-focus
                         :cursor-position (if prev-block prev-text-len 0)}}
                   {:selection {:nodes #{} :focus nil :anchor nil}
                    :ui {:editing-block-id nil}})}))})

(intent/register-intent! :indent
  {:doc "Indent node under previous sibling. LOGSEQ PARITY: Expands collapsed target."
   :fr/ids #{:fr.struct/indent-outdent}
   :spec [:map [:type [:= :indent]] [:id :string]]
   :allowed-states #{:editing :selection}
   :handler (fn [db session {:keys [id]}]
              (let [{:keys [ops unfold-target]} (indent-ops db session id)
                    unfold-updates (when unfold-target
                                     {:ui {:folded (disj (q/folded-set session) unfold-target)}})]
                {:ops (or ops [])
                 :session-updates unfold-updates}))})

(intent/register-intent! :outdent
  {:doc "Outdent node to be sibling of parent."
   :fr/ids #{:fr.struct/indent-outdent}
   :spec [:map [:type [:= :outdent]] [:id :string]]
   :allowed-states #{:editing :selection}
   :handler (fn [db session {:keys [id]}]
              (outdent-ops db session id))})

(intent/register-intent! :create-and-place
  {:doc "Create new block and place it under parent."
   :fr/ids #{:fr.struct/create-sibling}
   :spec [:map [:type [:= :create-and-place]] [:id :string] [:parent :string]
          [:after {:optional true} :string]]
   :handler (fn [_db _session {:keys [id parent after]}]
              [{:op :create-node :id id :type :block :props {:text ""}}
               {:op :place :id id :under parent :at (if after {:after after} :last)}])})

(intent/register-intent! :create-and-enter-edit
  {:doc "Create new block after focus and immediately enter edit mode."
   :fr/ids #{:fr.struct/create-sibling}
   :spec [:map [:type [:= :create-and-enter-edit]]]
   :handler (fn [db _session _intent]
              (let [focus-id (q/focus db)
                    parent (q/parent-of db focus-id)
                    new-id (str "block-" (random-uuid))]
                {:ops [{:op :create-node :id new-id :type :block :props {:text ""}}
                       {:op :place :id new-id :under parent :at {:after focus-id}}]
                 :session-updates {:selection {:nodes #{} :focus nil :anchor nil}
                                   :ui {:editing-block-id new-id :cursor-position 0}}}))})

(intent/register-intent! :create-block-in-page
  {:doc "Create new block directly under a page (for empty pages)."
   :fr/ids #{:fr.struct/create-sibling}
   :spec [:map [:type [:= :create-block-in-page]]
          [:page-id [:or :string :keyword]]
          [:block-id :string]]
   :handler (fn [_db _session {:keys [page-id block-id]}]
              {:ops [{:op :create-node :id block-id :type :block :props {:text ""}}
                     {:op :place :id block-id :under page-id :at :first}]
               :session-updates {:selection {:nodes #{} :focus nil :anchor nil}
                                 :ui {:editing-block-id block-id :cursor-position 0}}})})

;; ── Multi-Select Intent Handlers ──────────────────────────────────────────────

(intent/register-intent! :delete-selected
  {:doc "Delete all selected nodes. LOGSEQ PARITY: Focus moves to previous visible block."
   :fr/ids #{:fr.struct/delete-block}
   :spec [:map [:type [:= :delete-selected]]]
   :handler (fn [db session _intent]
              (let [targets (active-targets db session)
                    first-target (first targets)
                    last-target (last targets)
                    prev-block (when first-target (q/visible-prev-block db session first-target))
                    next-block (when (and (not prev-block) last-target)
                                 (q/visible-next-block db session last-target))
                    target-set (set targets)
                    ;; Find next block that isn't being deleted
                    safe-next (when next-block
                                (loop [candidate next-block]
                                  (cond
                                    (nil? candidate) nil
                                    (not (contains? target-set candidate)) candidate
                                    :else (recur (q/visible-next-block db session candidate)))))
                    new-focus (or prev-block safe-next)]
                {:ops (vec (mapcat #(delete-ops db session %) targets))
                 :session-updates {:selection {:nodes (if new-focus #{new-focus} #{})
                                               :focus new-focus
                                               :anchor new-focus}
                                   :ui {:editing-block-id nil}}}))})

(intent/register-intent! :indent-selected
  {:doc "Indent all selected/editing nodes (Logseq parity).
         LOGSEQ PARITY: Parent+child filter to top-level, reject non-consecutive."
   :fr/ids #{:fr.struct/indent-outdent}
   :spec [:map [:type [:= :indent-selected]]]
   :handler (fn [db session _intent]
              (let [editing-id (q/editing-block-id session)
                    raw-targets (active-targets db session)
                    targets (filter-top-level-targets db raw-targets)]
                (if (and (> (count targets) 1)
                         (not (consecutive-siblings? db targets)))
                  ;; Non-consecutive: no-op
                  {:ops []
                   :session-updates (when editing-id {:ui {:editing-block-id editing-id}})}
                  ;; Valid: proceed
                  (let [result (if (= 1 (count targets))
                                 (indent-ops db session (first targets))
                                 (indent-multi-ops db session targets))
                        {:keys [ops unfold-target]} result
                        base-updates (when editing-id {:ui {:editing-block-id editing-id}})
                        unfold-updates (when unfold-target
                                         {:ui {:folded (disj (q/folded-set session) unfold-target)}})]
                    {:ops (or ops [])
                     :session-updates (merge base-updates unfold-updates)}))))})

(intent/register-intent! :outdent-selected
  {:doc "Outdent all selected/editing nodes (Logseq parity).
         LOGSEQ PARITY: Parent+child filter to top-level, reject non-consecutive."
   :fr/ids #{:fr.struct/indent-outdent}
   :spec [:map [:type [:= :outdent-selected]]]
   :handler (fn [db session _intent]
              (let [editing-id (q/editing-block-id session)
                    raw-targets (active-targets db session)
                    targets (filter-top-level-targets db raw-targets)]
                (if (and (> (count targets) 1)
                         (not (consecutive-siblings? db targets)))
                  ;; Non-consecutive: no-op
                  {:ops []
                   :session-updates (when editing-id {:ui {:editing-block-id editing-id}})}
                  ;; Valid: proceed
                  (let [ops (if (= 1 (count targets))
                              (outdent-ops db session (first targets))
                              (or (outdent-multi-ops db session targets) []))]
                    {:ops ops
                     :session-updates (when editing-id {:ui {:editing-block-id editing-id}})}))))})

(intent/register-intent! :move-selected-up
  {:doc "Move selected nodes up one sibling position."
   :fr/ids #{:fr.struct/climb-descend}
   :allowed-states #{:editing :selection}
   :spec [:map [:type [:= :move-selected-up]]]
   :handler (fn [db session _intent]
              (move-selected-up-ops db session))})

(intent/register-intent! :move-selected-down
  {:doc "Move selected nodes down one sibling position."
   :fr/ids #{:fr.struct/climb-descend}
   :allowed-states #{:editing :selection}
   :spec [:map [:type [:= :move-selected-down]]]
   :handler (fn [db session _intent]
              (move-selected-down-ops db session))})

;; ── Movement/Reordering (merged from plugins.permute) ────────────────────────

(defn planned-positions
  "Compute target sibling vector after applying selection at the given anchor."
  [db {:keys [selection parent anchor]}]
  (let [current-kids (pos/children db parent)
        selection-set (set selection)
        kids-without-selection (vec (remove selection-set current-kids))
        target-idx (try
                     (pos/resolve-insert-index kids-without-selection anchor)
                     (catch #?(:clj Exception :cljs js/Error) _
                       (count kids-without-selection)))
        safe-idx (min target-idx (count kids-without-selection))
        [head tail] (split-at safe-idx kids-without-selection)]
    (vec (concat head selection tail))))

(defn lower-reorder
  "Lower a :move intent to a minimal sequence of :place operations."
  [db intent]
  (let [{:keys [selection parent]} intent
        target-order (planned-positions db intent)]
    ;; Build ops using chained {:after prev}
    (loop [remaining selection
           ops []]
      (if-let [id (first remaining)]
        (let [idx-in-target (.indexOf target-order id)
              prev-id (when (pos? idx-in-target) (nth target-order (dec idx-in-target)))
              anchor (if prev-id {:after prev-id} (:anchor intent))]
          (recur (rest remaining)
                 (conj ops {:op :place :id id :under parent :at anchor})))
        ops))))

(defn- find-missing-nodes
  "Return vector of selection IDs that don't exist in db."
  [db selection]
  (let [nodes (:nodes db)]
    (filterv #(not (contains? nodes %)) selection)))

(defn- would-create-cycle-any?
  "Check if moving any node in selection under parent would create a cycle."
  [db selection parent]
  (and (string? parent)
       (some #(db/descendant-of? db % parent) selection)))

(defn validate-move-intent
  "Validate a move intent. Returns nil if valid, issue map if invalid."
  [db {:keys [selection parent] :as intent}]
  (let [missing (find-missing-nodes db selection)]
    (cond
      (seq missing)
      {:reason ::node-not-found
       :hint "One or more selected nodes don't exist"
       :missing missing
       :intent intent}

      (not (db/valid-parent? db parent))
      {:reason ::parent-not-found
       :hint (str "Parent " parent " doesn't exist")
       :parent parent
       :intent intent}

      (would-create-cycle-any? db selection parent)
      {:reason ::would-create-cycle
       :hint "Cannot move node into its own descendant"
       :intent intent}

      :else nil)))

(defn lower-move
  "Lower a move intent to ops. Returns {:ops [...]} or {:issues [...]}."
  [db intent]
  (if-let [issue (validate-move-intent db intent)]
    {:issues [issue]}
    {:ops (lower-reorder db intent)}))

(intent/register-intent! :move
  {:doc "Move selection to target parent at anchor position."
   :spec [:map
          [:type [:= :move]]
          [:selection [:vector :string]]
          [:parent [:or :string :keyword]]
          [:anchor [:or :keyword
                    [:map [:after :string]]
                    [:map [:before :string]]]]]
   :fr/ids #{:fr.struct/climb-descend}
   :handler (fn [db _session intent]
              (:ops (lower-move db intent)))})

;; ── Move While Editing ────────────────────────────────────────────────────────

(intent/register-intent! :move-block-up-while-editing
  {:doc "Move current editing block up, preserving edit mode."
   :spec [:map [:type [:= :move-block-up-while-editing]] [:block-id :string]]
   :fr/ids #{:fr.struct/climb-descend}
   :handler (fn [db session {:keys [_block-id]}]
              (move-selected-up-ops db session))})

(intent/register-intent! :move-block-down-while-editing
  {:doc "Move current editing block down, preserving edit mode."
   :spec [:map [:type [:= :move-block-down-while-editing]] [:block-id :string]]
   :fr/ids #{:fr.struct/climb-descend}
   :handler (fn [db session {:keys [_block-id]}]
              (move-selected-down-ops db session))})

;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
