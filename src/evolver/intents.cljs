(ns evolver.intents
  (:require [evolver.kernel :as k]))

;;; =================================================================
;;; Pure Data Transformations (DB -> Transaction)
;;; =================================================================

(defn create-child-block [db {:keys [cursor]}]
  (when cursor
    [{:op :insert
      :parent-id cursor
      :node-id (k/gen-new-id)
      :node-data {:type :div :props {:text "New child"}}
      :position nil}]))

(defn create-sibling-above [db {:keys [cursor]}]
  (when cursor
    (let [{:keys [parent index]} (k/node-position db cursor)]
      [{:op :insert
        :parent-id parent
        :node-id (k/gen-new-id)
        :node-data {:type :div :props {:text "New sibling"}}
        :position index}])))

(defn create-sibling-below [db {:keys [cursor]}]
  (when cursor
    (let [{:keys [parent index]} (k/node-position db cursor)]
      [{:op :insert
        :parent-id parent
        :node-id (k/gen-new-id)
        :node-data {:type :div :props {:text "New sibling"}}
        :position (inc index)}])))

(defn enter-new-block [db {:keys [cursor cursor-position block-content]}]
  (when cursor
    (let [{:keys [parent index]} (k/node-position db cursor)]
      (if (or (nil? cursor-position) (= cursor-position (count (or block-content ""))))
        ;; Cursor at end -> create new sibling
        [{:op :insert
          :parent-id parent
          :node-id (k/gen-new-id)
          :node-data {:type :div :props {:text ""}}
          :position (inc index)}]
        ;; Cursor in middle -> split block
        (let [content-before (subs (or block-content "") 0 cursor-position)
              content-after (subs (or block-content "") cursor-position)]
          [{:op :transaction
            :commands [{:op :patch :node-id cursor :updates {:props {:text content-before}}}
                       {:op :insert
                        :parent-id parent
                        :node-id (k/gen-new-id)
                        :node-data {:type :div :props {:text content-after}}
                        :position (inc index)}]}])))))

(defn indent [db {:keys [cursor]}]
  (when cursor
    (let [{:keys [index parent children]} (k/node-position db cursor)]
      (when (> index 0)
        [{:op :move
          :node-id cursor
          :new-parent-id (nth children (dec index))
          :position nil}]))))

(defn outdent [db {:keys [cursor]}]
  (when cursor
    (let [{:keys [parent]} (k/node-position db cursor)]
      (when (not= parent k/root-id)
        (let [{p2 :parent i2 :index} (k/node-position db parent)]
          [{:op :move
            :node-id cursor
            :new-parent-id p2
            :position (inc i2)}])))))

(defn move-up [db {:keys [cursor]}]
  (when cursor
    (let [{:keys [parent index]} (k/node-position db cursor)
          new (max 0 (dec index))]
      (when (not= index new)
        [{:op :reorder
          :node-id cursor
          :parent-id parent
          :from-index index
          :to-index new}]))))

(defn move-down [db {:keys [cursor]}]
  (when cursor
    (let [{:keys [parent index]} (k/node-position db cursor)
          sibs (get-in db [:children-by-parent parent])
          new (min (dec (count sibs)) (inc index))]
      (when (not= index new)
        [{:op :reorder
          :node-id cursor
          :parent-id parent
          :from-index index
          :to-index new}]))))

(defn delete-blocks [db {:keys [ids]}]
  (mapv (fn [id] {:op :delete :node-id id :recursive true}) ids))

(defn merge-block-up [db {:keys [cursor cursor-position]}]
  "Merge current block with the previous block (Backspace at start)"
  (when (and cursor (= cursor-position 0))
    (let [prev-node (k/get-prev db cursor)]
      (when prev-node
        (let [current-content (get-in db [:nodes cursor :props :text] "")
              prev-content (get-in db [:nodes prev-node :props :text] "")
              merged-content (str prev-content current-content)
              children (get-in db [:children-by-parent cursor] [])]
          [{:op :transaction
            :commands (concat
                       ;; Update previous node with merged content
                       [{:op :patch
                         :node-id prev-node
                         :updates {:props {:text merged-content}}}]
                       ;; Move current node's children to previous node
                       (map (fn [child-id]
                              {:op :move
                               :node-id child-id
                               :new-parent-id prev-node
                               :position :last})
                            children)
                       ;; Delete the current node
                       [{:op :delete :node-id cursor}])}])))))

(defn merge-block-down [db {:keys [cursor cursor-position block-content]}]
  "Merge next block into current block (Delete at end)"
  (when (and cursor (= cursor-position (count (or block-content ""))))
    (let [next-node (k/get-next db cursor)]
      (when next-node
        (let [current-content (or block-content "")
              next-content (get-in db [:nodes next-node :props :text] "")
              merged-content (str current-content next-content)
              children (get-in db [:children-by-parent next-node] [])]
          [{:op :transaction
            :commands (concat
                       ;; Update current node with merged content
                       [{:op :patch
                         :node-id cursor
                         :updates {:props {:text merged-content}}}]
                       ;; Move next node's children to current node
                       (map (fn [child-id]
                              {:op :move
                               :node-id child-id
                               :new-parent-id cursor
                               :position :last})
                            children)
                       ;; Delete the next node
                       [{:op :delete :node-id next-node}])}])))))

(defn toggle-selection [db {:keys [target-id]}]
  "Toggle selection state of a node"
  (when target-id
    (let [current-selection (get-in db [:view :selection] [])
          is-selected (some #(= % target-id) current-selection)
          new-selection (if is-selected
                          (vec (remove #(= % target-id) current-selection))
                          (conj current-selection target-id))]
      [{:op :update-view
        :path [:selection]
        :value new-selection}])))

;;; =================================================================
;;; Public API
;;; =================================================================
(def intents
  {:create-child-block create-child-block
   :create-sibling-above create-sibling-above
   :create-sibling-below create-sibling-below
   :enter-new-block enter-new-block
   :indent-block indent
   :outdent-block outdent
   :move-up move-up
   :move-down move-down
   :delete-selected-blocks delete-blocks
   :merge-block-up merge-block-up
   :merge-block-down merge-block-down
   :toggle-selection toggle-selection
   ;; ... add all other pure state transformations here
   })
