(ns plugins.siblings-order
  "Plugin for canonical child ordering and reorder permutation computation."
  (:require [core.permutation :as perm]
            [plugins.registry :as registry]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Core Logic
;; ══════════════════════════════════════════════════════════════════════════════

(defn- child-order-index
  "Build child-order-of map: {parent -> [child-ids in canonical order]}"
  [children-by-parent]
  (reduce-kv (fn [m parent children]
               (assoc m parent (vec children)))
             {}
             children-by-parent))

(defn derived
  "Compute sibling order derived data.

   Returns map with:
   - :child-order-of {parent -> [child-ids]}"
  [db]
  (let [children-by-parent (:children-by-parent db)]
    {:siblings {:child-order-of (child-order-index children-by-parent)}}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Helper API
;; ══════════════════════════════════════════════════════════════════════════════

(defn target-permutation
  "Compute permutation to transform current child order to target order.

   Args:
   - db: Database with derived indexes
   - parent: Parent whose children are being reordered
   - target-vec: Target order as vector of child IDs

   Returns: Permutation p such that (arrange current-order p) == target-vec

   Throws: AssertionError if target-vec is not a valid reordering of current children."
  [db parent target-vec]
  (let [src (get-in db [:children-by-parent parent] [])]
    (assert (= (set src) (set target-vec))
            (str "target-permutation: target must be a reordering of current children. "
                 "Parent: " parent ", "
                 "Current: " src ", "
                 "Target: " target-vec))
    (perm/from-to src target-vec)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Plugin Registration
;; ══════════════════════════════════════════════════════════════════════════════

(defn init!
  "Register the siblings-order plugin."
  []
  (registry/register! :siblings-order derived))
