(ns plugins.struct
  "Structural-edit and movement intent compiler → core ops.

   Lowers high-level structural editing intents (delete, indent, outdent, move, etc.)
   into the closed instruction set of three core operations:
   - :create-node
   - :place
   - :update-node

   Design principle: Delete is archive by design - nodes are moved to :trash,
   never destroyed. This maintains referential integrity and enables undo.

   Includes movement/reordering logic (merged from plugins.permute)."
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q]
            [kernel.position :as pos]
            [kernel.db :as db]))


;; ── Intent compilers ──────────────────────────────────────────────────────────

(defn delete-ops
  "Compiles a delete intent into a :place operation that moves the node to :trash."
  [_DB id]
  [{:op :place :id id :under const/root-trash :at :last}])

(defn indent-ops
  "Compiles an indent intent into a :place operation that moves the node
   under its previous sibling."
  [db id]
  (if-let [sib (q/prev-sibling db id)]
    [{:op :place :id id :under sib :at :last}]
    []))

(defn outdent-ops
  "Compiles an outdent intent into a :place operation that moves the node
   to be a sibling of its parent (under its grandparent, after its parent).
   Prevents outdenting if grandparent is a root container (already at top level)."
  [db id]
  (let [p (q/parent-of db id)
        gp (when p (q/parent-of db p))
        roots (set (:roots db const/roots))]
    ;; Can outdent if: has parent, has grandparent, grandparent is NOT a root
    (if (and p gp (not (contains? roots gp)))
      [{:op :place :id id :under gp :at {:after p}}]
      [])))

;; ── Intent → Operations (ADR-016) ────────────────────────────────────────────

(intent/register-intent! :delete
  {:doc "Delete node by moving to :trash."
   :spec [:map [:type [:= :delete]] [:id :string]]
   :handler (fn [db {:keys [id]}]
              (delete-ops db id))})

(intent/register-intent! :indent
  {:doc "Indent node under previous sibling."
   :spec [:map [:type [:= :indent]] [:id :string]]
   :handler (fn [db {:keys [id]}]
              (indent-ops db id))})

(intent/register-intent! :outdent
  {:doc "Outdent node to be sibling of parent."
   :spec [:map [:type [:= :outdent]] [:id :string]]
   :handler (fn [db {:keys [id]}]
              (outdent-ops db id))})

(intent/register-intent! :create-and-place
  {:doc "Create new block and place it under parent."
   :spec [:map [:type [:= :create-and-place]] [:id :string] [:parent :string] [:after {:optional true} :string]]
   :handler (fn [_db {:keys [id parent after]}]
              [{:op :create-node :id id :type :block :props {:text ""}}
               {:op :place :id id :under parent :at (if after {:after after} :last)}])})

(intent/register-intent! :create-and-enter-edit
  {:doc "Create new block after focus and immediately enter edit mode.
   This consolidates the two-step UI logic (create + setTimeout + enter-edit) into a single intent."
   :spec [:map [:type [:= :create-and-enter-edit]]]
   :handler (fn [db _]
              (let [focus-id (q/focus db)
                    parent (q/parent-of db focus-id)
                    new-id (str "block-" (random-uuid))]
                [{:op :create-node :id new-id :type :block :props {:text ""}}
                 {:op :place :id new-id :under parent :at {:after focus-id}}
                 {:op :update-node
                  :id const/session-ui-id
                  :props {:editing-block-id new-id}}]))})

;; ── Multi-select intents ──────────────────────────────────────────────────────

