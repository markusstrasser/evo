(ns plugins.selection.core
  "Selection plugin using boolean :selected? property.

   Selection is temporal UI state, not a relationship.
   See ADR-012 for rationale."
  (:require [plugins.registry :as registry]))

;; =============================================================================
;; Derived Indexes
;; =============================================================================

(defn derive-indexes
  "Compute active selections from :selected? props.

   Returns:
   - :selection/active - #{node-ids} of selected nodes"
  [db]
  (let [nodes (:nodes db)
        active-selections (->> nodes
                               (filter (fn [[_id node]]
                                         (get-in node [:props :selected?])))
                               (map first)
                               set)]
    {:selection/active active-selections}))

;; =============================================================================
;; Intent Compilers
;; =============================================================================

(defn toggle-selection-op
  "Return :update-node op to toggle selection on node.

   Sets :selected? to opposite of current value."
  [db node-id]
  (let [currently-selected? (get-in db [:nodes node-id :props :selected?])]
    {:op :update-node
     :id node-id
     :props {:selected? (not currently-selected?)}}))

(defn select-op
  "Return :update-node op to select node (idempotent)."
  [db node-id]
  {:op :update-node
   :id node-id
   :props {:selected? true}})

(defn deselect-op
  "Return :update-node op to deselect node (idempotent)."
  [db node-id]
  {:op :update-node
   :id node-id
   :props {:selected? false}})

(defn clear-all-selections-ops
  "Return ops to deselect all currently selected nodes."
  [db]
  (let [selected-ids (get-in db [:derived :selection/active] #{})]
    (vec (for [node-id selected-ids]
           (deselect-op db node-id)))))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn selected?
  "Check if node is currently selected."
  [db node-id]
  (contains? (get-in db [:derived :selection/active] #{}) node-id))

(defn get-selected-nodes
  "Return set of currently selected node IDs."
  [db]
  (get-in db [:derived :selection/active] #{}))

;; =============================================================================
;; Plugin Registration
;; =============================================================================

;; Auto-register on namespace load
(registry/register! ::selection derive-indexes)
