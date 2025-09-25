(ns evolver.renderer
  (:require [evolver.kernel :as kernel]))

(defn render-node [db node-id]
  (let [node (get-in db [:nodes node-id])
        children (get-in db [:children-by-parent node-id] [])
        selected? (contains? (get-in db [:view :selected]) node-id)
        collapsed? (contains? (get-in db [:view :collapsed]) node-id)
        node-type (:type node)]
    (into [(if (keyword? node-type)
             node-type
             (keyword (str node-type)))
           {:on {:click [[:select-node {:node-id node-id}]]}
            :class (cond-> ""
                     selected? (str " selected")
                     collapsed? (str " collapsed"))}
           (or (:text (:props node)) "")]
          (when-not collapsed?
            (map #(render-node db %) children)))))

(defn render-ops-dropdown [selected-op]
  [:select {:value (if selected-op (name selected-op) "")
            :on {:change [[:set-selected-op]]}}
   [:option {:value ""} "Select operation"]
   [:option {:value "create-child-block"} "Create child block"]
   [:option {:value "create-sibling-above"} "Create sibling above"]
   [:option {:value "create-sibling-below"} "Create sibling below"]
   [:option {:value "indent"} "Indent"]
   [:option {:value "outdent"} "Outdent"]])

(defn render [db]
  [:div {:class "app"}
   [:h1 "Tree Editor"]
   [:div {:class "tree"}
    (render-node db "root")]
   [:div {:class "controls"}
    (render-ops-dropdown (:selected-op db))
    [:button {:on {:click [[:apply-selected-op]]}} "Apply Op"]]])