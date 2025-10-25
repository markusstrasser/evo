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
  (:require [core.permutation :as perm]
            [core.intent :as intent]
            [plugins.siblings-order :as so]
            [plugins.selection :as selection]
            [plugins.editing :as editing]
            [plugins.permute :as permute]))

;; ── Derived index accessors ──────────────────────────────────────────────────

(defn- parent-of
  "Returns the parent ID of the given node ID."
  [DB id]
  (get-in DB [:derived :parent-of id]))

(defn- prev-sibling
  "Returns the previous sibling ID of the given node ID."
  [DB id]
  (get-in DB [:derived :prev-id-of id]))

(defn- grandparent-of
  "Returns the grandparent ID of the given node ID."
  [DB id]
  (when-let [p (parent-of DB id)]
    (parent-of DB p)))

;; ── Intent compilers ──────────────────────────────────────────────────────────

(defn delete-ops
  "Compiles a delete intent into a :place operation that moves the node to :trash."
  [_DB id]
  [{:op :place :id id :under :trash :at :last}])

(defn indent-ops
  "Compiles an indent intent into a :place operation that moves the node
   under its previous sibling."
  [DB id]
  (if-let [sib (prev-sibling DB id)]
    [{:op :place :id id :under sib :at :last}]
    []))

(defn outdent-ops
  "Compiles an outdent intent into a :place operation that moves the node
   to be a sibling of its parent (under its grandparent, after its parent).
   Prevents outdenting if grandparent is a root container (already at top level)."
  [DB id]
  (let [p (parent-of DB id)
        gp (grandparent-of DB id)
        roots (:roots DB #{:doc :trash})]
    ;; Can outdent if: has parent, has grandparent, grandparent is NOT a root
    (if (and p gp (not (contains? roots gp)))
      [{:op :place :id id :under gp :at {:after p}}]
      [])))

;; ── Intent → Operations (ADR-016) ────────────────────────────────────────────

(defmethod intent/intent->ops :delete
  [DB {:keys [id]}]
  (delete-ops DB id))

(defmethod intent/intent->ops :indent
  [DB {:keys [id]}]
  (indent-ops DB id))

(defmethod intent/intent->ops :outdent
  [DB {:keys [id]}]
  (outdent-ops DB id))

(defmethod intent/intent->ops :create-and-place
  [_DB {:keys [id parent after]}]
  [{:op :create-node :id id :type :block :props {:text ""}}
   {:op :place :id id :under parent :at (if after {:after after} :last)}])

(defmethod intent/intent->ops :delete-block
  [DB {:keys [block-id]}]
  (delete-ops DB block-id))

;; ── Reorder intents ───────────────────────────────────────────────────────────

(defn- realize-permutation->places
  "Convert a permutation into a sequence of :place operations.

   Strategy: Emit places in destination order with stable {:after prev} anchors.
   This ensures children end up in the target order after all places are applied."
  [DB parent p]
  (let [src (vec (get-in DB [:children-by-parent parent] []))
        dst (perm/arrange src p)]
    (vec
     (map-indexed
      (fn [i id]
        {:op :place
         :id id
         :under parent
         :at (if (zero? i)
               :first
               {:after (nth dst (dec i))})})
      dst))))

(defmethod intent/intent->ops :reorder/children
  [DB {:keys [parent order]}]
  ;; Reorder children to an explicit target order - route through permute
  ;; Intent: {:type :reorder/children, :parent P, :order [id1 id2 ...]}
  (intent/intent->ops DB {:type :reorder
                          :selection (vec order)
                          :parent parent
                          :anchor :first}))

(defmethod intent/intent->ops :reorder/move-blocks
  [DB {:keys [parent ids after]}]
  ;; Move contiguous selection after pivot - route through permute
  ;; Intent: {:type :reorder/move-blocks, :parent P, :ids [...], :after pivot}
  (intent/intent->ops DB {:type :reorder
                          :selection (vec ids)
                          :parent parent
                          :anchor (if after {:after after} :first)}))

;; ── Multi-select intents ──────────────────────────────────────────────────────

(defn- sort-by-doc-order
  "Sort node IDs by document order (pre-order traversal).
   Ensures operations are applied top-to-bottom, left-to-right."
  [DB ids]
  (sort-by #(get-in DB [:derived :pre %] ##Inf) ids))

(defn- active-targets
  "Return selected node IDs or the currently editing block (vector, doc-ordered)."
  [DB]
  (let [selected (selection/get-selected-nodes DB)
        editing-id (editing/editing-block-id DB)
        targets (cond
                  (seq selected) selected
                  editing-id [editing-id]
                  :else [])]
    (vec (sort-by-doc-order DB targets))))

(defn- same-parent?
  "Check that all ids share the same parent."
  [DB ids]
  (when (seq ids)
    (let [parent (parent-of DB (first ids))]
      (and parent
           (every? #(= parent (parent-of DB %)) (rest ids))
           parent))))

(defn- move-selected-up-ops
  [DB]
  (let [targets (active-targets DB)
        first-id (first targets)
        parent (same-parent? DB targets)
        prev (when first-id (prev-sibling DB first-id))
        before-prev (when prev (prev-sibling DB prev))]
    (if (and parent prev)
      (intent/intent->ops DB {:type :reorder
                              :selection targets
                              :parent parent
                              :anchor (if before-prev {:after before-prev} :first)})
      [])))

(defn- move-selected-down-ops
  [DB]
  (let [targets (active-targets DB)
        last-id (last targets)
        parent (same-parent? DB targets)
        next (when last-id (get-in DB [:derived :next-id-of last-id]))]
    (if (and parent next)
      (intent/intent->ops DB {:type :reorder
                              :selection targets
                              :parent parent
                              :anchor {:after next}})
      [])))

(defmethod intent/intent->ops :delete-selected
  [DB _]
  ;; Delete all currently selected nodes in document order
  ;; If nothing selected, delete the currently editing block
  (let [selected (selection/get-selected-nodes DB)
        editing-id (editing/editing-block-id DB)
        targets (if (seq selected) selected (if editing-id [editing-id] []))
        ordered (sort-by-doc-order DB targets)]
    (vec (mapcat #(delete-ops DB %) ordered))))

(defmethod intent/intent->ops :indent-selected
  [DB _]
  ;; Indent all currently selected nodes in document order
  ;; If nothing selected, indent the currently editing block
  ;; Processing top-to-bottom ensures correct parent-child relationships
  (let [selected (selection/get-selected-nodes DB)
        editing-id (editing/editing-block-id DB)
        targets (if (seq selected) selected (if editing-id [editing-id] []))
        ordered (sort-by-doc-order DB targets)]
    (vec (mapcat #(indent-ops DB %) ordered))))

(defmethod intent/intent->ops :outdent-selected
  [DB _]
  ;; Outdent all currently selected nodes in document order
  ;; If nothing selected, outdent the currently editing block
  ;; Processing top-to-bottom ensures correct parent-child relationships
  (let [selected (selection/get-selected-nodes DB)
        editing-id (editing/editing-block-id DB)
        targets (if (seq selected) selected (if editing-id [editing-id] []))
        ordered (sort-by-doc-order DB targets)]
    (vec (mapcat #(outdent-ops DB %) ordered))))

(defmethod intent/intent->ops :move-selected-up
  [DB _]
  (move-selected-up-ops DB))

(defmethod intent/intent->ops :move-selected-down
  [DB _]
  (move-selected-down-ops DB))
