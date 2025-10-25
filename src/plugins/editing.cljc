(ns plugins.editing
  "Editing plugin: edit mode state, content operations.

   Edit state stored in session/edit node.
   All state changes emit ops for full undo/redo support."
  (:require [core.intent :as intent]))

;; ── Getters ───────────────────────────────────────────────────────────────────

(defn editing-block-id
  "Get currently editing block ID (nil if not editing)."
  [db]
  (get-in db [:nodes "session/edit" :props :block-id]))

(defn get-edit-mode
  "Get current edit mode state."
  [db]
  {:editing? (some? (editing-block-id db))
   :block-id (editing-block-id db)})

(defn get-block-text
  "Get text content of a block."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

;; ── Intent Implementations (Session State Changes) ───────────────────────────

(defmethod intent/intent->ops :enter-edit
  [_db {:keys [block-id]}]
  [{:op :update-node :id "session/edit" :props {:block-id block-id}}
   {:op :update-node :id "session/selection" :props {:focus block-id}}])

(defmethod intent/intent->ops :exit-edit
  [_db _intent]
  [{:op :update-node :id "session/edit" :props {:block-id nil}}])

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
