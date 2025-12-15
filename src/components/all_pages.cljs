(ns components.all-pages
  "All Pages view - shows all pages in a list (Logseq-style /all-pages).

   Features:
   - Shows all pages (excluding journals)
   - Click to navigate
   - Search/filter (future)
   - Create new page button"
  (:require [kernel.query :as q]
            [shell.view-state :as vs]
            [clojure.string :as str]))

(defn- journal-page?
  "Detect if a page title looks like a journal date."
  [title]
  (when title
    (or (re-matches #"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th), \d{4}" title)
        (re-matches #"\d{4}-\d{2}-\d{2}" title))))

(defn- valid-page?
  "Check if page has a valid, displayable title."
  [title]
  (and (some? title)
       (not (str/blank? title))
       (not= title "Untitled")))

(defn AllPagesView
  "All Pages listing view (like Logseq's /all-pages).

   Shows all regular pages (excluding journals) in a clean list.
   Click to navigate to page."
  [{:keys [db on-intent]}]
  (let [all-pages (q/all-pages db)
        favorites-set (vs/favorites)

        ;; Build page metadata, filtering invalid/journal pages
        pages (->> all-pages
                   (map (fn [pid]
                          (let [title (q/page-title db pid)]
                            {:id pid
                             :title title
                             :favorite? (contains? favorites-set pid)
                             :valid? (valid-page? title)
                             :journal? (journal-page? title)})))
                   (filter :valid?)
                   (remove :journal?)
                   (sort-by (comp str/lower-case :title)))]

    [:div.all-pages-view
     {:style {:padding "0"}}

     [:div.all-pages-header
      {:style {:display "flex"
               :align-items "center"
               :justify-content "space-between"
               :margin-bottom "20px"}}
      [:h3 {:style {:margin "0"
                    :color "#374151"}}
       "All Pages"]
      [:button.create-page-btn
       {:style {:padding "6px 12px"
                :background "#3b82f6"
                :color "white"
                :border "none"
                :border-radius "6px"
                :cursor "pointer"
                :font-size "13px"}
        :on {:click (fn [e]
                      (.preventDefault e)
                      (let [title (js/prompt "Page title:")]
                        (when (and title (not (str/blank? title)))
                          (on-intent {:type :create-page :title title}))))}}
       "+ New Page"]]

     (if (seq pages)
       [:div.all-pages-list
        {:style {:display "flex"
                 :flex-direction "column"
                 :gap "2px"}}
        (for [{:keys [id title favorite?]} pages]
          ^{:key id}
          [:div.all-pages-item
           {:style {:padding "10px 12px"
                    :border-radius "6px"
                    :cursor "pointer"
                    :display "flex"
                    :align-items "center"
                    :gap "8px"
                    :transition "background 0.15s ease"}
            :on {:click (fn [e]
                          (.preventDefault e)
                          (vs/add-to-recents! id)
                          (on-intent {:type :switch-page :page-id id}))
                 :mouseenter (fn [e]
                               (set! (.. e -currentTarget -style -background) "#f3f4f6"))
                 :mouseleave (fn [e]
                               (set! (.. e -currentTarget -style -background) "transparent"))}}
           ;; Page icon
           [:span {:style {:color "#9ca3af"}} "📄"]
           ;; Title
           [:span {:style {:flex "1"
                           :color "#374151"}}
            title]
           ;; Favorite indicator
           (when favorite?
             [:span {:style {:color "#fbbf24"}} "★"])])]

       ;; Empty state
       [:div.all-pages-empty
        {:style {:padding "40px"
                 :text-align "center"
                 :color "#9ca3af"}}
        [:p "No pages yet"]
        [:p {:style {:font-size "13px"}}
         "Click \"+ New Page\" to create one"]])]))
