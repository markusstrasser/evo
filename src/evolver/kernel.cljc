(ns evolver.kernel
  (:require [clojure.set :as set]
            [malli.core :as m]
            [malli.error :as me]
            [evolver.schemas :as schemas]
            [evolver.constants :as constants]
            [clojure.core.match :refer [match]]))

(declare insert-node move-node patch-node delete-node reorder-node undo-last-operation redo-last-operation add-reference remove-reference initial-db-base)

;; Constants
(def root-id "root")

;; Derive ALL metadata after ANY operation:
(defn derive-tree-metadata
  "Return {:depth {id depth} :paths {id [parent ...]} :parent-of {id parent}} from a :children-by-parent adjacency map."
  [{:keys [children-by-parent nodes]}]
  (letfn [(walker [node-id depth path acc visited]
            ;; Prevent infinite loops by checking if we've visited this node in this path
            (if (contains? visited node-id)
              acc ; Skip cycles
              (let [node-children (get children-by-parent node-id [])
                    new-visited (conj visited node-id)
                    acc (-> acc
                            (assoc-in [:depth node-id] depth)
                            (assoc-in [:paths node-id] path)
                            (update :parent-of into (map (fn [c] [c node-id]) node-children)))]
                (reduce
                 (fn [acc child] (walker child (inc depth) (conj path node-id) acc new-visited))
                 acc
                 node-children))))]
    (let [reachable-metadata (walker root-id 0 [] {:depth {} :paths {} :parent-of {}} #{})
          all-node-ids (set (keys nodes))
          reachable-ids (set (keys (:depth reachable-metadata)))
          orphaned-ids (set/difference all-node-ids reachable-ids)]
      ;; Add orphaned nodes with nil depth/path to indicate they're disconnected
      (reduce (fn [acc orphaned-id]
                (-> acc
                    (assoc-in [:depth orphaned-id] nil)
                    (assoc-in [:paths orphaned-id] nil)))
              reachable-metadata
              orphaned-ids))))

(defn find-parent [children-by-parent node-id]
  (some (fn [[p children]] (when (some #{node-id} children) p)) children-by-parent))

(defn parent-of
  "O(1) parent lookup using derived data"
  [db id]
  (get-in db [:derived :parent-of id]))

(defn update-derived [db]
  (assoc db :derived (derive-tree-metadata db)))

(defn validate-db-state
  "Validate db state and throw if invalid"
  [db]
  (schemas/validate-db db))

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

(defn apply-step
  "Applies a single command and immediately updates derived data. The core of transactional safety."
  [db command]
  (let [db-with-derived (if (:derived db) db (update-derived db))
        new-db-base (apply-command db-with-derived command)]
    (update-derived new-db-base)))

(defn apply-transaction
  "Applies a vector of commands, ensuring derived state is valid between each step."
  [db commands]
  (reduce apply-step db commands))

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

(defmethod apply-command :transaction [db command]
  (apply-transaction db (:commands command)))

(defmethod apply-command :update-view [db command]
  (let [{:keys [path value]} command]
    (assoc-in db (cons :view path) value)))

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
      ;; Validate db state after operation using derive-tree-metadata for structural validation
      (derive-tree-metadata new-db) ; This will throw if invalid
      ;; Also validate against schema
      (when-not (m/validate schemas/db-schema new-db)
        (throw (ex-info "Invalid database state after command"
                        {:errors (me/humanize (m/explain schemas/db-schema new-db))
                         :command command
                         :before-db db})))
      new-db)
    (catch #?(:clj Exception :cljs :default) e
      (try
        (log-message db :error (str "Command failed: " (:op command)) {:error (ex-message e) :command command})
        (catch #?(:clj Exception :cljs :default) _ nil)) ; If logging fails, ignore
      (throw (ex-info "Command execution failed" {:error (ex-message e) :command command} e)))))

(defn node-position [db node-id]
  (when-let [parent (parent-of db node-id)]
    (let [children (get-in db [:children-by-parent parent] [])
          idx #?(:clj (.indexOf ^java.util.List children node-id)
                 :cljs (.indexOf children node-id))]
      {:parent parent :index idx :children children})))

(def db (update-derived constants/initial-db-base))

(defn insert-at-position
  "Inserts an item into a vector based on a tagged-union position spec."
  [coll item position-spec]
  (let [v (vec (remove #{item} (or coll [])))]
    (match position-spec
      [:index i]
      (if (and (number? i) (<= 0 i (count v)))
        (vec (concat (subvec v 0 i) [item] (subvec v i)))
        (conj v item)) ; Append if index is invalid

      [:after sibling-id]
      (let [idx (.indexOf v sibling-id)]
        (if (neg? idx)
          (conj v item) ; Append if sibling not found
          (vec (concat (subvec v 0 (inc idx)) [item] (subvec v (inc idx))))))

      [:before sibling-id]
      (let [idx (.indexOf v sibling-id)]
        (if (neg? idx)
          (vec (cons item v)) ; Prepend if sibling not found
          (vec (concat (subvec v 0 idx) [item] (subvec v idx)))))

      [:first] (vec (cons item v))
      [:last]  (conj v item)
      :else    (conj v item)))) ; Default to appending

(defn insert-node [db {:keys [parent-id node-id node-data at-position]}]
  (schemas/validate-node node-data) ; Validate node data
  (let [new-db (-> db
                   (assoc-in [:nodes node-id] node-data)
                   (update-in [:children-by-parent parent-id]
                              #(insert-at-position % node-id at-position)))]
    (if (and (not= (:type (get-in new-db [:nodes parent-id])) :div)
             (seq (get-in new-db [:children-by-parent parent-id])))
      (assoc-in new-db [:nodes parent-id :type] :div)
      new-db)))

(defn is-ancestor?
  "Checks if potential-ancestor-id is an ancestor of node-id."
  [db node-id potential-ancestor-id]
  (let [path (get-in db [:derived :paths node-id] [])]
    (boolean (some #{potential-ancestor-id} path))))

(defn patch-node [db {:keys [node-id updates]}]
  (update-in db [:nodes node-id]
             (fn [node]
               (merge-with (fn [old new] (if (and (map? old) (map? new)) (merge old new) new))
                           node
                           updates))))

(defn move-node [db {:keys [node-id new-parent-id at-position]}]
  (if (or (= node-id new-parent-id) (is-ancestor? db new-parent-id node-id))
    (log-message db :warn "Invalid move: cannot move a node under itself or its descendants.")
    (let [old-parent (parent-of db node-id)
          new-db (-> db
                     ;; Remove from old parent
                     (update-in [:children-by-parent old-parent] #(vec (remove #{node-id} %)))
                     ;; Also remove from new parent if it's already there (prevents duplicates)
                     (update-in [:children-by-parent new-parent-id] #(vec (remove #{node-id} %)))
                     ;; Add to new parent at specified position
                     (update-in [:children-by-parent new-parent-id]
                                #(insert-at-position % node-id at-position)))
          ;; Convert new parent to div if it's not already and now has children
          final-db (if (and (not= (:type (get-in new-db [:nodes new-parent-id])) :div)
                            (seq (get-in new-db [:children-by-parent new-parent-id])))
                     (update-in new-db [:nodes new-parent-id] assoc :type :div)
                     new-db)]
      final-db)))

(defn delete-node [db {:keys [node-id]}]
  (if (= node-id root-id)
    (log-message db :warn "Cannot delete the root node.")
    (if-let [parent (parent-of db node-id)]
      (if-let [idx (-> (node-position db node-id) :index)]
        (let [siblings (get-in db [:children-by-parent parent])
              kids     (get-in db [:children-by-parent node-id] [])
              new-sibs (vec (concat (subvec siblings 0 idx) kids (subvec siblings (inc idx))))]
          (-> db
              (assoc-in [:children-by-parent parent] new-sibs)
              (update :children-by-parent dissoc node-id)
              (update :nodes dissoc node-id)
              (update-in [:view :selection] #(vec (remove #{node-id} %)))
              (update-in [:view :collapsed] (fnil disj #{}) node-id)
              (update :references dissoc node-id)))
        db) ; Node not found in siblings, do nothing
      db))) ; Node has no parent, do nothing

(defn reorder-node [db {:keys [node-id parent-id to-index]}]
  (let [actual (parent-of db node-id)]
    (when (not= actual parent-id)
      (throw (ex-info "reorder-node only reorders within a parent; use move-node"
                      {:node-id node-id :arg-parent parent-id :actual-parent actual})))
    (let [siblings (get-in db [:children-by-parent parent-id] [])
          without (vec (remove #{node-id} siblings))
          new (vec (concat (take to-index without) [node-id] (drop to-index without)))]
      (assoc-in db [:children-by-parent parent-id] new))))

;; NOTE: execute-command is redundant - use apply-command directly or go through middleware/state pipeline

;; Structural editor operations

(defn current-node-id [db]
  (first (:selected (:view db))))

;; Monotonic ID generation for better debugging
(defonce !id-counter (atom 0))
(defn gen-new-id []
  (str "node-" (swap! !id-counter inc)))

(defn add-reference
  "Adds a reference from one node to another"
  {:malli/schema [:=> [:cat map? map?] map?]}
  [db {:keys [from-node-id to-node-id]}]
  (if (and (contains? (:nodes db) from-node-id)
           (contains? (:nodes db) to-node-id))
    (update-in db [:references to-node-id] (fnil conj #{}) from-node-id)
    (throw (ex-info "Cannot add reference: nodes do not exist"
                    {:from-node-id from-node-id
                     :to-node-id to-node-id
                     :existing-nodes (keys (:nodes db))}))))

(defn remove-reference
  "Removes a reference from one node to another"
  {:malli/schema [:=> [:cat map? map?] map?]}
  [db {:keys [from-node-id to-node-id]}]
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
                (when (not= parent-id root-id)
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
              (not= parent-id root-id)
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
  (parent-of db node-id))

(defn get-block-parents
  "Get the entire chain of ancestors for a given block"
  [db node-id]
  (loop [current-id node-id
         parents []]
    (let [parent-id (get-parent db current-id)]
      (if (or (nil? parent-id) (= parent-id root-id))
        parents
        (recur parent-id (conj parents parent-id))))))

;; === SELECTION UTILITIES ===

(defn selected?
  "Check if a node is currently selected"
  [db node-id]
  (let [selection (get-in db [:view :selection] [])]
    (some #(= % node-id) selection)))

(defn node-exists?
  "Check if a node exists in the database"
  [db node-id]
  (contains? (:nodes db) node-id))

(defn toggle-selection
  "Toggle selection state of a node"
  [db node-id]
  (let [current-selection (get-in db [:view :selection] [])
        is-selected (some #(= % node-id) current-selection)]
    (if is-selected
      ;; Remove from selection
      (assoc-in db [:view :selection]
                (vec (remove #(= % node-id) current-selection)))
      ;; Add to selection
      (assoc-in db [:view :selection]
                (conj current-selection node-id)))))
