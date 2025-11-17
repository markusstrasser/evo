(ns components.slash-menu
  "Slash command menu UI component.
   
   Renders as an inline popup menu anchored to the cursor position."
  (:require [replicant.dom :as d]
            [kernel.constants :as const]))

(defn- get-caret-coordinates
  "Get the current caret position coordinates for menu positioning."
  []
  (when-let [sel (.getSelection js/window)]
    (when (pos? (.-rangeCount sel))
      (let [range (.getRangeAt sel 0)
            rect (.getBoundingClientRect range)
            scroll-top (or (.-scrollTop (.-documentElement js/document)) 0)
            scroll-left (or (.-scrollLeft (.-documentElement js/document)) 0)]
        {:top (+ (.-bottom rect) scroll-top)
         :left (+ (.-left rect) scroll-left)}))))

(defn SlashMenuItem
  "Single command item in the menu."
  [{:keys [command selected? on-select]}]
  (let [{:keys [icon cmd-name doc]} command]
    [:div.slash-menu-item
     {:class (when selected? "selected")
      :on-click on-select
      :on-mouse-enter on-select
      :style {:padding "8px 12px"
              :cursor "pointer"
              :display "flex"
              :align-items "center"
              :gap "10px"
              :background-color (if selected? "#e3f2fd" "#fff")
              :border-left (if selected? "3px solid #1976d2" "3px solid transparent")}}
     [:span.slash-menu-icon
      {:style {:font-size "18px"
               :width "24px"
               :text-align "center"}}
      icon]
     [:div.slash-menu-text
      {:style {:flex "1"}}
      [:div.slash-menu-name
       {:style {:font-weight (if selected? "600" "400")
                :color "#212121"}}
       cmd-name]
      [:div.slash-menu-doc
       {:style {:font-size "12px"
                :color "#757575"}}
       doc]]]))

(defn SlashMenu
  "Slash command menu component.
   
   Shows available commands filtered by search text.
   Positioned at cursor location."
  [{:keys [db on-intent]}]
  (when-let [menu (get-in db [:nodes const/session-ui-id :props :slash-menu])]
    (let [{:keys [results selected-idx search-text]} menu
          coords (get-caret-coordinates)
          has-results? (seq results)]
      (when coords
        [:div.slash-menu-container
         {:style {:position "absolute"
                  :top (str (:top coords) "px")
                  :left (str (:left coords) "px")
                  :z-index "1000"}}
         [:div.slash-menu
          {:style {:background-color "#fff"
                   :border "1px solid #e0e0e0"
                   :border-radius "8px"
                   :box-shadow "0 4px 12px rgba(0,0,0,0.15)"
                   :min-width "300px"
                   :max-width "400px"
                   :max-height "400px"
                   :overflow-y "auto"}}

          ;; Header
          [:div.slash-menu-header
           {:style {:padding "10px 12px"
                    :border-bottom "1px solid #e0e0e0"
                    :font-size "12px"
                    :color "#757575"
                    :font-weight "500"}}
           (if (empty? search-text)
             "Slash Commands"
             (str "Search: /" search-text))]

          ;; Results
          (if has-results?
            [:div.slash-menu-results
             (for [[idx command] (map-indexed vector results)]
               ^{:key (:id command)}
               [SlashMenuItem
                {:command command
                 :selected? (= idx selected-idx)
                 :on-select #(on-intent {:type :slash-menu/select})}])]

            ;; No results
            [:div.slash-menu-empty
             {:style {:padding "20px"
                      :text-align "center"
                      :color "#9e9e9e"}}
             "No commands found"])]]))))
