(ns evolver.renderer
  (:require [evolver.kernel :as kernel]))

(defn render-references [db node-id]
  "Render a references section for a node that has references"
  (let [references (kernel/get-references db node-id)]
    (when (seq references)
      [:div {:class [:references-section]}
       [:div {:class [:references-header]} "References"]
       (for [ref-id references]
         [:div {:class [:reference-item]
                :on {:click [[:select-node {:node-id ref-id}]]}}
          (or (:text (:props (get-in db [:nodes ref-id]))) ref-id)])])))

(defn render-node [db node-id]
  (let [node (get-in db [:nodes node-id])
        children (get-in db [:children-by-parent node-id] [])
        selected? (contains? (get-in db [:view :selected]) node-id)
        collapsed? (contains? (get-in db [:view :collapsed]) node-id)
        referenced? (contains? (get-in db [:computed :referenced-nodes]) node-id)
        hovered-referencers (get-in db [:view :hovered-referencers] #{})
        is-referencer-highlighted? (contains? hovered-referencers node-id)
        node-type (:type node)]
    (into [(if (keyword? node-type)
             node-type
             (keyword (str node-type)))
           {:replicant/key node-id
            :class (cond-> [:node]
                     selected? (conj :selected)
                     collapsed? (conj :collapsed)
                     referenced? (conj :referenced)
                     is-referencer-highlighted? (conj :referencer-highlighted))
            :on (when-not (= node-id "root")
                  {:click [[:select-node {:node-id node-id}]]
                   :mouseenter [[:hover-node {:node-id node-id}]]
                   :mouseleave [[:unhover-node {:node-id node-id}]]})}
           (or (:text (:props node)) (str node-id))]
          (when-not collapsed?
            (cond-> (map #(render-node db %) children)
              referenced? (conj (render-references db node-id)))))))

(defn render-ops-dropdown [selected-op]
  [:select {:value (if selected-op (name selected-op) "")
            :on {:change [[:set-selected-op]]}}
   [:option {:replicant/key "none" :value ""} "Select operation"]
   [:option {:replicant/key "child" :value "create-child-block"} "Create child block"]
   [:option {:replicant/key "above" :value "create-sibling-above"} "Create sibling above"]
   [:option {:replicant/key "below" :value "create-sibling-below"} "Create sibling below"]
   [:option {:replicant/key "indent" :value "indent"} "Indent"]
   [:option {:replicant/key "outdent" :value "outdent"} "Outdent"]
   [:option {:replicant/key "add-ref" :value "add-reference"} "Add reference to selected"]
   [:option {:replicant/key "remove-ref" :value "remove-reference"} "Remove reference from selected"]])

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
   [:div {:class [:hotkeys-header]}
    [:h4 "Keyboard Shortcuts"]
    [:span {:class [:hotkeys-hint]} "(press Esc to quit selection)"]]
   [:div {:class [:hotkeys-grid]}
    ;; Selection operations
    [:div {:class [:hotkey-group]}
     [:h5 "Selection"]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Delete selected blocks"]
      [:div {:class [:hotkey-keys]}
       [:kbd "Backspace"] [:span {:class [:key-separator]} " / "] [:kbd "Delete"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Edit selected block"]
      [:div {:class [:hotkey-keys]}
       [:kbd "↩"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Select all blocks"]
      [:div {:class [:hotkey-keys]}
       [:kbd "⌘"] [:kbd "⇧"] [:kbd "A"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Select block below"]
      [:div {:class [:hotkey-keys]}
       [:kbd "Opt"] [:kbd "↓"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Select block above"]
      [:div {:class [:hotkey-keys]}
       [:kbd "Opt"] [:kbd "↑"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Select parent block"]
      [:div {:class [:hotkey-keys]}
       [:kbd "⌘"] [:kbd "A"]]]]

    ;; Block operations
    [:div {:class [:hotkey-group]}
     [:h5 "Block Operations"]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Create child block"]
      [:div {:class [:hotkey-keys]}
       [:kbd "Enter"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Create sibling above"]
      [:div {:class [:hotkey-keys]}
       [:kbd "Shift"] [:kbd "Enter"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Indent block"]
      [:div {:class [:hotkey-keys]}
       [:kbd "Tab"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Outdent block"]
      [:div {:class [:hotkey-keys]}
       [:kbd "Shift"] [:kbd "Tab"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Move block up/down"]
      [:div {:class [:hotkey-keys]}
       [:kbd "Alt"] [:kbd "Shift"] [:kbd "↑↓"]]]
     [:div {:class [:hotkey-item]}
      [:span {:class [:hotkey-desc]} "Toggle collapse/expand"]
      [:div {:class [:hotkey-keys]}
       [:kbd "⌘"] [:kbd "."]]]]]])

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