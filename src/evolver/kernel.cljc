(ns evolver.kernel
  (:require [clojure.set :as set]
            [malli.core :as m]
            [malli.error :as me]
            [evolver.schemas :as schemas]
            [evolver.constants :as constants]))

(declare insert-node move-node patch-node delete-node reorder-node undo-last-operation redo-last-operation add-reference remove-reference initial-db-base)

;; Derive ALL metadata after ANY operation:
(defn derive-tree-metadata
  "Return {:depth {id depth} :paths {id [parent ...]}} from a :children-by-parent adjacency map."
  [{:keys [children-by-parent]}]
  (letfn
   [(walker [node-id depth path acc]
      (let [node-children (get children-by-parent node-id []) ;;lookup node in the children-by-parent map; if it's not there, use []

            acc (-> acc
                    (assoc-in [:depth node-id] depth)
                    (assoc-in [:paths node-id] path))]

        (reduce
         (fn [acc child] (walker child (inc depth) (conj path node-id) acc))
         acc
         node-children)))]
    (walker "root" 0 [] {})))

(defn find-parent [children-by-parent node-id]
  (some (fn [[p children]] (when (some #{node-id} children) p)) children-by-parent))

(defn update-derived [db]
  (assoc db :derived (derive-tree-metadata db)))

(defn validate-db-state
  "Validate db state and throw if invalid"
  [db]
  (try
    (schemas/validate-db db)
    (catch #?(:clj Exception :cljs js/Error) e
      (throw (ex-info "Database state validation failed"
                      {:errors (:errors (ex-data e))
                       :db db}
                      e)))))

(defn log-operation
  "Add operation to transaction log"
  [db operation]
  (let [timestamp #?(:clj (System/currentTimeMillis)
                     :cljs (.now js/Date))
        tx {:op (:op operation)
            :timestamp timestamp
            :args (dissoc operation :op)}]
    (schemas/validate-transaction tx) ; Validate transaction
    (update db :tx-log conj tx)))

(defn log-message
  "Add message to log history based on log level"
  [db level message & [data]]
  (let [current-level (:log-level db :info)
        should-log (>= (constants/log-levels level 0) (constants/log-levels current-level 1))]
    (if should-log
      (let [timestamp #?(:clj (System/currentTimeMillis)
                         :cljs (.now js/Date))
            log-entry {:level level
                       :message message
                       :timestamp timestamp
                       :data data}]
        (update db :log-history conj log-entry))
      db)))

(defmulti apply-command
  "Apply command based on operation type"
  (fn [_ command] (:op command)))

(defmethod apply-command :insert [db command]
  (insert-node db command))

(defmethod apply-command :move [db command]
  (move-node db command))

(defmethod apply-command :patch [db command]
  (patch-node db command))

(defmethod apply-command :delete [db command]
  (delete-node db command))

(defmethod apply-command :reorder [db command]
  (reorder-node db command))

(defmethod apply-command :add-reference [db command]
  (add-reference db command))

(defmethod apply-command :remove-reference [db command]
  (remove-reference db command))

(defmethod apply-command :undo [db command]
  (undo-last-operation db))

(defmethod apply-command :redo [db command]
  (redo-last-operation db))

(defmethod apply-command :default [db command]
  (log-message db :warn (str "Unknown command op: " (:op command)))
  db)

(defn safe-apply-command
  "Apply command with proper error handling and validation"
  [db command]
  ;; Validate command structure first
  (schemas/validate-command command)
  ;; Validate db state before operation
  (when-not (m/validate schemas/db-schema db)
    (throw (ex-info "Invalid database state before command"
                    {:errors (me/humanize (m/explain schemas/db-schema db))
                     :command command})))
  (try
    (let [new-db (apply-command db command)
          new-db (if (#{:insert :delete :move :patch :reorder :add-reference :remove-reference} (:op command))
                   (log-operation new-db command)
                   new-db)]
      ;; Validate db state after operation
      (when-not (m/validate schemas/db-schema new-db)
        (throw (ex-info "Invalid database state after command"
                        {:errors (me/humanize (m/explain schemas/db-schema new-db))
                         :command command
                         :before-db db})))
      new-db)
    (catch #?(:clj Exception :cljs js/Error) e
      (try
        (log-message db :error (str "Command failed: " (:op command)) {:error (ex-message e) :command command})
        (catch #?(:clj Exception :cljs js/Error) _ nil)) ; If logging fails, ignore
      (throw (ex-info "Command execution failed" {:error (ex-message e) :command command} e)))))

(defn undo-last-operation
  "Update history index for undo"
  [db]
  (update db :history-index (fn [idx] (max 0 (dec (or idx 0))))))

(defn redo-last-operation
  "Update history index for redo"
  [db]
  (let [history (:history db)
        history-index (:history-index db)
        max-index (count history)]
    (if (< history-index max-index)
      (update db :history-index inc)
      db)))

(defn node-position [db node-id]
  (when-let [parent (find-parent (:children-by-parent db) node-id)]
    (let [children (get (:children-by-parent db) parent [])
          idx (.indexOf children node-id)]
      {:parent parent :index idx :children children})))

(def db (update-derived constants/initial-db-base))

(defn insert-at-position
  [v thing position-spec]
  (let [v (or v [])]
    (cond
      (nil? position-spec) (conj (vec v) thing)
      (number? position-spec) (vec (concat (take position-spec v) [thing] (drop position-spec v)))
      (= (:type position-spec) :last) (conj (vec v) thing)
      (= (:type position-spec) :first) (vec (cons thing v))
      (= (:type position-spec) :after)
      (let [sibling-id (:sibling-id position-spec)
            idx (some #(when (= (get v %) sibling-id) %) (range (count v)))]
        (if idx
          (vec (concat (take (inc idx) v) [thing] (drop (inc idx) v)))
          (conj (vec v) thing))) ; if sibling not found, append
      (= (:type position-spec) :before)
      (let [sibling-id (:sibling-id position-spec)
            idx (some #(when (= (get v %) sibling-id) %) (range (count v)))]
        (if idx
          (vec (concat (take idx v) [thing] (drop idx v)))
          (vec (cons thing v)))) ; if not found, prepend
      :else (conj (vec v) thing))))

(defn insert-node [db {:keys [parent-id node-id node-data position]}]
  (schemas/validate-node node-data) ; Validate node data
  (let [new-db (-> db
                   (assoc-in [:nodes node-id] node-data)
                   (update-in [:children-by-parent parent-id]
                              #(insert-at-position % node-id position)))]
    (update-derived new-db)))

(defn patch-node [db {:keys [node-id updates]}]
  (let [new-db (update-in db [:nodes node-id] merge updates)]
    (update-derived new-db)))

(defn move-node [db {:keys [node-id new-parent-id position]}]
  (let [old-parent (find-parent (:children-by-parent db) node-id)
        new-db (-> db
                   (update-in [:children-by-parent old-parent] #(vec (remove #{node-id} %)))
                   (update-in [:children-by-parent new-parent-id]
                              #(insert-at-position % node-id position)))]
    (update-derived new-db)))

(defn delete-node [db {:keys [node-id]}]
  (let [descendants (set (tree-seq #(get-in db [:children-by-parent %])
                                   #(get-in db [:children-by-parent %])
                                   node-id))
        parent (find-parent (:children-by-parent db) node-id)
        new-db (-> db
                   (update :nodes #(apply dissoc % descendants))
                   (update :children-by-parent #(-> %
                                                    (update parent (fn [ch] (vec (remove #{node-id} ch))))
                                                    ((fn [m] (apply dissoc m descendants)))))
                   (update :view #(into {} (map (fn [[k v]] [k (set/difference v descendants)]) %)))
                   ;; Clean up references to deleted nodes
                   (update :references #(apply dissoc % descendants))
                   ;; Remove references FROM deleted nodes in other nodes' reference lists
                   (update :references (fn [refs]
                                         (into {} (map (fn [[node-id ref-set]]
                                                         [node-id (set/difference ref-set descendants)])
                                                       refs)))))]
    (update-derived new-db)))

(defn reorder-node [db {:keys [node-id parent-id to-index]}]
  (let [siblings (get (:children-by-parent db) parent-id [])
        siblings-without-node (vec (remove #{node-id} siblings))
        new-siblings (vec (concat (take to-index siblings-without-node)
                                  [node-id]
                                  (drop to-index siblings-without-node)))]
    (update-derived (assoc-in db [:children-by-parent parent-id] new-siblings))))

;; NOTE: execute-command is redundant - use apply-command directly or go through middleware/state pipeline

;; Structural editor operations

(defn current-node-id [db]
  (first (:selected (:view db))))

(defn gen-new-id []
  (str "node-" (rand-int 10000)))

(defn create-child-block [db]
  (let [current (current-node-id db)
        new-id (gen-new-id)
        new-node {:type :div :props {:text (str "Child of " current)}}]
    (insert-node db {:parent-id current :node-id new-id :node-data new-node :position nil})))

(defn create-sibling-above [db]
  (let [current (current-node-id db)
        {:keys [parent index]} (node-position db current)
        new-id (gen-new-id)
        new-node {:type :div :props {:text (str "Sibling above " current)}}]
    (insert-node db {:parent-id parent :node-id new-id :node-data new-node :position index})))

(defn create-sibling-below [db]
  (let [current (current-node-id db)
        {:keys [parent index]} (node-position db current)
        new-id (gen-new-id)
        new-node {:type :div :props {:text (str "Sibling below " current)}}]
    (insert-node db {:parent-id parent :node-id new-id :node-data new-node :position (inc index)})))

(defn indent [db]
  "Move current block under the previous sibling (make it a child)"
  (let [current (current-node-id db)
        {:keys [index children]} (node-position db current)]
    (if (> index 0)
      (let [prev-sib (get children (dec index))]
        (move-node db {:node-id current :new-parent-id prev-sib :position nil}))
      db)))

(defn outdent [db]
  (let [current (current-node-id db)
        {:keys [parent]} (node-position db current)]
    (if (not= parent "root")
      (let [grandparent-pos (node-position db parent)
            grandparent (:parent grandparent-pos)]
        (move-node db {:node-id current :new-parent-id grandparent :position {:type :after :sibling-id parent}}))
      db)))

(defn add-reference [db {:keys [from-node-id to-node-id]}]
  (if (and (contains? (:nodes db) from-node-id)
           (contains? (:nodes db) to-node-id))
    (update-in db [:references to-node-id] (fnil conj #{}) from-node-id)
    (throw (ex-info "Cannot add reference: nodes do not exist"
                    {:from-node-id from-node-id
                     :to-node-id to-node-id
                     :existing-nodes (keys (:nodes db))}))))

(defn remove-reference [db {:keys [from-node-id to-node-id]}]
  (if (and (contains? (:nodes db) from-node-id)
           (contains? (:nodes db) to-node-id))
    (update-in db [:references to-node-id] (fnil disj #{}) from-node-id)
    (do
      (log-message db :warn "Cannot remove reference: nodes do not exist"
                   {:from-node-id from-node-id :to-node-id to-node-id :existing-nodes (keys (:nodes db))})
      db)))

(defn get-references [db node-id]
  (get (:references db) node-id #{}))

(defn get-referenced-by [db node-id]
  (set (for [[to-node-id referencers] (:references db)
             :when (contains? referencers node-id)]
         to-node-id)))

(defn get-next
  "Find the next block in sequential navigation order.
   First tries to move to first child (if expanded), then to next sibling, 
   then up to parent's next sibling recursively.
   Respects collapsed blocks - if a block is collapsed, skips its children."
  [db node-id & {:keys [respect-collapsed?] :or {respect-collapsed? true}}]
  (let [collapsed (get-in db [:view :collapsed] #{})
        children-by-parent (:children-by-parent db)
        children (get children-by-parent node-id [])]
    (if (and (seq children)
             (or (not respect-collapsed?)
                 (not (contains? collapsed node-id))))
      ;; Has children and not collapsed, go to first child
      (first children)
      ;; No accessible children, try next sibling or up
      (loop [current-id node-id]
        (let [pos (node-position db current-id)]
          (when pos
            (let [parent-id (:parent pos)
                  siblings (:children pos)
                  current-idx (:index pos)]
              ;; Try to move to next sibling
              (if (< (inc current-idx) (count siblings))
                (nth siblings (inc current-idx))
                ;; No next sibling, try parent's next sibling
                (when (not= parent-id "root")
                  (recur parent-id))))))))))

(defn get-prev
  "Find the previous block in sequential navigation order.
   Complex logic to ensure intuitive upward navigation:
   1. Look for previous sibling
   2. If previous sibling is expanded and has children, go to deepest last visible child
   3. If previous sibling is collapsed or has no children, go to that sibling
   4. If no previous sibling, go to parent"
  [db node-id & {:keys [respect-collapsed?] :or {respect-collapsed? true}}]
  (let [collapsed (get-in db [:view :collapsed] #{})
        children-by-parent (:children-by-parent db)]
    (letfn [(get-deepest-last-child [id]
              (let [children (get children-by-parent id [])]
                (if (and (seq children)
                         (or (not respect-collapsed?)
                             (not (contains? collapsed id))))
                  (get-deepest-last-child (last children))
                  id)))]
      (let [pos (node-position db node-id)]
        (when pos
          (let [parent-id (:parent pos)
                siblings (:children pos)
                current-idx (:index pos)]
            (cond
              ;; Has previous sibling
              (> current-idx 0)
              (let [prev-sibling (nth siblings (dec current-idx))]
                (get-deepest-last-child prev-sibling))

              ;; No previous sibling, go to parent (unless it's root)
              (not= parent-id "root")
              parent-id

              ;; At root level, no previous
              :else
              nil)))))))

(defn get-first-child
  "Get the first child of a node, if any"
  [db node-id]
  (first (get-in db [:children-by-parent node-id])))

(defn get-last-child
  "Get the last immediate child of a node, if any"
  [db node-id]
  (last (get-in db [:children-by-parent node-id])))

(defn get-parent
  "Get the parent of a node"
  [db node-id]
  (find-parent (:children-by-parent db) node-id))

(defn get-block-parents
  "Get the entire chain of ancestors for a given block"
  [db node-id]
  (loop [current-id node-id
         parents []]
    (let [parent-id (get-parent db current-id)]
      (if (or (nil? parent-id) (= parent-id "root"))
        parents
        (recur parent-id (conj parents parent-id))))))
