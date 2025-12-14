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

;; ── Helpers for Content Detection ────────────────────────────────────────────

(defn- has-content?
  "Check if a journal page has any non-empty blocks (recursively)."
  [db page-id]
  (let [children (q/children db page-id)]
    (some (fn [bid]
            (let [text (q/block-text db bid)
                  child-children (q/children db bid)]
              (or (and text (not (str/blank? text)))
                  (and (seq child-children) (has-content? db bid)))))
          children)))

;; ── Journal Page Renderer ──────────────────────────────────────────────────────

(defn- JournalBlock
  "Recursively render a block and its children with proper hierarchy.
   depth controls indentation (0 = top-level)."
  [db block-id depth]
  (let [text (q/block-text db block-id)
        children (q/children db block-id)
        ;; Skip empty blocks without children
        has-text? (and text (not (str/blank? text)))
        has-children? (seq children)]
    (when (or has-text? has-children?)
      [:div.journal-block {:style {:margin-left (str (* depth 20) "px")}}
       (when has-text?
         [:div.journal-block-content
          [:span.block-bullet "•"]
          [:span.block-text text]])
       (when has-children?
         (into [:div.journal-block-children]
               (keep #(JournalBlock db % (inc depth)) children)))])))

(defn- JournalPage
  "Single journal page in the journals list.
   The entire journal item is clickable to navigate to that page."
  [{:keys [db page-id title is-last? on-intent]}]
  (let [children (q/children db page-id)
        ;; Check if page has any content (recursively)
        has-any-content? (has-content? db page-id)
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
     ;; Journal blocks with proper hierarchy
     (if has-any-content?
       [:div.journal-blocks
        (into [:<>] (keep #(JournalBlock db % 0) children))]
       [:div.journal-empty
        [:span.empty-hint "Click to add entries"]])]))

;; ── Main Journals View ─────────────────────────────────────────────────────────

(defn- today-iso
  "Get today's date in ISO format (YYYY-MM-DD)."
  []
  (let [now (js/Date.)
        y (.getFullYear now)
        m (inc (.getMonth now))
        d (.getDate now)]
    (str y "-" (when (< m 10) "0") m "-" (when (< d 10) "0") d)))

(defn JournalsView
  "Main journals view showing all journal pages stacked.

   Props:
   - db: Database
   - on-intent: Intent handler for navigation

   LOGSEQ PARITY:
   - Today's journal always shows (even if empty), positioned first
   - Other empty journals are hidden
   - Journals sorted by date descending (newest first)"
  [{:keys [db on-intent]}]
  (let [all-pages (q/all-pages db)
        today (today-iso)
        ;; Get journal pages with titles and content status
        journal-pages (->> all-pages
                           (map (fn [pid]
                                  (let [title (q/page-title db pid)
                                        date (parse-journal-date title)]
                                    {:id pid
                                     :title title
                                     :date date
                                     :is-today? (= date today)
                                     :has-content? (has-content? db pid)})))
                           (filter #(journal-page? (:title %)))
                           ;; Keep: today OR has content
                           (filter #(or (:is-today? %) (:has-content? %)))
                           ;; Sort: today first, then by date descending
                           (sort (fn [a b]
                                   (cond
                                     ;; Today always first
                                     (:is-today? a) -1
                                     (:is-today? b) 1
                                     ;; Then by date descending (newest first)
                                     :else (compare (:date b) (:date a))))))
        total (count journal-pages)]

    [:div.journals-view
     ;; Header with back button
     [:div.journals-header
      [:button.journals-back
       {:on {:click (fn [e]
                      (.preventDefault e)
                      (vs/set-journals-view! false))}}
       "← Back"]
      [:h2.journals-title "Journals"]
      [:span.journals-count (str total " entries")]]

     ;; Journal pages list - use Replicant VECTOR SYNTAX for components
     (if (seq journal-pages)
       (into [:div.journals-list]
             (map-indexed
              (fn [idx {:keys [id title]}]
                ;; REPLICANT: Use vector syntax [Component props], not (Component props)
                ^{:key id}
                [JournalPage {:db db
                              :page-id id
                              :title title
                              :is-last? (= idx (dec total))
                              :on-intent on-intent}])
              journal-pages))
       [:div.journals-empty
        [:p "No journal pages yet"]
        [:p.hint "Journal pages are date-formatted pages like 'Dec 14th, 2025'"]])]))
