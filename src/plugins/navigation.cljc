(ns plugins.navigation
  "Navigation plugin: cursor tracking, block traversal, boundary detection.

   Navigation updates selection focus only (undoable).
   Cursor state stored in :ui (ephemeral, not in history)."
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q])
  #?(:clj (:require [kernel.intent :refer [defintent]]))
  #?(:cljs (:require-macros [kernel.intent :refer [defintent]])))

;; ── Getters (Delegated to kernel.query) ───────────────────────────────────────

(def get-prev-block q/prev-sibling)
(def get-next-block q/next-sibling)
(def get-cursor-state q/cursor-state)
(def cursor-at-first-row? q/cursor-first-row?)
(def cursor-at-last-row? q/cursor-last-row?)

;; ── Intent Implementations (Session State Changes) ───────────────────────────

(defintent :navigate-up
  {:sig [db {:keys [block-id]}]
   :doc "Navigate to previous visible block. Updates selection focus only."
   :spec [:map [:type [:= :navigate-up]] [:block-id {:optional true} :string]]
   :ops (when-let [prev-id (get-prev-block db (or block-id (q/focus db)))]
          [{:op :update-node :id const/session-selection-id :props {:focus prev-id}}])})

(defintent :navigate-down
  {:sig [db {:keys [block-id]}]
   :doc "Navigate to next visible block. Updates selection focus only."
   :spec [:map [:type [:= :navigate-down]] [:block-id {:optional true} :string]]
   :ops (when-let [next-id (get-next-block db (or block-id (q/focus db)))]
          [{:op :update-node :id const/session-selection-id :props {:focus next-id}}])})

(defintent :update-cursor-state
  {:sig [db {:keys [block-id first-row? last-row?]}]
   :doc "Update cursor position state for boundary detection. Ephemeral - not in history."
   :spec [:map [:type [:= :update-cursor-state]] [:block-id :string] [:first-row? :boolean] [:last-row? :boolean]]
   :ops (let [current-cursor (get-in db [:ui :cursor] {})
              updated-cursor (assoc current-cursor block-id {:first-row? first-row? :last-row? last-row?})]
          [{:op :update-ui :props {:cursor updated-cursor}}])})
