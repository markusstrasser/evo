(ns plugins.editing.core
  "Editing plugin: edit mode state, content operations.

   Provides getters for edit state and implements intent multimethods
   for both view changes (enter/exit edit) and structural changes (content updates)."
  (:require [core.intent :as intent]))

;; ── Getters ───────────────────────────────────────────────────────────────────

(defn editing-block-id
  "Get currently editing block ID (nil if not editing)."
  [db]
  (get-in db [:view :editing]))

(defn get-edit-mode
  "Get current edit mode state."
  [db]
  {:editing? (some? (editing-block-id db))
   :block-id (editing-block-id db)})

(defn get-block-text
  "Get text content of a block."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

;; ── Intent Implementations (View Changes) ────────────────────────────────────

(defmethod intent/intent->db :enter-edit
  [db {:keys [block-id]}]
  (-> db
      (assoc-in [:view :editing] block-id)
      (assoc-in [:view :focus] block-id)))

(defmethod intent/intent->db :exit-edit
  [db _intent]
  (update db :view dissoc :editing))

;; ── Intent Implementations (Structural Changes) ───────────────────────────────

(defmethod intent/intent->ops :update-content
  [_db {:keys [block-id text]}]
  [{:op :update-node :id block-id :props {:text text}}])

(defmethod intent/intent->ops :merge-with-prev
  [db {:keys [block-id]}]
  (let [prev-id (get-in db [:derived :prev-id-of block-id])
        prev-text (get-block-text db prev-id)
        curr-text (get-block-text db block-id)
        merged-text (str prev-text curr-text)]
    (when prev-id
      [{:op :update-node :id prev-id :props {:text merged-text}}
       {:op :place :id block-id :under :trash :at :last}])))

(defmethod intent/intent->ops :split-at-cursor
  [db {:keys [block-id cursor-pos]}]
  (let [text (get-block-text db block-id)
        before (subs text 0 cursor-pos)
        after (subs text cursor-pos)
        parent (get-in db [:derived :parent-of block-id])
        new-id (str "block-" (random-uuid))]
    (when parent
      [{:op :update-node :id block-id :props {:text before}}
       {:op :create-node :id new-id :type :block :props {:text after}}
       {:op :place :id new-id :under parent :at {:after block-id}}])))
