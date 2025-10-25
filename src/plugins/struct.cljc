(ns plugins.struct
  "Structural-edit intent compiler → core ops.

   Lowers high-level structural editing intents (delete, indent, outdent, etc.)
   into the closed instruction set of three core operations:
   - :create-node
   - :place
   - :update-node

   Design principle: Delete is archive by design - nodes are moved to :trash,
   never destroyed. This maintains referential integrity and enables undo.

   Implements intent->ops multimethod from core.intent for structural intents."
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q]
            [plugins.selection :as selection]
            [plugins.editing :as editing]
            [plugins.permute :as permute])
  #?(:clj (:require [kernel.intent :refer [defintent]]))
  #?(:cljs (:require-macros [kernel.intent :refer [defintent]])))

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

(defintent :delete
  {:sig [db {:keys [id]}]
   :doc "Delete node by moving to :trash."
   :spec [:map [:type [:= :delete]] [:id :string]]
   :ops (delete-ops db id)})

(defintent :indent
  {:sig [db {:keys [id]}]
   :doc "Indent node under previous sibling."
   :spec [:map [:type [:= :indent]] [:id :string]]
   :ops (indent-ops db id)})

(defintent :outdent
  {:sig [db {:keys [id]}]
   :doc "Outdent node to be sibling of parent."
   :spec [:map [:type [:= :outdent]] [:id :string]]
   :ops (outdent-ops db id)})

(defintent :create-and-place
  {:sig [_db {:keys [id parent after]}]
   :doc "Create new block and place it under parent."
   :spec [:map [:type [:= :create-and-place]] [:id :string] [:parent :string] [:after {:optional true} :string]]
   :ops [{:op :create-node :id id :type :block :props {:text ""}}
         {:op :place :id id :under parent :at (if after {:after after} :last)}]})


;; ── Reorder intents ───────────────────────────────────────────────────────────

(defintent :reorder/children
  {:sig [db {:keys [parent order]}]
   :doc "Reorder children to explicit target order."
   :spec [:map [:type [:= :reorder/children]] [:parent :string] [:order [:vector :string]]]
   :ops (intent/intent->ops db {:type :reorder
                                :selection (vec order)
                                :parent parent
                                :anchor :first})})

(defintent :reorder/move-blocks
  {:sig [db {:keys [parent ids after]}]
   :doc "Move contiguous selection after pivot."
   :spec [:map [:type [:= :reorder/move-blocks]] [:parent :string] [:ids [:vector :string]] [:after {:optional true} :string]]
   :ops (intent/intent->ops db {:type :reorder
                                :selection (vec ids)
                                :parent parent
                                :anchor (if after {:after after} :first)})})

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
      (intent/intent->ops db {:type :reorder
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
      (intent/intent->ops db {:type :reorder
                              :selection targets
                              :parent parent
                              :anchor {:after next}})
      [])))

(defintent :delete-selected
  {:sig [db _]
   :doc "Delete all selected nodes (or editing block if no selection)."
   :spec [:map [:type [:= :delete-selected]]]
   :ops (let [selected (q/selection db)
              editing-id (q/editing-block-id db)
              targets (if (seq selected) selected (if editing-id [editing-id] []))
              ordered (sort-by-doc-order db targets)]
          (vec (mapcat #(delete-ops db %) ordered)))})

(defintent :indent-selected
  {:sig [db _]
   :doc "Indent all selected nodes (or editing block if no selection)."
   :spec [:map [:type [:= :indent-selected]]]
   :ops (let [selected (q/selection db)
              editing-id (q/editing-block-id db)
              targets (if (seq selected) selected (if editing-id [editing-id] []))
              ordered (sort-by-doc-order db targets)]
          (vec (mapcat #(indent-ops db %) ordered)))})

(defintent :outdent-selected
  {:sig [db _]
   :doc "Outdent all selected nodes (or editing block if no selection)."
   :spec [:map [:type [:= :outdent-selected]]]
   :ops (let [selected (q/selection db)
              editing-id (q/editing-block-id db)
              targets (if (seq selected) selected (if editing-id [editing-id] []))
              ordered (sort-by-doc-order db targets)]
          (vec (mapcat #(outdent-ops db %) ordered)))})

(defintent :move-selected-up
  {:sig [db _]
   :doc "Move selected nodes up one sibling position."
   :spec [:map [:type [:= :move-selected-up]]]
   :ops (move-selected-up-ops db)})

(defintent :move-selected-down
  {:sig [db _]
   :doc "Move selected nodes down one sibling position."
   :spec [:map [:type [:= :move-selected-down]]]
   :ops (move-selected-down-ops db)})

