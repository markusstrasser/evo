(ns components.journals
  "Journals view - shows all journal pages stacked, newest first.

   Like Logseq's /all-journals view:
   - Multiple journal pages on one scrollable page
   - Newest journals at top
   - Each journal is FULLY EDITABLE (uses real Block components)
   - Borders between journals for visual separation"
  (:require [kernel.query :as q]
            [shell.view-state :as vs]
            [components.block :as block]
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

(defn- JournalPage
  "Single journal page in the journals list - FULLY EDITABLE.
   Uses real Block components for inline editing.
   Title is clickable to navigate to that page."
  [{:keys [db page-id title is-last? on-intent
           editing-block-id focus-block-id selection-set folded-set]}]
  (let [children (q/children db page-id)
        has-any-content? (or (seq children) (has-content? db page-id))
        navigate-to-page (fn [e]
                           (.preventDefault e)
                           (.stopPropagation e)
                           (vs/set-journals-view! false)
                           (when on-intent
                             (on-intent {:type :switch-page :page-id page-id})))]
    [:div.journal-item
     {:replicant/key page-id
      :class [(when is-last? "journal-last")]}
     ;; Journal title/date header - clickable to navigate
     [:h3.journal-title
      {:on {:click navigate-to-page}
       :style {:cursor "pointer"}}
      title]
     ;; Journal blocks - REAL Block components for full editing
     (if has-any-content?
       (into [:div.journal-blocks]
             (map (fn [child-id]
                    (block/Block {:db db
                                  :block-id child-id
                                  :depth 0
                                  :is-focused (= focus-block-id child-id)
                                  :is-selected (contains? selection-set child-id)
                                  :is-editing (= editing-block-id child-id)
                                  :is-folded (contains? folded-set child-id)
                                  :on-intent on-intent}))
                  children))
       ;; Empty journal - clickable placeholder to create first block
       [:div.journal-empty
        {:style {:padding "20px"
                 :text-align "center"
                 :color "#9ca3af"
                 :cursor "text"
                 :border "1px dashed #e5e7eb"
                 :border-radius "4px"
                 :margin "10px 0"}
         :on {:click (fn [e]
                       (.stopPropagation e)
                       (let [new-id (str "block-" (random-uuid))]
                         (on-intent {:type :create-block-in-page
                                     :page-id page-id
                                     :block-id new-id})))}}
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
  "Main journals view showing all journal pages stacked and EDITABLE.

   Props:
   - db: Database
   - on-intent: Intent handler for navigation and editing

   LOGSEQ PARITY:
   - Today's journal always shows (even if empty), positioned first
   - Other empty journals are hidden
   - Journals sorted by date descending (newest first)
   - Full editing support (same as regular pages)"
  [{:keys [db on-intent]}]
  (let [all-pages (q/all-pages db)
        today (today-iso)
        ;; Session state for editing context (same as Outline)
        editing-block-id (vs/editing-block-id)
        focus-block-id (vs/focus-id)
        selection-set (vs/selection-nodes)
        folded-set (vs/folded)
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
        total (count journal-pages)
        ;; Build journal items with editing context
        journal-items (map-indexed
                       (fn [idx {:keys [id title]}]
                         (JournalPage {:db db
                                       :page-id id
                                       :title title
                                       :is-last? (= idx (dec total))
                                       :on-intent on-intent
                                       :editing-block-id editing-block-id
                                       :focus-block-id focus-block-id
                                       :selection-set selection-set
                                       :folded-set folded-set}))
                       journal-pages)]

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

     ;; Journal pages list - fully editable
     (if (seq journal-pages)
       (into [:div.journals-list] journal-items)
       [:div.journals-empty
        [:p "No journal pages yet"]
        [:p.hint "Journal pages are date-formatted pages like 'Dec 14th, 2025'"]])]))
