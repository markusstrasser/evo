(ns evolver.kernel
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
   {:depth    {}

    :paths    {}                                            ;;ie. ancestors
    :refs-out {}                                            ;; refs-in, refs-out only for easy show
    :refs-in  {}}
   })

;; derivation vs transpiling of [[Some Page]] into an actual Link-Component(PagePath)?
;; But then it changes source truth ... I guess that split should happen at ...

;; Cleanup-operation during Post-WALK hook
;; into a block-segment(ui as one block but has children)

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


(defn get-descendants [children-by-parent node-id]
  (tree-seq
    #(contains? children-by-parent %)
    #(get children-by-parent % [])
    node-id))

(defn remove-from-parent [children-by-parent node-id]
  (into {}
        (map (fn [[parent children]]
               [parent (remove #{node-id} children)]))
        children-by-parent))

(defn delete-subtree [db node-id]
  (let [to-delete (set (get-descendants (:children-by-parent db) node-id))]
    (-> db
        (update :nodes #(apply dissoc % to-delete))
        (update :children-by-parent
                #(as-> % m
                       (remove-from-parent m node-id)
                       (apply dissoc m to-delete)))
        (update :view update-vals #(set/difference % to-delete)))))


(delete-subtree db "div1")

(defn delete-node [db node-id]
  ;;delete the node
  (dissoc db :nodes node-id)
  (dissoc (:nodes db) node-id)
  ()
  ;;delete children-refs
  ;;delete views

  )