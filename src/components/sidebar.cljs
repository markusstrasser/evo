(ns components.sidebar
  "Sidebar component for page navigation."
  (:require [plugins.pages :as pages]
            [shell.view-state :as vs]))

(defn Sidebar
  "Sidebar showing list of pages with navigation.

   Props:
   - db: application database
   - on-intent: callback for dispatching intents
   - on-pick-folder: callback to trigger folder picker dialog
   - on-clear-folder: callback to disconnect from folder
   - storage-status: {:folder-name string :loading? bool}"
  [{:keys [db on-intent on-pick-folder on-clear-folder storage-status]}]
  (let [all-pages (pages/all-pages db)
        current-page-id (vs/current-page)
        folder-name (:folder-name storage-status)
        loading? (:loading? storage-status)]
    [:div.sidebar

     ;; Storage section at top
     [:div.sidebar-storage
      {:style {:padding "12px"
               :border-bottom "1px solid var(--color-border)"
               :margin-bottom "8px"}}
      (if folder-name
        ;; Connected to folder
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "8px"
                       :font-size "13px"}}
         [:span {:style {:color "var(--color-ink-faint)"}} "\uD83D\uDCC1"]
         [:span {:style {:color "var(--color-ink)"
                         :overflow "hidden"
                         :text-overflow "ellipsis"
                         :white-space "nowrap"}}
          folder-name]
         [:button.sidebar-btn
          {:style {:margin-left "auto"
                   :padding "4px 8px"
                   :font-size "11px"
                   :background "transparent"
                   :border "1px solid var(--color-border)"
                   :border-radius "4px"
                   :cursor "pointer"
                   :color "var(--color-ink-faint)"}
           :on {:click (fn [e]
                         (.preventDefault e)
                         (when on-clear-folder
                           (on-clear-folder)))}}
          "\u00D7"]]
        ;; Not connected
        [:button.sidebar-btn
         {:style {:width "100%"
                  :padding "8px 12px"
                  :font-size "13px"
                  :background "var(--color-surface-raised)"
                  :border "1px dashed var(--color-border)"
                  :border-radius "6px"
                  :cursor (if loading? "wait" "pointer")
                  :color "var(--color-ink)"
                  :display "flex"
                  :align-items "center"
                  :justify-content "center"
                  :gap "8px"}
          :disabled loading?
          :on {:click (fn [e]
                        (.preventDefault e)
                        (when on-pick-folder
                          (on-pick-folder)))}}
         (if loading?
           "Loading..."
           [:span "\uD83D\uDCC2 Open Folder"])])]

     ;; Header with New Page button
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :padding "0 12px"}}
      [:h3 {:style {:margin "0"}} "Pages"]
      [:button.sidebar-btn
       {:style {:padding "4px 8px"
                :font-size "12px"
                :background "transparent"
                :border "1px solid var(--color-border)"
                :border-radius "4px"
                :cursor "pointer"
                :color "var(--color-ink-faint)"}
        :on {:click (fn [e]
                      (.preventDefault e)
                      (let [title (js/prompt "Page title:")]
                        (when (and title (not= title ""))
                          (when on-intent
                            (on-intent {:type :create-page :title title})))))}}
       "+ New"]]

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
             [:span {:style {:flex "1"}} title]
             ;; Delete button (appears on hover via CSS)
             [:button.sidebar-delete
              {:style {:padding "2px 6px"
                       :font-size "11px"
                       :background "transparent"
                       :border "none"
                       :border-radius "3px"
                       :cursor "pointer"
                       :color "var(--color-ink-faint)"
                       :opacity "0"
                       :transition "opacity 0.15s"}
               :on {:click (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (when (js/confirm (str "Delete \"" title "\"?"))
                               (when on-intent
                                 (on-intent {:type :delete-page :page-id page-id}))))}}
              "\u00D7"]]))
        ;; Empty state
        [:div {:style {:padding "20px"
                       :text-align "center"
                       :color "var(--color-ink-faint)"
                       :font-size "14px"}}
         "No pages yet"])]]))
