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
  {:selection/active
   (->> (:nodes db)
        (keep (fn [[id node]]
                (when (get-in node [:props :selected?])
                  id)))
        set)})

;; =============================================================================
;; Intent Compilers
;; =============================================================================

(defn toggle-selection
  "Return :update-node op to toggle selection on node.

   Sets :selected? to opposite of current value."
  [db node-id]
  (let [selected? (get-in db [:nodes node-id :props :selected?])]
    {:op :update-node
     :id node-id
     :props {:selected? (not selected?)}}))

(defn select
  "Return :update-node op to select node (idempotent)."
  [_db node-id]
  {:op :update-node
   :id node-id
   :props {:selected? true}})

(defn deselect
  "Return :update-node op to deselect node (idempotent)."
  [_db node-id]
  {:op :update-node
   :id node-id
   :props {:selected? false}})

(defn clear-all-selections
  "Return ops to deselect all currently selected nodes."
  [db]
  (let [selected-ids (get-in db [:derived :selection/active] #{})]
    (mapv #(deselect db %) selected-ids)))

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
