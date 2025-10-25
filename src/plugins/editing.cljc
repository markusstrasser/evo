(ns plugins.editing
  "Editing plugin: edit mode state, content operations.

   Edit state stored in :ui (ephemeral, not in history).
   Content changes emit ops for full undo/redo support."
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q])
  #?(:clj (:require [kernel.intent :refer [defintent]]))
  #?(:cljs (:require-macros [kernel.intent :refer [defintent]])))

;; ── Getters (Delegated to kernel.query) ───────────────────────────────────────

(def editing-block-id q/editing-block-id)
(def editing? q/editing?)

(defn get-block-text
  "Get text content of a block."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

;; ── Intent Implementations (Session State Changes) ───────────────────────────

(defintent :enter-edit
  {:sig [_db {:keys [block-id]}]
   :doc "Enter edit mode for a block. Ephemeral - not in undo/redo history."
   :spec [:map [:type [:= :enter-edit]] [:block-id :string]]
   :ops [{:op :update-ui :props {:editing-block-id block-id}}]})

(defintent :exit-edit
  {:sig [_db _intent]
   :doc "Exit edit mode. Ephemeral - not in undo/redo history."
   :spec [:map [:type [:= :exit-edit]]]
   :ops [{:op :update-ui :props {:editing-block-id nil}}]})

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
             {:op :place :id block-id :under const/root-trash :at :last}]))})

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
