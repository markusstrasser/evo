(ns components.journals
  "Journals view - shows all journal pages stacked, newest first.

   Like Logseq's /all-journals view:
   - Multiple journal pages on one scrollable page
   - Newest journals at top
   - Each journal is a full page with its blocks
   - Borders between journals for visual separation"
  (:require [kernel.query :as q]
            [shell.view-state :as vs]
            [clojure.string :as str]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- journal-page?
  "Detect if a page title looks like a journal date.
   Matches patterns like 'Dec 14th, 2025' or '2025-12-14'."
  [title]
  (when title
    (or (re-matches #"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th), \d{4}" title)
        (re-matches #"\d{4}-\d{2}-\d{2}" title))))

(defn- parse-journal-date
  "Parse journal title to sortable date value.
   Returns nil if not a valid journal format."
  [title]
  (when title
    (cond
      ;; ISO format: 2025-12-14
      (re-matches #"\d{4}-\d{2}-\d{2}" title)
      title

      ;; Human format: Dec 14th, 2025 -> 2025-12-14
      (re-matches #"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th), \d{4}" title)
      (let [months {"Jan" "01" "Feb" "02" "Mar" "03" "Apr" "04"
                    "May" "05" "Jun" "06" "Jul" "07" "Aug" "08"
                    "Sep" "09" "Oct" "10" "Nov" "11" "Dec" "12"}
            [_ mon day _ year] (re-matches #"([A-Z][a-z]{2}) (\d{1,2})(st|nd|rd|th), (\d{4})" title)]
        (when (and mon day year)
          (str year "-" (months mon) "-" (when (< (count day) 2) "0") day)))

      :else nil)))

;; ── Journal Page Renderer ──────────────────────────────────────────────────────

(defn- JournalPage
  "Single journal page in the journals list.
   The entire journal item is clickable to navigate to that page."
  [{:keys [db page-id title is-last? on-intent]}]
  (let [children (q/children db page-id)
        ;; Only show blocks with actual content
        blocks-with-content (->> children
                                 (map (fn [bid] {:id bid :text (q/block-text db bid)}))
                                 (remove #(str/blank? (:text %))))
        navigate-to-page (fn [e]
                           (.preventDefault e)
                           (vs/set-journals-view! false)
                           (when on-intent
                             (on-intent {:type :switch-page :page-id page-id})))]
    [:div.journal-item
     {:class [(when is-last? "journal-last")
              "journal-clickable"]
      :on {:click navigate-to-page}}
     ;; Journal title/date header
     [:h3.journal-title title]
     ;; Journal blocks - only those with content
     (if (seq blocks-with-content)
       [:div.journal-blocks
        (for [{:keys [id text]} blocks-with-content]
          ^{:key id}
          [:div.journal-block
           [:span.block-bullet "\u2022"]
           [:span.block-text text]])]
       [:div.journal-empty
        [:span.empty-hint "Click to add entries"]])]))

;; ── Main Journals View ─────────────────────────────────────────────────────────

(defn JournalsView
  "Main journals view showing all journal pages stacked.

   Props:
   - db: Database
   - on-intent: Intent handler for navigation"
  [{:keys [db on-intent]}]
  (let [all-pages (q/all-pages db)
        ;; Get journal pages with titles
        journal-pages (->> all-pages
                           (map (fn [pid]
                                  (let [title (q/page-title db pid)]
                                    {:id pid
                                     :title title
                                     :date (parse-journal-date title)})))
                           (filter #(journal-page? (:title %)))
                           ;; Sort by date descending (newest first)
                           (sort-by :date #(compare %2 %1)))]

    [:div.journals-view
     ;; Header with back button
     [:div.journals-header
      [:button.journals-back
       {:on {:click (fn [e]
                      (.preventDefault e)
                      (vs/set-journals-view! false))}}
       "\u2190 Back"]
      [:h2.journals-title "Journals"]
      [:span.journals-count (str (count journal-pages) " entries")]]

     ;; Journal pages list
     (if (seq journal-pages)
       [:div.journals-list
        (let [total (count journal-pages)]
          (for [[idx {:keys [id title]}] (map-indexed vector journal-pages)]
            ^{:key id}
            (JournalPage {:db db
                          :page-id id
                          :title title
                          :is-last? (= idx (dec total))
                          :on-intent on-intent})))]
       [:div.journals-empty
        [:p "No journal pages yet"]
        [:p.hint "Journal pages are date-formatted pages like 'Dec 14th, 2025'"]])]))
