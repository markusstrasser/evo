(ns plugins.navigation
  "Navigation plugin: cursor tracking, block traversal, boundary detection.

   Navigation state stored in session nodes.
   All state changes emit ops for full undo/redo support."
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
  "Get cursor state from session/cursor node."
  [db block-id]
  (get-in db [:nodes "session/cursor" :props block-id]))

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

;; ── Intent Implementations (Session State Changes) ───────────────────────────

(defmethod intent/intent->ops :navigate-up
  [db {:keys [block-id]}]
  (when-let [prev-id (get-prev-block db (or block-id (get-in db [:nodes "session/selection" :props :focus])))]
    [{:op :update-node :id "session/selection" :props {:focus prev-id}}
     {:op :update-node :id "session/edit" :props {:block-id prev-id}}]))

(defmethod intent/intent->ops :navigate-down
  [db {:keys [block-id]}]
  (when-let [next-id (get-next-block db (or block-id (get-in db [:nodes "session/selection" :props :focus])))]
    [{:op :update-node :id "session/selection" :props {:focus next-id}}
     {:op :update-node :id "session/edit" :props {:block-id next-id}}]))

(defmethod intent/intent->ops :update-cursor-state
  [db {:keys [block-id first-row? last-row?]}]
  (let [current-cursor (get-in db [:nodes "session/cursor" :props] {})
        updated-cursor (assoc current-cursor block-id {:first-row? first-row? :last-row? last-row?})]
    [{:op :update-node :id "session/cursor" :props updated-cursor}]))
