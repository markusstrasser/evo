(ns components.all-pages
  "All Pages view - compact table layout with metadata.

   Design: Dense, information-rich table inspired by file managers.
   Shows: title, modified time, word count, block count.
   Color-coded sections for favorites/pages/journals."
  (:require [kernel.query :as q]
            [shell.view-state :as vs]
            [clojure.string :as str]))

;; ── Time Formatting ───────────────────────────────────────────────────────────

(defn- format-relative-time
  "Format timestamp as relative time (e.g., '2h ago', 'Dec 14')."
  [timestamp]
  (if (nil? timestamp)
    "—"
    (let [now (js/Date.now)
          diff-ms (- now timestamp)
          diff-min (/ diff-ms 1000 60)
          diff-hr (/ diff-min 60)
          diff-days (/ diff-hr 24)]
      (cond
        (< diff-min 1) "now"
        (< diff-hr 1) (str (int diff-min) "m")
        (< diff-days 1) (str (int diff-hr) "h")
        (< diff-days 7) (str (int diff-days) "d")
        :else
        (let [date (js/Date. timestamp)
              months ["Jan" "Feb" "Mar" "Apr" "May" "Jun"
                      "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]]
          (str (nth months (.getMonth date)) " " (.getDate date)))))))

;; ── Number Formatting ─────────────────────────────────────────────────────────

(defn- format-number
  "Format number with k suffix for thousands."
  [n]
  (cond
    (nil? n) "—"
    (< n 1000) (str n)
    :else (str (/ (int (* n 10) ) 10000.0) "k")))

;; ── Row ──────────────────────────────────────────────────────────────────────

(defn- page-row
  "Single row in the pages list."
  [{:keys [id title created-at updated-at word-count block-count favorite? on-intent]}]
  [:div.pages-row
   {:replicant/key id
    :on {:click (fn [e]
                  (.preventDefault e)
                  (vs/add-to-recents! id)
                  (on-intent {:type :switch-page :page-id id}))}}
   [:span.pages-cell.pages-cell--title
    (when favorite? [:span.pages-star "★"])
    [:span.pages-title-text title]]
   [:span.pages-cell.pages-cell--created
    (format-relative-time created-at)]
   [:span.pages-cell.pages-cell--modified
    (format-relative-time updated-at)]
   [:span.pages-cell.pages-cell--words
    (format-number word-count)]
   [:span.pages-cell.pages-cell--blocks
    block-count]])

;; ── Section List ─────────────────────────────────────────────────────────────

(defn- pages-list
  "List of pages for a section."
  [pages on-intent]
  [:div.pages-list
   (for [page pages]
     (page-row (assoc page :on-intent on-intent)))])

;; ── Main View ─────────────────────────────────────────────────────────────────

(defn AllPagesView
  "All Pages index - compact table layout.

   Structure:
   1. Summary stats
   2. Favorites table (if any)
   3. Pages table
   4. Journals table"
  [{:keys [db on-intent]}]
  (let [all-page-ids (q/all-pages db)
        favorites-set (vs/favorites)

        ;; Build full metadata for each page
        pages (->> all-page-ids
                   (map #(q/page-metadata db % favorites-set))
                   (filter #(and (:title %)
                                 (not (str/blank? (:title %)))
                                 (not= (:title %) "Untitled"))))

        ;; Separate into groups
        favorites (->> pages
                       (filter :favorite?)
                       (sort-by #(str/lower-case (:title %))))
        journals (->> pages
                      (filter :journal?)
                      (sort-by :title)
                      reverse)
        regular (->> pages
                     (filter #(and (not (:journal? %))
                                   (not (:favorite? %))))
                     (sort-by #(str/lower-case (:title %))))

        ;; Stats
        total-words (reduce + 0 (map :word-count pages))
        total-blocks (reduce + 0 (map :block-count pages))]

    [:article.pages-index

     ;; Summary header
     [:header.pages-summary
      [:span.pages-stat (str (count pages))]
      [:span.pages-stat-label "pages"]
      [:span.pages-sep "·"]
      [:span.pages-stat (format-number total-words)]
      [:span.pages-stat-label "words"]
      [:span.pages-sep "·"]
      [:span.pages-stat (str total-blocks)]
      [:span.pages-stat-label "blocks"]]

     ;; Column headers (shared)
     [:div.pages-header-row
      [:span.pages-header.pages-header--title "Title"]
      [:span.pages-header.pages-header--created "Created"]
      [:span.pages-header.pages-header--modified "Modified"]
      [:span.pages-header.pages-header--words "Words"]
      [:span.pages-header.pages-header--blocks "¶"]]

     ;; Favorites section
     (when (seq favorites)
       [:section.pages-section.pages-section--favorites
        [:h2.pages-section-label
         "Favorites"
         [:span.pages-section-count (count favorites)]]
        (pages-list favorites on-intent)])

     ;; Regular pages section
     (when (seq regular)
       [:section.pages-section.pages-section--regular
        [:h2.pages-section-label
         "Pages"
         [:span.pages-section-count (count regular)]]
        (pages-list regular on-intent)])

     ;; Journals section
     (when (seq journals)
       [:section.pages-section.pages-section--journals
        [:h2.pages-section-label
         "Journals"
         [:span.pages-section-count (count journals)]]
        (pages-list journals on-intent)])

     ;; Empty state
     (when (empty? pages)
       [:div.pages-empty
        [:p "No pages yet"]
        [:p.pages-empty-hint "Create a page from the sidebar"]])]))
