(ns plugins.struct.core
  "Structural-edit intent compiler → core ops.

   Lowers high-level structural editing intents (delete, indent, outdent, etc.)
   into the closed instruction set of three core operations:
   - :create-node
   - :place
   - :update-node

   Design principle: Delete is archive by design - nodes are moved to :trash,
   never destroyed. This maintains referential integrity and enables undo."
  (:require [algebra.permutation :as perm]
            [plugins.siblings-order :as so]
            [plugins.selection.core :as selection]))

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
   Prevents outdenting if grandparent is a root container (:doc, :trash)."
  [DB id]
  (let [p  (parent-of DB id)
        gp (grandparent-of DB id)
        roots (:roots DB #{:doc :trash})]
    (if (and p gp (not (contains? roots gp)))
      [{:op :place :id id :under gp :at {:after p}}]
      [])))

;; ── Multimethod dispatch ──────────────────────────────────────────────────────

(defmulti compile-intent
  "Compiles a single high-level intent into a vector of core operations.
   Dispatch on the :type key of the intent map."
  (fn [_DB intent] (:type intent)))

(defmethod compile-intent :delete
  [DB {:keys [id]}]
  (delete-ops DB id))

(defmethod compile-intent :indent
  [DB {:keys [id]}]
  (indent-ops DB id))

(defmethod compile-intent :outdent
  [DB {:keys [id]}]
  (outdent-ops DB id))

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

(defmethod compile-intent :reorder/children
  [DB {:keys [parent order]}]
  ;; Reorder children to an explicit target order.
  ;; Intent: {:type :reorder/children, :parent P, :order [id1 id2 ...]}
  (let [p (so/target-permutation DB parent (vec order))]
    (realize-permutation->places DB parent p)))

(defn- splice-after
  "Remove ids from items and re-insert after pivot (or at start if pivot is nil)."
  [items ids after]
  (let [items' (vec (remove (set ids) items))
        i      (if after (inc (.indexOf items' after)) 0)]
    (vec (concat (subvec items' 0 i) ids (subvec items' i)))))

(defmethod compile-intent :reorder/move-blocks
  [DB {:keys [parent ids after]}]
  ;; Move contiguous selection after pivot.
  ;; Intent: {:type :reorder/move-blocks, :parent P, :ids [...], :after pivot}
  (let [src (vec (get-in DB [:children-by-parent parent] []))
        dst (splice-after src ids after)
        p   (perm/from-to src dst)]
    (realize-permutation->places DB parent p)))

(defmethod compile-intent :default
  [_DB _]
  [])

;; ── Multi-select intents ──────────────────────────────────────────────────────

(defn- sort-by-doc-order
  "Sort node IDs by document order (pre-order traversal).
   Ensures operations are applied top-to-bottom, left-to-right."
  [DB ids]
  (sort-by #(get-in DB [:derived :pre %] ##Inf) ids))

(defmethod compile-intent :delete-selected
  [DB _]
  ;; Delete all currently selected nodes in document order
  (let [selected (selection/get-selected-nodes DB)
        ordered (sort-by-doc-order DB selected)]
    (mapcat #(delete-ops DB %) ordered)))

(defmethod compile-intent :indent-selected
  [DB _]
  ;; Indent all currently selected nodes in document order
  ;; Processing top-to-bottom ensures correct parent-child relationships
  (let [selected (selection/get-selected-nodes DB)
        ordered (sort-by-doc-order DB selected)]
    (mapcat #(indent-ops DB %) ordered)))

(defmethod compile-intent :outdent-selected
  [DB _]
  ;; Outdent all currently selected nodes in document order
  ;; Processing top-to-bottom ensures correct parent-child relationships
  (let [selected (selection/get-selected-nodes DB)
        ordered (sort-by-doc-order DB selected)]
    (mapcat #(outdent-ops DB %) ordered)))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn compile-intents
  "Compiles a sequence of high-level intents into a vector of core operations.
   Each intent is compiled independently and the results are concatenated."
  [DB intents]
  (->> intents
       (mapcat #(compile-intent DB %))
       vec))