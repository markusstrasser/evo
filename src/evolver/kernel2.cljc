(ns evolver.kernel2
  (:require [clojure.set :as set]))

;; Derive ALL metadata after ANY operation:
(defn derive-tree-metadata
  "Return {:depth {id depth} :paths {id [parent ...]}} from a :children-by-parent adjacency map."
  [{:keys [children-by-parent nodes]}]
  (letfn
    [(walker [node-id depth path acc]
       (let [node-children (get children-by-parent node-id []) ;;lookup node in the children-by-parent map; if it’s not there, use []

             acc (-> acc
                     (assoc-in [:depth node-id] depth)
                     (assoc-in [:paths node-id] path))]

         (reduce
           (fn [acc child] (walker child (inc depth) (conj path node-id) acc))
           acc
           node-children)))]
    (walker "root" 0 [] {})))

(def db
  (let [base {:nodes
              {"root"      {:type :div}
               "title"     {:type :h1, :props {:text "Declarative Components, Procedural Styles"}}
               "p1-select" {:type :p, :props {:text     "This paragraph is selected. Click to deselect."
                                              :on/click [[:toggle-selection {:target-id "p1-select"}]]}}
               "p2-high"   {:type :p, :props {:text "This is highlighted but NOT selected. No style should apply."}}
               "p3-both"   {:type :p, :props {:text     "This is selected AND highlighted. Click to deselect."
                                              :on/click [[:toggle-selection {:target-id "p3-both"}]]}}

               "div1"      {:type :div, :props {:text "This is a div containing a paragraph."}}
               "p4-click"  {:type :p, :props {:text     "Click this paragraph to select it."
                                              :on/click [[:toggle-selection {:target-id "p4-click"}]]}}}

              :children-by-parent
              {"root" ["title" "p1-select" "p2-high" "p3-both" "div1"]
               "div1" ["p4-click"]}

              :view
              {:selected    #{"p1-select"}                             ; sets ARE the index
               :highlighted #{"p2-high"}
               :collapsed   #{"p4-click"}}}]
    (assoc base :derived (derive-tree-metadata base))))


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
    (assoc new-db :derived (derive-tree-metadata new-db))))

(defn patch-node [db {:keys [node-id updates]}]
  (let [new-db (update-in db [:nodes node-id] merge updates)]
    (assoc new-db :derived (derive-tree-metadata new-db))))



(defn move-node [db {:keys [node-id new-parent-id position]}]
  (let [old-parent (some (fn [[parent children]]
                           (when (some #{node-id} children) parent))
                         (:children-by-parent db))
        new-db (-> db
                   (update-in [:children-by-parent old-parent] #(vec (remove #{node-id} %)))
                   (update-in [:children-by-parent new-parent-id]
                              #(insert-at-position % node-id position)))]
    (assoc new-db :derived (derive-tree-metadata new-db))))

(defn delete-node [db {:keys [node-id recursive]}]
  (let [descendants (set (tree-seq #(get-in db [:children-by-parent %])
                                   #(get-in db [:children-by-parent %])
                                   node-id))
        parent (some (fn [[p children]] (when (some #{node-id} children) p))
                     (:children-by-parent db))
        new-db (-> db
                   (update :nodes #(apply dissoc % descendants))
                   (update :children-by-parent #(-> %
                                                    (update parent (fn [ch] (vec (remove #{node-id} ch))))
                                                    ((fn [m] (apply dissoc m descendants)))))
                   (update :view #(into {} (map (fn [[k v]] [k (set/difference v descendants)]) %))))]
    (assoc new-db :derived (derive-tree-metadata new-db))))


(defn apply-command [db command]
  (case (:op command)
    :insert (insert-node db command)
    :move (move-node db command)
    :patch (patch-node db command)
    :delete (delete-node db command)
    db))

;; Use like:
(-> db
    (apply-command {:op :insert :parent-id "root" :node-id "new-p" :node-data {:type :p :props {:text "Hello"}} :position 1})
    (apply-command {:op :move :node-id "p1-select" :new-parent-id "div1"})
    (apply-command {:op :patch :node-id "new-p" :updates {:props {:text "Updated"}}})
    (apply-command {:op :delete :node-id "p2-high" :recursive true}))