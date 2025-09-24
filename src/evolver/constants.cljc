(ns evolver.constants
   "Constants and initial state for the evolver system.")

;; Operation types
(def operation-types
  "Supported operation types"
  #{:insert :move :patch :delete :reorder :add-reference :remove-reference :undo :redo})

;; Log levels with priorities
(def log-levels
  "Available log levels in priority order"
  {:debug 0 :info 1 :warn 2 :error 3})

;; Initial database state
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
    {:selected #{"p1-select"}
     :highlighted #{"p2-high"}
     :collapsed #{"p4-click"}
     :hovered-referencers #{}}

    :references {"p1-select" #{"title"}} ; node-id -> set of referencing nodes

    :history []
    :history-index 0
    :log-level :info
    :log-history []})