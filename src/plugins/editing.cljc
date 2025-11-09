(ns plugins.editing
  "Editing plugin: edit mode state, content operations.

   Edit state stored in :ui (ephemeral, not in history).
   Content changes emit ops for full undo/redo support."
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]))

;; ── Private Helpers ───────────────────────────────────────────────────────────

(defn- get-block-text
  "Get text content of a block (internal helper)."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

;; ── Intent Implementations (Session State Changes) ───────────────────────────

(intent/register-intent! :enter-edit
                         {:doc "Enter edit mode for a block. Ephemeral - not in undo/redo history.
         Optional :cursor-at can be :start or :end to position cursor."
                          :spec [:map [:type [:= :enter-edit]] [:block-id :string] [:cursor-at {:optional true} [:enum :start :end]]]
                          :handler (fn [_db {:keys [block-id cursor-at]}]
                                     [{:op :update-node
                                       :id const/session-ui-id
                                       :props {:editing-block-id block-id
                                               :cursor-position cursor-at}}])})

(intent/register-intent! :exit-edit
                         {:doc "Exit edit mode. Ephemeral - not in undo/redo history."
                          :spec [:map [:type [:= :exit-edit]]]
                          :handler (fn [_db _intent]
                                     [{:op :update-node :id const/session-ui-id :props {:editing-block-id nil}}])})

(intent/register-intent! :clear-cursor-position
                         {:doc "Clear cursor-position from session state. Used after applying cursor position to prevent reapplication."
                          :spec [:map [:type [:= :clear-cursor-position]]]
                          :handler (fn [_db _intent]
                                     [{:op :update-node :id const/session-ui-id :props {:cursor-position nil}}])})

(intent/register-intent! :update-cursor-state
                         {:doc "Update cursor position state for boundary detection. Ephemeral - not in history."
                          :spec [:map [:type [:= :update-cursor-state]] [:block-id :string] [:first-row? :boolean] [:last-row? :boolean]]
                          :handler (fn [db {:keys [block-id first-row? last-row?]}]
                                     (let [current-cursor (get-in db [:nodes const/session-ui-id :props :cursor] {})
                                           updated-cursor (assoc current-cursor block-id {:first-row? first-row? :last-row? last-row?})]
                                       [{:op :update-node :id const/session-ui-id :props {:cursor updated-cursor}}]))})

;; ── Intent Implementations (Structural Changes) ───────────────────────────────

(intent/register-intent! :update-content
                         {:doc "Update block text content."
                          :spec [:map [:type [:= :update-content]] [:block-id :string] [:text :string]]
                          :handler (fn [_db {:keys [block-id text]}]
                                     [{:op :update-node :id block-id :props {:text text}}])})

(intent/register-intent! :merge-with-prev
                         {:doc "Merge block with previous sibling, placing cursor at merge point."
                          :spec [:map [:type [:= :merge-with-prev]] [:block-id :string]]
                          :handler (fn [db {:keys [block-id]}]
                                     (let [prev-id (get-in db [:derived :prev-id-of block-id])
                                           prev-text (get-block-text db prev-id)
                                           curr-text (get-block-text db block-id)
                                           merged-text (str prev-text curr-text)
                    ;; KEY: Calculate where cursor should land (end of prev text)
                                           cursor-at (count prev-text)]
                                       (when prev-id
                                         [{:op :update-node :id prev-id :props {:text merged-text}}
                                          {:op :place :id block-id :under const/root-trash :at :last}
                   ;; NEW: Store cursor position for entering prev block
                                          {:op :update-node
                                           :id const/session-ui-id
                                           :props {:editing-block-id prev-id
                                                   :cursor-position cursor-at}}])))})

(intent/register-intent! :split-at-cursor
                         {:doc "Split block at cursor position into two blocks."
                          :spec [:map [:type [:= :split-at-cursor]] [:block-id :string] [:cursor-pos :int]]
                          :handler (fn [db {:keys [block-id cursor-pos]}]
                                     (let [text (get-block-text db block-id)
                                           before (subs text 0 cursor-pos)
                                           after (subs text cursor-pos)
                                           parent (get-in db [:derived :parent-of block-id])
                                           new-id (str "block-" (random-uuid))]
                                       (when parent
                                         [{:op :update-node :id block-id :props {:text before}}
                                          {:op :create-node :id new-id :type :block :props {:text after}}
                                          {:op :place :id new-id :under parent :at {:after block-id}}])))})
