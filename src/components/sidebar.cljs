(ns components.sidebar
  "Sidebar component for page navigation."
  (:require [plugins.pages :as pages]
            [shell.view-state :as vs]))

(defn Sidebar
  "Sidebar showing list of pages with navigation.

   Props:
   - db: application database
   - on-intent: callback for dispatching intents"
  [{:keys [db on-intent]}]
  (let [all-pages (pages/all-pages db)
        current-page-id (vs/current-page)]
    [:div.sidebar

     ;; Header
     [:h3 "Pages"]

     ;; Page list
     [:div.page-list
      (if (seq all-pages)
        (for [page-id all-pages]
          (let [is-current? (= page-id current-page-id)
                title (pages/page-title db page-id)]
            [:div.sidebar-item
             {:key page-id
              :class (when is-current? "active")
              :on {:click (fn [e]
                            (.preventDefault e)
                            (when on-intent
                              (on-intent {:type :switch-page :page-id page-id})))}}
             [:span title]]))
        ;; Empty state
        [:div {:style {:padding "20px"
                       :text-align "center"
                       :color "var(--color-ink-faint)"
                       :font-size "14px"}}
         "No pages yet"])]]))