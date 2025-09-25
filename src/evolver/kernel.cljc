(ns evolver.kernel
   (:require [clojure.set :as set]
             [evolver.schemas :as schemas]))

(declare insert-node move-node patch-node delete-node reorder-node undo-last-operation redo-last-operation initial-db-base)

;; Derive ALL metadata after ANY operation:
(defn derive-tree-metadata
  "Return {:depth {id depth} :paths {id [parent ...]}} from a :children-by-parent adjacency map."
  [{:keys [children-by-parent nodes]}]
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
        level-priority {:debug 0 :info 1 :warn 2 :error 3}
        should-log (>= (level-priority level 0) (level-priority current-level 1))]
    (if should-log
      (let [timestamp #?(:clj (System/currentTimeMillis)
                         :cljs (.now js/Date))
            log-entry {:level level
                       :message message
                       :timestamp timestamp
                       :data data}]
        (update db :log-history conj log-entry))
      db)))

(defn safe-apply-command
  "Apply command with validation and error handling"
  [db command]
  (try
    (log-message db :debug (str "Applying command: " (:op command)) command)
    (let [new-db (case (:op command)
                   :insert (insert-node db command)
                   :move (move-node db command)
                   :patch (patch-node db command)
                   :delete (delete-node db command)
                   :reorder (reorder-node db command)
                   :undo (undo-last-operation db)
                   :redo (redo-last-operation db)
                   (do (log-message db :warn (str "Unknown command op: " (:op command)))
                       db))]
      (validate-db-state new-db)
      ;; Don't log undo/redo operations themselves
      (if (#{:undo :redo} (:op command))
        new-db
        (log-operation new-db command)))
    (catch #?(:clj Exception :cljs js/Error) e
      (let [error-db (try
                       (log-message db :error (str "Command failed: " (:op command)) {:error (ex-message e) :command command})
                       (catch #?(:clj Exception :cljs js/Error) _ db))] ; If logging fails, return original db
        (throw (ex-info "Command execution failed" {:error (ex-message e) :command command} e))))))

(defn undo-last-operation
  "Undo the last operation by inverting it"
  [db]
  (if-let [last-tx (last (:tx-log db))]
    (let [tx-log (pop (:tx-log db))
          undo-stack (conj (or (:undo-stack db) []) last-tx)
          _ (println "UNDO: undo-stack =" undo-stack)
          inverted-db (case (:op last-tx)
                        :insert (let [args (:args last-tx)]
                                  (delete-node db {:node-id (:node-id args) :recursive true}))
                        :delete (let [args (:args last-tx)]
                                  ;; For delete, we'd need to store the deleted data - for now, just log
                                  (log-message db :warn "Delete undo not fully implemented")
                                  db)
                        :move (let [args (:args last-tx)]
                                ;; Move back to original position - this is complex, for now just log
                                (log-message db :warn "Move undo not fully implemented")
                                db)
                        :patch (let [args (:args last-tx)]
                                 ;; For patch, we'd need to store old values - for now, just log
                                 (log-message db :warn "Patch undo not fully implemented")
                                 db)
                        :reorder (let [args (:args last-tx)]
                                   ;; For reorder, swap from/to indices
                                   (reorder-node db (assoc args :to-index (:from-index args) :from-index (:to-index args))))
                        db)
          inverted-db (assoc inverted-db :undo-stack undo-stack)]
      (let [final-db (-> inverted-db
                         (assoc :tx-log tx-log)
                         (assoc :undo-stack undo-stack)
                         (update-derived))]
        (log-message final-db :info "Undid last operation" last-tx)))
    db))

(defn redo-last-operation
  "Redo the last undone operation"
  [db]
  (if-let [last-undone-tx (last (:undo-stack db))]
    (let [undo-stack (pop (:undo-stack db))
          tx-log (conj (or (:tx-log db) []) last-undone-tx)
          redone-db (let [cmd (assoc (:args last-undone-tx) :op (:op last-undone-tx))]
                      (case (:op last-undone-tx)
                        :insert (insert-node db cmd)
                        :delete (delete-node db cmd)
                        :move (move-node db cmd)
                        :patch (patch-node db cmd)
                        :reorder (reorder-node db cmd)
                        db))
          redone-db (assoc redone-db :undo-stack undo-stack)]
      (let [final-db (-> redone-db
                         (assoc :tx-log tx-log)
                         (assoc :undo-stack undo-stack)
                         (update-derived))]
        (log-message final-db :info "Redid last operation" last-undone-tx)))
    db))

(defn node-position [db node-id]
  (when-let [parent (find-parent (:children-by-parent db) node-id)]
    (let [children (get (:children-by-parent db) parent [])
          idx (.indexOf children node-id)]
      {:parent parent :index idx :children children})))

(def initial-db-base
  {:nodes
   {"root" {:type :div}
    "title" {:type :h1, :props {:text "Declarative Components, Procedural Styles"}}
    "p1-select" {:type :p, :props {:text "This paragraph is selected. Click to deselect."
                                   :on/click [[:toggle-selection {:target-id "p1-select"}]]}}
    "p2-high" {:type :p, :props {:text "This is highlighted but NOT selected. No style should apply."}}
    "p3-both" {:type :p, :props {:text "This is selected AND highlighted. Click to deselect."
                                 :on/click [[:toggle-selection {:target-id "p3-both"}]]}}

    "div1" {:type :div, :props {:text "This is a div containing a paragraph."}}
    "p4-click" {:type :p, :props {:text "Click this paragraph to select it."
                                  :on/click [[:toggle-selection {:target-id "p4-click"}]]}}}

   :children-by-parent
   {"root" ["title" "p1-select" "p2-high" "p3-both" "div1"]
    "div1" ["p4-click"]}

   :view
   {:selected #{"p1-select"} ; sets ARE the index
    :highlighted #{"p2-high"}
    :collapsed #{"p4-click"}}

    :tx-log []
    :undo-stack []
   :log-level :info
   :log-history []})

(def db (update-derived initial-db-base))

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

(defn delete-node [db {:keys [node-id recursive]}]
  (let [descendants (set (tree-seq #(get-in db [:children-by-parent %])
                                   #(get-in db [:children-by-parent %])
                                   node-id))
        parent (find-parent (:children-by-parent db) node-id)
        new-db (-> db
                   (update :nodes #(apply dissoc % descendants))
                   (update :children-by-parent #(-> %
                                                    (update parent (fn [ch] (vec (remove #{node-id} ch))))
                                                    ((fn [m] (apply dissoc m descendants)))))
                   (update :view #(into {} (map (fn [[k v]] [k (set/difference v descendants)]) %))))]
    (update-derived new-db)))

(defn reorder-node [db {:keys [node-id parent-id from-index to-index]}]
  (let [siblings (get (:children-by-parent db) parent-id [])
        siblings-without-node (vec (remove #{node-id} siblings))
        new-siblings (vec (concat (take to-index siblings-without-node)
                                  [node-id]
                                  (drop to-index siblings-without-node)))]
    (update-derived (assoc-in db [:children-by-parent parent-id] new-siblings))))

(defn apply-command [db command]
   (safe-apply-command db command))

;; Structural editor operations

(defn current-node-id [db]
  (first (:selected (:view db))))

(defn gen-new-id []
  (str "node-" (rand-int 10000)))

(defn create-child-block [db]
  "Create a new block as child of current node"
  (let [current (current-node-id db)
        new-id (gen-new-id)
        new-node {:type :div :props {:text (str "Child of " current)}}]
    (insert-node db {:parent-id current :node-id new-id :node-data new-node :position nil})))

(defn create-sibling-above [db]
  "Create a new sibling above the current node"
  (let [current (current-node-id db)
        {:keys [parent index]} (node-position db current)
        new-id (gen-new-id)
        new-node {:type :div :props {:text (str "Sibling above " current)}}]
    (insert-node db {:parent-id parent :node-id new-id :node-data new-node :position index})))

(defn create-sibling-below [db]
  "Create a new sibling below the current node"
  (let [current (current-node-id db)
        {:keys [parent index]} (node-position db current)
        new-id (gen-new-id)
        new-node {:type :div :props {:text (str "Sibling below " current)}}]
    (insert-node db {:parent-id parent :node-id new-id :node-data new-node :position (inc index)})))

(defn indent [db]
  "Move current block under the previous sibling (make it a child)"
  (let [current (current-node-id db)
        {:keys [parent index children]} (node-position db current)]
    (if (> index 0)
      (let [prev-sib (get children (dec index))]
        (move-node db {:node-id current :new-parent-id prev-sib :position nil}))
      db)))

(defn outdent [db]
  "Move current block up one level (promote)"
  (let [current (current-node-id db)
        {:keys [parent]} (node-position db current)]
    (if (not= parent "root")
      (let [grandparent-pos (node-position db parent)
            grandparent (:parent grandparent-pos)
            parent-idx (:index grandparent-pos)]
        (move-node db {:node-id current :new-parent-id grandparent :position {:type :after :sibling-id parent}}))
      db)))