(defn- sort-by-doc-order
  "Sort node IDs by document order (pre-order traversal).
   Ensures operations are applied top-to-bottom, left-to-right."
  [db ids]
  (sort-by #(get-in db [:derived :pre %] ##Inf) ids))

(defn- active-targets
  "Return selected node IDs or the currently editing block (vector, doc-ordered)."
  [db]
  (let [selected (q/selection db)
        editing-id (q/editing-block-id db)
        targets (cond
                  (seq selected) selected
                  editing-id [editing-id]
                  :else [])]
    (vec (sort-by-doc-order db targets))))

(defn- apply-to-active-targets
  "Apply op-fn to each active target node, returning combined ops vector."
  [db op-fn]
  (->> (active-targets db)
       (mapcat #(op-fn db %))
       vec))

(defn- same-parent?
  "Check that all ids share the same parent."
  [db ids]
  (when (seq ids)
    (let [parent (q/parent-of db (first ids))]
      (and parent
           (every? #(= parent (q/parent-of db %)) (rest ids))
           parent))))

(defn- move-selected-up-ops
  [db]
  (let [targets (active-targets db)
        first-id (first targets)
        parent (same-parent? db targets)
        prev (when first-id (q/prev-sibling db first-id))
        before-prev (when prev (q/prev-sibling db prev))]
    (if (and parent prev)
      (intent/intent->ops db {:type :move
                              :selection targets
                              :parent parent
                              :anchor (if before-prev {:after before-prev} :first)})
      [])))

(defn- move-selected-down-ops
  [db]
  (let [targets (active-targets db)
        last-id (last targets)
        parent (same-parent? db targets)
        next (when last-id (get-in db [:derived :next-id-of last-id]))]
    (if (and parent next)
      (intent/intent->ops db {:type :move
                              :selection targets
                              :parent parent
                              :anchor {:after next}})
      [])))

(intent/register-intent! :delete-selected
  {:doc "Delete all selected nodes (or editing block if no selection)."
   :spec [:map [:type [:= :delete-selected]]]
   :handler (fn [db _]
              (apply-to-active-targets db delete-ops))})

(intent/register-intent! :indent-selected
  {:doc "Indent all selected nodes (or editing block if no selection)."
   :spec [:map [:type [:= :indent-selected]]]
   :handler (fn [db _]
              (apply-to-active-targets db indent-ops))})

(intent/register-intent! :outdent-selected
  {:doc "Outdent all selected nodes (or editing block if no selection)."
   :spec [:map [:type [:= :outdent-selected]]]
   :handler (fn [db _]
              (apply-to-active-targets db outdent-ops))})

(intent/register-intent! :move-selected-up
  {:doc "Move selected nodes up one sibling position."
   :spec [:map [:type [:= :move-selected-up]]]
   :handler (fn [db _]
              (move-selected-up-ops db))})

(intent/register-intent! :move-selected-down
  {:doc "Move selected nodes down one sibling position."
   :spec [:map [:type [:= :move-selected-down]]]
   :handler (fn [db _]
              (move-selected-down-ops db))})

;; ── Movement/Reordering (merged from plugins.permute) ────────────────────────

(defn planned-positions
  "Compute target sibling vector after applying selection at the given anchor.

   Args:
     db - database
     selection - vector of node IDs to move (preserves order)
     parent - target parent ID
     anchor - position anchor (from kernel.anchor)

   Returns:
     Vector representing the final sibling order after move.

   Algorithm:
     1. Remove all selected nodes from parent's current children
     2. Resolve anchor position in the remaining siblings
     3. Insert selection at that position (preserving internal order)"
  [db {:keys [selection parent anchor]}]
  (let [current-kids (pos/children db parent)
        selection-set (set selection)
        kids-without-selection (vec (remove selection-set current-kids))
        target-idx (try
                     (pos/resolve-anchor-in-vec kids-without-selection anchor)
                     (catch #?(:clj Exception :cljs js/Error) _
                       ;; If anchor references a selected node, it will fail after removal
                       ;; Fallback to end
                       (count kids-without-selection)))
        safe-idx (min target-idx (count kids-without-selection))
        [head tail] (split-at safe-idx kids-without-selection)]
    (vec (concat head selection tail))))


(defn lower-reorder
  "Lower a :move intent to a minimal sequence of :place operations.

   Intent schema:
   {:selection [id ...]      ; IDs to move/reorder (non-contiguous OK)
    :parent parent-id        ; target parent
    :anchor Anchor}          ; where selection lands

   Returns: vector of :place ops that achieve the reorder.

   Strategy: emit one :place per selected ID, in target order, using relative anchors.
   Each :place uses {:after prev-id} to build up the sequence incrementally."
  [db intent]
  (let [{:keys [selection parent anchor]} intent
        target-order (planned-positions db intent)

        ;; Build ops: place each selected node using {:after previous-in-target-order}
        ops (reduce (fn [ops-acc id]
                      (let [;; Find what comes before this ID in target order
                            idx-in-target (.indexOf target-order id)
                            prev-id (when (pos? idx-in-target)
                                      (nth target-order (dec idx-in-target)))]
                        (conj ops-acc
                              (if prev-id
                                {:op :place
                                 :id id
                                 :under parent
                                 :at {:after prev-id}}
                                ;; First in selection goes at the anchor
                                {:op :place
                                 :id id
                                 :under parent
                                 :at anchor}))))
                    []
                    selection)]
    ops))


(defn- find-missing-nodes
  "Return vector of selection IDs that don't exist in db."
  [db selection]
  (let [nodes (:nodes db)]
    (filterv #(not (contains? nodes %)) selection)))

(defn- would-create-cycle-any?
  "Check if moving any node in selection under parent would create a cycle.
   A cycle occurs when parent is a descendant of any selected node.
   Short-circuits on first cycle found."
  [db selection parent]
  (and (string? parent)  ; Keywords (roots) can't be descendants
       (some #(db/descendant-of? db % parent) selection)))

(defn validate-move-intent
  "Validate a move intent before lowering.

   Returns: nil if valid, or issue map if invalid.

   Checks:
   - Selection IDs exist
   - Parent exists
   - No cycles (none of selection are ancestors of parent)"
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
  "Main entry point: lower a move intent to ops.

   Returns:
   - {:ops [Op...]} if valid
   - {:issues [Issue...]} if invalid"
  [db intent]
  (if-let [issue (validate-move-intent db intent)]
    {:issues [issue]}
    {:ops (lower-reorder db intent)}))

(intent/register-intent! :move
  {:doc "Move selection to target parent at anchor position (handles both cross-parent and same-parent reordering)."
   :spec [:map [:type [:= :move]] [:selection [:vector :string]] [:parent :string] [:anchor [:or :keyword [:map [:after :string]]]]]
   :handler (fn [db intent]
              (:ops (lower-move db intent)))})
