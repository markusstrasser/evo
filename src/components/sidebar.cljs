(ns components.sidebar
  "Sidebar component for page navigation."
  (:require [plugins.pages :as pages]
            [shell.session :as session]))

(defn Sidebar
  "Sidebar showing list of pages with navigation.

   Props:
   - db: application database
   - on-intent: callback for dispatching intents"
  [{:keys [db on-intent]}]
  (let [all-pages (pages/all-pages db)
        current-page-id (session/current-page)]
    [:div.sidebar
     {:style {:position "fixed"
              :left "0"
              :top "0"
              :bottom "0"
              :width "200px"
              :background-color "rgb(249, 250, 251)"
              :border-right "1px solid rgb(229, 231, 235)"
              :padding "16px"
              :overflow-y "auto"
              :box-shadow "2px 0 8px rgba(0,0,0,0.05)"}}

     ;; Header
     [:div.sidebar-header
      {:style {:margin-bottom "16px"
               :padding-bottom "12px"
               :border-bottom "2px solid rgb(209, 213, 219)"}}
      [:h3 {:style {:margin "0"
                    :font-size "18px"
                    :font-weight "600"
                    :color "rgb(31, 41, 55)"}}
       "Pages"]]

     ;; Page list
     [:div.page-list
      (if (seq all-pages)
        (for [page-id all-pages]
          (let [is-current? (= page-id current-page-id)
                title (pages/page-title db page-id)]
            [:div.page-item
             {:key page-id
              :style {:padding "8px 12px"
                      :margin-bottom "4px"
                      :border-radius "6px"
                      :cursor "pointer"
                      :background-color (if is-current?
                                          "rgb(219, 234, 254)"
                                          "transparent")
                      :color (if is-current?
                               "rgb(29, 78, 216)"
                               "rgb(75, 85, 99)")
                      :font-weight (if is-current? "600" "400")
                      :transition "all 0.15s ease"
                      :user-select "none"}
              :on {:click (fn [e]
                           (.preventDefault e)
                           (when on-intent
                             (on-intent {:type :switch-page :page-id page-id})))
                   :mouseenter (fn [e]
                                (when-not is-current?
                                  (set! (.. e -target -style -backgroundColor) "rgb(243, 244, 246)")))
                   :mouseleave (fn [e]
                                (when-not is-current?
                                  (set! (.. e -target -style -backgroundColor) "transparent")))}}
             ;; Page icon
             [:span {:style {:margin-right "8px"
                            :opacity "0.6"}}
              "📄"]
             ;; Page title
             [:span title]]))
        ;; Empty state
        [:div {:style {:padding "20px"
                      :text-align "center"
                      :color "rgb(156, 163, 175)"
                      :font-size "14px"}}
         "No pages yet"])]]))
