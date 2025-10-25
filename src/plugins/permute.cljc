(ns plugins.permute
  "Intent-level reordering and movement operations.

   Lowers high-level reorder/move intents into minimal sequences of :place operations.
   Uses core.position for position resolution and core.permutation for deterministic ordering."
  (:require [kernel.position :as pos]
            [kernel.intent :as intent])
  #?(:clj (:require [kernel.intent :refer [defintent]]))
  #?(:cljs (:require-macros [kernel.intent :refer [defintent]])))

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
  (let [;; Get current siblings
        current-kids (pos/children db parent)

        ;; Remove selected nodes (they'll be re-inserted)
        selection-set (set selection)
        kids-without-selection (filterv #(not (contains? selection-set %)) current-kids)

        ;; Resolve anchor in the list WITHOUT the selected nodes
        ;; This matches the :place semantics (remove → resolve → insert)
        target-idx (try
                     (pos/resolve-anchor-in-vec kids-without-selection anchor)
                     (catch #?(:clj Exception :cljs js/Error) _
                       ;; If anchor references a selected node, it will fail after removal
                       ;; Fallback to end
                       (count kids-without-selection)))

        ;; Build result: head + selection + tail
        head (subvec kids-without-selection 0 (min target-idx (count kids-without-selection)))
        tail (subvec kids-without-selection (min target-idx (count kids-without-selection)))]

    (vec (concat head selection tail))))

(defn- normalize-anchor-for-ops
  "Convert intent-level anchors to op-level anchors.
   Ensures :at-start/:at-end are converted to :first/:last for op consistency."
  [anchor]
  (case anchor
    :at-start :first
    :at-end :last
    anchor))

(defn lower-reorder
  "Lower a :reorder intent to a minimal sequence of :place operations.

   Intent schema:
   {:intent :reorder
    :selection [id ...]      ; IDs to reorder (non-contiguous OK)
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
                                 :at (normalize-anchor-for-ops anchor)}))))
                    []
                    selection)]
    ops))

(defn lower-move
  "Lower a multi-select move intent (potentially cross-parent).

   Intent schema:
   {:intent :move
    :selection [id ...]      ; IDs to move (preserves order)
    :parent parent-id        ; target parent (can differ from source)
    :anchor Anchor}          ; where selection lands in target

   Returns: vector of :place ops.

   This is actually identical to lower-reorder - the :place operation handles
   cross-parent moves automatically (remove from current, insert in target)."
  [db intent]
  ;; Move and reorder are the same at the op level
  (lower-reorder db intent))

(defn validate-intent
  "Validate a reorder/move intent before lowering.

   Returns: nil if valid, or issue map if invalid.

   Checks:
   - Selection IDs exist
   - Parent exists
   - No cycles (none of selection are ancestors of parent)
   - Anchor is valid (if it can be pre-validated)"
  [db {:keys [selection parent] :as intent}]
  (let [nodes (:nodes db)
        roots (set (:roots db))]
    (cond
      ;; Check all selected nodes exist
      (not-every? #(contains? nodes %) selection)
      {:reason ::node-not-found
       :hint "One or more selected nodes don't exist"
       :missing (filterv #(not (contains? nodes %)) selection)
       :intent intent}

      ;; Check parent exists
      (not (or (contains? roots parent)
               (contains? nodes parent)))
      {:reason ::parent-not-found
       :hint (str "Parent " parent " doesn't exist")
       :parent parent
       :intent intent}

      ;; Check for cycles: none of selection can be ancestors of parent
      (and (string? parent)  ; roots can't have parents
           (some (fn [sel-id]
                   (loop [curr parent
                          visited #{}]
                     (cond
                       (nil? curr) false
                       (= curr sel-id) true
                       (contains? visited curr) false
                       (contains? roots curr) false
                       :else (recur (get-in db [:derived :parent-of curr])
                                    (conj visited curr)))))
                 selection))
      {:reason ::would-create-cycle
       :hint "Cannot move node into its own descendant"
       :intent intent}

      ;; All checks passed
      :else nil)))

(defn lower
  "Main entry point: lower a reorder or move intent to ops.

   Returns:
   - {:ops [Op...]} if valid
   - {:issues [Issue...]} if invalid"
  [db intent]
  (if-let [issue (validate-intent db intent)]
    {:issues [issue]}
    (let [ops (case (:intent intent)
                :reorder (lower-reorder db intent)
                :move (lower-move db intent)
                (throw (ex-info "Unknown intent type"
                               {:intent intent
                                :expected #{:reorder :move}})))]
      {:ops ops})))

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(defintent :move
  {:sig [db intent]
   :doc "Move selection to target parent at anchor position."
   :spec [:map [:type [:= :move]] [:selection [:vector :string]] [:parent :string] [:anchor [:or :keyword [:map [:after :string]]]]]
   :ops (:ops (lower db (assoc intent :intent :move)))})

(defintent :reorder
  {:sig [db intent]
   :doc "Reorder selection within same parent to anchor position."
   :spec [:map [:type [:= :reorder]] [:selection [:vector :string]] [:parent :string] [:anchor [:or :keyword [:map [:after :string]]]]]
   :ops (:ops (lower db (assoc intent :intent :reorder)))})
