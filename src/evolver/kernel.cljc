(ns evolver.kernel
  (:require [clojure.set :as set]))

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

(defn node-position [db node-id]
  (when-let [parent (find-parent (:children-by-parent db) node-id)]
    (let [children (get (:children-by-parent db) parent [])
          idx (.indexOf children node-id)]
      {:parent parent :index idx :children children})))

(def db
  (let [base {:nodes
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
               :collapsed #{"p4-click"}}}]
    (update-derived base)))

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

(defn apply-command [db command]
  (case (:op command)
    :insert (insert-node db command)
    :move (move-node db command)
    :patch (patch-node db command)
    :delete (delete-node db command)
    db))

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

;; Light tests

;; Test create-child-block
(let [db-with-child (create-child-block db)
      current (current-node-id db)
      children (get (:children-by-parent db-with-child) current)]
  (assert (= 1 (count children)) "Should have one child")
  (assert (string? (first children)) "Child id should be string"))

;; Test create-sibling-above
(let [db-with-sib (create-sibling-above db)
      parent "root"
      children (get (:children-by-parent db-with-sib) parent)
      current-idx (.indexOf children (current-node-id db))]
  (assert (= 6 (count children)) "Should have 6 children")
  (assert (= 2 current-idx) "Current should be at index 2"))

;; Test indent (assuming current is not first)
(let [db-indented (indent db)
      current (current-node-id db)
      new-parent (find-parent (:children-by-parent db-indented) current)]
  (assert (= "title" new-parent) "Should be child of title"))

;; Test outdent (current is child of div1)
(let [db-outdented (-> db
                       (assoc-in [:view :selected] #{"p4-click"})
                       outdent)
      current "p4-click"
      new-parent (find-parent (:children-by-parent db-outdented) current)]
  (assert (= "root" new-parent) "Should be child of root"))

;; Use like:
(-> db
    (apply-command {:op :insert :parent-id "root" :node-id "new-p" :node-data {:type :p :props {:text "Hello"}} :position 1})
    (apply-command {:op :move :node-id "p1-select" :new-parent-id "div1"})
    (apply-command {:op :patch :node-id "new-p" :updates {:props {:text "Updated"}}})
    (apply-command {:op :delete :node-id "p2-high" :recursive true}))