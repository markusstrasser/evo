(ns components.devtools-simple
  "Simplified Dev Tools: Event → Human-Spec → DB Diff"
  (:require [dev.tooling :as devtools]
            [dev.data-diff :as dd]
            [kernel.query :as q]
            [plugins.pages :as pages]
            [clojure.string :as str]
            [dataspex.core :as dataspex]))

(defonce !selected-entry (atom nil))

(defn EntryInspector
  "Simple inspector: event → human-spec → db diff."
  [{:keys [entry current-page-id]}]
  (when entry
    (let [{:keys [intent db-before db-after timestamp hotkey]} entry
          timestamp-obj (js/Date. timestamp)
          time-str (.toLocaleTimeString timestamp-obj)
          {:keys [changes]} (dd/compare-db-states db-before db-after)]
      [:div.entry-inspector
       {:style {:background-color "#ffffff"
                :border "1px solid #e5e7eb"
                :border-radius "6px"
                :padding "15px"}}

       ;; EVENT
       [:div.event-header
        {:style {:margin-bottom "15px"
                 :padding "12px 15px"
                 :background-color "#eff6ff"
                 :border-left "4px solid #3b82f6"
                 :border-radius "4px"}}
        [:div {:style {:font-size "16px" :font-weight "600" :color "#1e40af"}}
         "⚡ " (devtools/format-intent intent)]
        [:div {:style {:font-size "12px" :color "#6b7280" :margin-top "6px"}}
         time-str
         (when hotkey
           [:span {:style {:margin-left "12px"
                           :padding "3px 8px"
                           :background-color "#dbeafe"
                           :border-radius "3px"
                           :font-family "monospace"}}
            "⌨️ " hotkey])]]

       ;; HUMAN-SPEC
       [:div.human-spec-section
        {:style {:margin-bottom "15px"}}
        [:h4 {:style {:margin "0 0 10px 0"
                      :font-size "13px"
                      :color "#374151"
                      :font-weight "600"}}
         "State (Human-Spec)"]
        [:div.before-after
         {:style {:display "grid"
                  :grid-template-columns "1fr 1fr"
                  :gap "12px"}}
         [:div
          [:div {:style {:font-size "11px"
                         :color "#6b7280"
                         :margin-bottom "6px"
                         :font-weight "600"
                         :text-transform "uppercase"}}
           "Before"]
          [:pre {:style {:margin 0
                         :padding "10px"
                         :background-color "#1f2937"
                         :color "#10b981"
                         :border-radius "4px"
                         :font-size "11px"
                         :line-height "1.5"
                         :max-height "250px"
                         :overflow-y "auto"
                         :font-family "monospace"}}
           (devtools/format-state-snapshot db-before)]]
         [:div
          [:div {:style {:font-size "11px"
                         :color "#6b7280"
                         :margin-bottom "6px"
                         :font-weight "600"
                         :text-transform "uppercase"}}
           "After"]
          [:pre {:style {:margin 0
                         :padding "10px"
                         :background-color "#1f2937"
                         :color "#10b981"
                         :border-radius "4px"
                         :font-size "11px"
                         :line-height "1.5"
                         :max-height "250px"
                         :overflow-y "auto"
                         :font-family "monospace"}}
           (devtools/format-state-snapshot db-after)]]]]

       ;; DB DIFF
       [:div.db-diff-section
        [:h4 {:style {:margin "0 0 10px 0"
                      :font-size "13px"
                      :color "#374151"
                      :font-weight "600"}}
         "DB Changes (" (count changes) ")"]
        (if (seq changes)
          [:div.changes-list
           {:style {:max-height "250px"
                    :overflow-y "auto"}}
           (for [[idx change] (map-indexed vector changes)]
             ^{:key idx}
             [:div.change-item
              {:style {:padding "8px 10px"
                       :margin-bottom "4px"
                       :background-color (case (:type change)
                                           :+ "#ecfdf5"
                                           :- "#fef2f2"
                                           :r "#fef3c7"
                                           "#f9fafb")
                       :border-left (str "3px solid "
                                         (case (:type change)
                                           :+ "#10b981"
                                           :- "#ef4444"
                                           :r "#f59e0b"
                                           "#e5e7eb"))
                       :border-radius "3px"
                       :font-family "monospace"
                       :font-size "11px"}}
              [:div {:style {:color "#6b7280" :margin-bottom "3px" :font-size "10px"}}
               (str/join " → " (map str (:path change)))]
              [:div
               (case (:type change)
                 :+ [:span {:style {:color "#047857"}}
                     "+ " (pr-str (:value change))]
                 :- [:span {:style {:color "#b91c1c"}}
                     "- " (pr-str (:old-value change))]
                 :r [:div
                     [:div {:style {:color "#b45309"}} "Old: " (pr-str (:old-value change))]
                     [:div {:style {:color "#047857"}} "New: " (pr-str (:new-value change))]]
                 [:span (pr-str change)])]])]
          [:div {:style {:padding "15px"
                         :text-align "center"
                         :color "#9ca3af"
                         :font-style "italic"
                         :font-size "12px"}}
           "No DB changes"])]])))

