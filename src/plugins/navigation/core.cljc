(ns plugins.navigation.core
  "Navigation plugin: cursor tracking, block traversal, boundary detection.

   Provides getters for navigation state and implements intent multimethods
   for navigation actions (all view changes, no structural operations)."
  (:require [core.intent :as intent]))

;; ── Getters ───────────────────────────────────────────────────────────────────

(defn get-prev-block
  "Get previous visible block ID (skipping collapsed children)."
  [db block-id]
  (get-in db [:derived :prev-id-of block-id]))

(defn get-next-block
  "Get next visible block ID (skipping collapsed children)."
  [db block-id]
  (get-in db [:derived :next-id-of block-id]))

(defn get-cursor-state
  "Get cursor state for a block (if being tracked)."
  [db block-id]
  (get-in db [:view :cursor block-id]))

(defn cursor-at-first-row?
  "Check if cursor is on first row of contenteditable.
   Relies on mock-text element measurement (UI-dependent)."
  [cursor-state]
  (:first-row? cursor-state false))

(defn cursor-at-last-row?
  "Check if cursor is on last row of contenteditable.
   Relies on mock-text element measurement (UI-dependent)."
  [cursor-state]
  (:last-row? cursor-state false))

;; ── Intent Implementations (View Changes) ────────────────────────────────────

(defmethod intent/intent->db :navigate-up
  [db {:keys [block-id]}]
  (when-let [prev-id (get-prev-block db (or block-id (get-in db [:view :focus])))]
    (-> db
        (assoc-in [:view :focus] prev-id)
        (assoc-in [:view :editing] prev-id))))

(defmethod intent/intent->db :navigate-down
  [db {:keys [block-id]}]
  (when-let [next-id (get-next-block db (or block-id (get-in db [:view :focus])))]
    (-> db
        (assoc-in [:view :focus] next-id)
        (assoc-in [:view :editing] next-id))))

(defmethod intent/intent->db :update-cursor-state
  [db {:keys [block-id first-row? last-row?]}]
  (assoc-in db [:view :cursor block-id]
            {:first-row? first-row?
             :last-row? last-row?}))
