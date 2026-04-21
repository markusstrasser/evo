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
            [utils.journal :as journal]
            [clojure.string :as str]))

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

(defn- ordinal-suffix
  "Get ordinal suffix for a day number (1st, 2nd, 3rd, 4th, etc.)."
  [day]
  (cond
    (or (= day 11) (= day 12) (= day 13)) "th"
    (= (mod day 10) 1) "st"
    (= (mod day 10) 2) "nd"
    (= (mod day 10) 3) "rd"
    :else "th"))

(defn- today-title
  "Get today's date as human-readable title: 'Dec 14th, 2025'."
  []
  (let [now (js/Date.)
        months ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]
        y (.getFullYear now)
        m (.getMonth now)
        d (.getDate now)]
    (str (nth months m) " " d (ordinal-suffix d) ", " y)))

(defn JournalsView
  "Main journals view showing all journal pages stacked and EDITABLE.

   Props:
   - db: Database
   - on-intent: Intent handler for navigation and editing

   LOGSEQ PARITY:
   - Today's journal always shows (even if empty), positioned first
   - Auto-creates today's journal if it doesn't exist
   - Other empty journals are hidden
   - Journals sorted by date descending (newest first)
   - Full editing support (same as regular pages)"
  [{:keys [db on-intent]}]
  (let [all-pages (q/all-pages db)
        today (today-iso)
        today-human (today-title)
        ;; Session state for editing context (same as Outline)
        editing-block-id (vs/editing-block-id)
        focus-block-id (vs/focus-id)
        selection-set (vs/selection-nodes)
        folded-set (vs/folded)
        ;; Get journal pages with titles and content status
        existing-journals (->> all-pages
                               (map (fn [pid]
                                      (let [title (q/page-title db pid)
                                            date (journal/parse-journal-date title)]
                                        {:id pid
                                         :title title
                                         :date date
                                         :is-today? (= date today)
                                         :has-content? (has-content? db pid)})))
                               (filter #(journal/journal-page? (:title %))))
        ;; Check if today's journal exists
        today-exists? (some :is-today? existing-journals)
        ;; Filter to visible journals (today or has content) and sort
        ;; by date descending — pure chronological order. Today is kept
        ;; visible by the filter clause, not by pinning it to the top;
        ;; pinning would place today above a future-dated journal, which
        ;; reads backwards when you scroll a mix of past/today/future.
        journal-pages (->> existing-journals
                           (filter #(or (:is-today? %) (:has-content? %)))
                           (sort-by :date #(compare %2 %1)))
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

    ;; Auto-create today's journal if it doesn't exist (Logseq parity)
    (when (and (not today-exists?) on-intent)
      ;; Use js/setTimeout to avoid dispatching during render
      (js/setTimeout #(on-intent {:type :create-page :title today-human}) 0))

    [:div.journals-view
     ;; Header
     [:div.journals-header
      [:h2.journals-title "Journals"]
      [:span.journals-count (str (if today-exists? total (inc total)) " entries")]]

     ;; Journal pages list - fully editable
     (if (or (seq journal-pages) (not today-exists?))
       (into [:div.journals-list] journal-items)
       [:div.journals-empty
        [:p "No journal pages yet"]
        [:p.hint "Journal pages are date-formatted pages like 'Dec 14th, 2025'"]])]))