(defn DBStateView
  "Compact DB state metrics."
  [{:keys [db]}]
  (let [nodes (:nodes db)
        selection (q/selection db)
        editing (q/editing-block-id db)]
    [:div.db-state-view
     {:style {:background-color "#ffffff"
              :border "1px solid #e5e7eb"
              :border-radius "6px"
              :padding "12px 15px"
              :margin-bottom "15px"
              :display "flex"
              :gap "20px"
              :align-items "center"
              :font-family "monospace"
              :font-size "12px"}}
     [:div {:style {:font-weight "600" :color "#374151"}} "DB:"]
     [:div
      [:span {:style {:color "#6b7280"}} "Nodes: "]
      [:span {:style {:color "#3b82f6" :font-weight "600"}} (count nodes)]]
     [:div
      [:span {:style {:color "#6b7280"}} "Selected: "]
      [:span {:style {:color "#f59e0b" :font-weight "600"}} (count selection)]]
     [:div
      [:span {:style {:color "#6b7280"}} "Editing: "]
      [:span {:style {:color (if editing "#10b981" "#9ca3af") :font-weight "600"}}
       (if editing "✓" "—")]]]))

(defn DevToolsPanel
  "Simplified dev tools: event → state → diff."
  [{:keys [db]}]
  (let [log (devtools/get-log)
        current-page-id (pages/current-page db)
        selected-entry (or @!selected-entry (last log))]

    [:div.devtools-panel-simple
     {:style {:margin-top "30px"
              :padding "20px"
              :background-color "#f9fafb"
              :border-radius "8px"}}

     [:h2 {:style {:margin "0 0 15px 0"
                   :font-size "18px"
                   :color "#111827"
                   :font-weight "600"}}
      "🛠 Dev Tools"]

     (DBStateView {:db db})

     [:div.log-timeline
      {:style {:background-color "#ffffff"
               :border "1px solid #e5e7eb"
               :border-radius "6px"
               :padding "12px"
               :margin-bottom "15px"}}
      [:div {:style {:display "flex"
                     :justify-content "space-between"
                     :align-items "center"
                     :margin-bottom "10px"}}
       [:h4 {:style {:margin 0
                     :font-size "13px"
                     :color "#374151"
                     :font-weight "600"}}
        "Operations (" (count log) ")"]
       [:div {:style {:display "flex" :gap "8px"}}
        [:button
         {:on {:click (fn [e]
                        (.stopPropagation e)
                        (devtools/clear-log!)
                        (reset! !selected-entry nil))}
          :style {:padding "4px 10px"
                  :background-color "#ef4444"
                  :color "white"
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-size "11px"}}
         "Clear"]
        [:button
         {:on {:click (fn [e]
                        (.stopPropagation e)
                        (dataspex/inspect "App DB" db))}
          :style {:padding "4px 10px"
                  :background-color "#10b981"
                  :color "white"
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-size "11px"}}
         "Dataspex"]]]

      [:div.log-entries
       {:style {:max-height "150px"
                :overflow-y "auto"}}
       (if (empty? log)
         [:div {:style {:text-align "center"
                        :padding "15px"
                        :color "#9ca3af"
                        :font-size "12px"}}
          "No operations yet"]
         (for [[idx entry] (map-indexed vector (reverse log))]
           ^{:key idx}
           [:div.log-entry
            {:on {:click (fn [e]
                           (.stopPropagation e)
                           (reset! !selected-entry entry))}
             :style {:padding "6px 10px"
                     :margin-bottom "3px"
                     :background-color (if (= entry selected-entry) "#eff6ff" "#ffffff")
                     :border-left (str "3px solid "
                                       (if (= entry selected-entry) "#3b82f6" "#e5e7eb"))
                     :border-radius "3px"
                     :cursor "pointer"
                     :font-family "monospace"
                     :font-size "11px"
                     :transition "all 0.15s"}}
            [:div {:style {:color "#3b82f6" :font-weight "600"}}
             (devtools/format-intent (:intent entry))]
            [:div {:style {:color "#9ca3af" :font-size "10px" :margin-top "2px"}}
             (.toLocaleTimeString (js/Date. (:timestamp entry)))]]))]]

     (when selected-entry
       (EntryInspector {:entry selected-entry
                        :current-page-id current-page-id}))]))
