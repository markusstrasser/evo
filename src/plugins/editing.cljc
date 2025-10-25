(ns plugins.editing
  "Editing plugin: edit mode state, content operations.

   Edit state stored in session/edit node.
   All state changes emit ops for full undo/redo support."
  (:require [kernel.intent :as intent])
  #?(:clj (:require [kernel.intent :refer [defintent]]))
  #?(:cljs (:require-macros [kernel.intent :refer [defintent]])))

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

(defintent :enter-edit
  {:sig [_db {:keys [block-id]}]
   :doc "Enter edit mode for a block. Sets edit block and focus."
   :spec [:map [:type [:= :enter-edit]] [:block-id :string]]
   :ops [{:op :update-node :id "session/edit" :props {:block-id block-id}}
         {:op :update-node :id "session/selection" :props {:focus block-id}}]})

(defintent :exit-edit
  {:sig [_db _intent]
   :doc "Exit edit mode. Clears editing block ID."
   :spec [:map [:type [:= :exit-edit]]]
   :ops [{:op :update-node :id "session/edit" :props {:block-id nil}}]})

;; ── Intent Implementations (Structural Changes) ───────────────────────────────

(defintent :update-content
  {:sig [_db {:keys [block-id text]}]
   :doc "Update block text content."
   :spec [:map [:type [:= :update-content]] [:block-id :string] [:text :string]]
   :ops [{:op :update-node :id block-id :props {:text text}}]})

(defintent :merge-with-prev
  {:sig [db {:keys [block-id]}]
   :doc "Merge block with previous sibling, delete current block."
   :spec [:map [:type [:= :merge-with-prev]] [:block-id :string]]
   :ops (let [prev-id (get-in db [:derived :prev-id-of block-id])
              prev-text (get-block-text db prev-id)
              curr-text (get-block-text db block-id)
              merged-text (str prev-text curr-text)]
          (when prev-id
            [{:op :update-node :id prev-id :props {:text merged-text}}
             {:op :place :id block-id :under :trash :at :last}]))})

(defintent :split-at-cursor
  {:sig [db {:keys [block-id cursor-pos]}]
   :doc "Split block at cursor position into two blocks."
   :spec [:map [:type [:= :split-at-cursor]] [:block-id :string] [:cursor-pos :int]]
   :ops (let [text (get-block-text db block-id)
              before (subs text 0 cursor-pos)
              after (subs text cursor-pos)
              parent (get-in db [:derived :parent-of block-id])
              new-id (str "block-" (random-uuid))]
          (when parent
            [{:op :update-node :id block-id :props {:text before}}
             {:op :create-node :id new-id :type :block :props {:text after}}
             {:op :place :id new-id :under parent :at {:after block-id}}]))})
