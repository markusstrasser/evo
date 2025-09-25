(ns evolver.kernel)
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
    "div1" ["p4"]}

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


(def derived-state (rebuild-derived db))

(defn id:level [conn])
"arrow up/down"
(defn move-vertical [])
"shift-tab ..."
(defn move-up [])
"tab"
(defn move-down [])