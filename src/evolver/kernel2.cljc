(ns evolver.kernel2
  (:require [clojure.set :as set]))

(def db
  {:nodes
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
   {"root" ["title" "p1" "div1"]
    "div1" ["p4-click"]}

   :view
   {:selected    #{"p1-select"}                             ; sets ARE the index
    :highlighted #{"p2-high"}
    :collapsed   #{"p4-click"}}

   ; if you’ll have split panes: :views {view-id {:selected #{…} …}}

   :derived
   {:depth {}

    :paths {}                                               ;;ie. ancestors
    }})


(defn insert-at
  [v thing position]
  (let [v (or v [])]
    (if (some? position)
      (vec (concat (take position v) [thing] (drop position v)))
      (conj (vec v) thing))))


(defn insert-node [db parent-id node-id node-data & [position]]
  (-> db
      (assoc-in [:nodes node-id] node-data)
      (update-in [:children-by-parent parent-id]
                 #(insert-at % node-id position))
      ))



(defn move-node [db node-id new-parent-id & [position]]
  (let [old-parent (some (fn [[parent children]]
                           (when (some #{node-id} children) parent))
                         (:children-by-parent db))]
    (-> db
        (update-in [:children-by-parent old-parent] #(vec (remove #{node-id} %)))
        (update-in [:children-by-parent new-parent-id]
                   #(insert-at % node-id position))
        )))

(defn delete-node-with-descendants [db node-id]
  (let [descendants (set (tree-seq #(get-in db [:children-by-parent %])
                                   #(get-in db [:children-by-parent %])
                                   node-id))
        parent (some (fn [[p children]] (when (some #{node-id} children) p))
                     (:children-by-parent db))]
    (-> db
        (update :nodes #(apply dissoc % descendants))
        (update :children-by-parent #(-> %
                                         (update parent (fn [ch] (vec (remove #{node-id} ch))))
                                         (apply dissoc descendants)))
        (update :view update-vals #(set/difference % descendants)))))

;; Rebuild ALL derived data after ANY operation:
(defn rebuild-derived
  "Return {:depth {id depth} :paths {id [parent ...]}} from a :children adjacency map."
  [{:keys [children-by-parent nodes]}]
  (letfn
    [(walker [node-id depth path acc]
       (let [node-children (get children-by-parent node-id []) ;;lookup node in the children map; if it’s not there, use []

             acc (-> acc
                     (assoc-in [:depth node-id] depth)
                     (assoc-in [:paths node-id] path))]

         (reduce
           (fn [acc child] (walker child (inc depth) (conj path node-id) acc))
           acc
           node-children)))]
    (walker "root" 0 [] {})))
;; Use like:
(-> db
    (insert-node "root" "new-p" {:type :p :text "Hello"} 1) ; at position 1
    (move-node "p1-select" "div1")
    (delete-node-with-descendants "p2-high")
    rebuild-all)