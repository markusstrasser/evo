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
           {:replicant/key node-id
            :class (cond-> [:node]
                      selected? (conj :selected)
                      collapsed? (conj :collapsed))
            :on (when-not (= node-id "root")  ;; Don't make root clickable
                  {:click [[:select-node {:node-id node-id}]]})}
           (or (:text (:props node)) (str node-id))]
          (when-not collapsed?
            (map #(render-node db %) children)))))

(defn render-ops-dropdown [selected-op]
  [:select {:value (if selected-op (name selected-op) "")
            :on {:change [[:set-selected-op]]}}
   [:option {:replicant/key "none" :value ""} "Select operation"]
   [:option {:replicant/key "child" :value "create-child-block"} "Create child block"]
   [:option {:replicant/key "above" :value "create-sibling-above"} "Create sibling above"]
   [:option {:replicant/key "below" :value "create-sibling-below"} "Create sibling below"]
   [:option {:replicant/key "indent" :value "indent"} "Indent"]
   [:option {:replicant/key "outdent" :value "outdent"} "Outdent"]])

(defn render-log-history [log-history]
   (when (seq log-history)
     [:div {:class [:log-history]}
      [:h3 "Recent Logs"]
      [:div {:class [:log-entries]}
       (for [entry (take-last 5 log-history)]
         [:div {:class [:log-entry (:level entry)]}
          [:span {:class [:log-timestamp]} (str (js/Date. (:timestamp entry)))]
          [:span {:class [:log-level]} (name (:level entry))]
          [:span {:class [:log-message]} (:message entry)]])]]))

(defn render-error-boundary [db]
   (when-let [error-log (some #(when (= (:level %) :error) %) (:log-history db))]
     [:div {:class [:error-boundary]}
      [:h3 "⚠️ Error Occurred"]
      [:p (:message error-log)]
      [:details
       [:summary "Error Details"]
       [:pre (pr-str (:data error-log))]]]))

(defn render-hotkeys-footer []
  [:footer {:class [:hotkeys-footer]}
   [:h4 "Keyboard Shortcuts"]
   [:div {:class [:hotkeys-grid]}
    [:div {:class [:hotkey-item]}
     [:kbd "Enter"] [:span "Create child block"]]
    [:div {:class [:hotkey-item]}
     [:kbd "Shift"] [:kbd "Enter"] [:span "Create sibling above"]]
    [:div {:class [:hotkey-item]}
     [:kbd "Enter"] [:span "(at end of line) Create sibling below"]]
    [:div {:class [:hotkey-item]}
     [:kbd "Tab"] [:span "Indent (make child)"]]
    [:div {:class [:hotkey-item]}
     [:kbd "Shift"] [:kbd "Tab"] [:span "Outdent (promote)"]]
    [:div {:class [:hotkey-item]}
     [:kbd "Alt"] [:kbd "Shift"] [:kbd "↑↓"] [:span "Move block up/down"]]
    [:div {:class [:hotkey-item]}
     [:kbd "Cmd"] [:kbd "."] [:span "Toggle collapse/expand"]]]])

(defn render [db]
   [:div {:class [:app]}
    [:h1 "Tree Editor"]
    (render-error-boundary db)
    [:div {:class [:tree]}
     (render-node db "root")]
    [:div {:class [:controls]}
     (render-ops-dropdown (:selected-op db))
     [:button {:on {:click [[:apply-selected-op]]}} "Apply Op"]
     [:button {:on {:click [[:undo]]}} "Undo"]
     [:button {:on {:click [[:redo]]}} "Redo"]]
    (render-log-history (:log-history db))
    (render-hotkeys-footer)])