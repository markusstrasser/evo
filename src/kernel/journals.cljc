(ns kernel.journals
  "Pure journal projection helpers.

   These helpers keep journal ordering semantic and testable. DOM adjacency
   remains a browser geometry concern, not the source of journal navigation
   truth."
  (:require [clojure.string :as str]
            [kernel.query :as q]))

(defn journal-page?
  [title]
  (boolean
   (when title
     (or (re-matches #"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th)?, \d{4}" title)
         (re-matches #"\d{4}-\d{2}-\d{2}" title)))))

(defn parse-journal-date
  [title]
  (when title
    (cond
      (re-matches #"\d{4}-\d{2}-\d{2}" title)
      title

      (re-matches #"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th)?, \d{4}" title)
      (let [months {"Jan" "01" "Feb" "02" "Mar" "03" "Apr" "04"
                    "May" "05" "Jun" "06" "Jul" "07" "Aug" "08"
                    "Sep" "09" "Oct" "10" "Nov" "11" "Dec" "12"}
            [_ mon day _ year] (re-matches
                                #"([A-Z][a-z]{2}) (\d{1,2})(st|nd|rd|th)?, (\d{4})"
                                title)]
        (when (and mon day year)
          (str year "-" (months mon) "-" (when (< (count day) 2) "0") day)))

      :else nil)))

(defn journal-page-content?
  "True if PAGE-ID has any descendant block with non-blank text."
  [db page-id]
  (some (fn [id]
          (not (str/blank? (q/block-text db id))))
        (q/descendants-of db page-id)))

(defn visible-journal-pages
  "Return journal page maps sorted newest first.

   Options:
   - :today-iso keeps today's page visible even if empty."
  [db {:keys [today-iso]}]
  (->> (q/all-pages db)
       (keep (fn [page-id]
               (let [title (q/page-title db page-id)
                     date (parse-journal-date title)]
                 (when (journal-page? title)
                   {:id page-id
                    :title title
                    :date date
                    :is-today? (= date today-iso)
                    :has-content? (boolean (journal-page-content? db page-id))}))))
       (filter #(or (:is-today? %) (:has-content? %)))
       (sort-by :date #(compare %2 %1))
       vec))

(defn journals-visible-blocks
  "Return selectable journal block IDs in semantic journal order.

   Headings are page IDs and are intentionally excluded. Each journal page
   contributes its own visible outline order under current fold state."
  [db session opts]
  (vec
   (mapcat (fn [{page-id :id}]
             (q/selectable-visible-blocks
              db
              (assoc-in session [:ui :current-page] page-id)))
           (visible-journal-pages db opts))))

(defn journals-mode?
  "True when the session is rendering the journals (cross-page) view."
  [session]
  (boolean (get-in session [:ui :journals-view?])))

(defn- journal-block-at-offset
  [db session current-id offset]
  (let [;; Today-iso is irrelevant for navigation: empty pages contribute
        ;; zero blocks whether or not today's empty page is included.
        blocks (journals-visible-blocks db session {})
        idx (.indexOf blocks current-id)
        target (+ idx offset)]
    (when (and (not= idx -1) (>= target 0) (< target (count blocks)))
      (nth blocks target))))

(defn next-block-in-journals
  "Next selectable block in concatenated journal order. Crosses page boundaries.
   Returns nil if CURRENT-ID is the last block across all visible journals."
  [db session current-id]
  (journal-block-at-offset db session current-id 1))

(defn prev-block-in-journals
  "Previous selectable block in concatenated journal order. Crosses page boundaries.
   Returns nil if CURRENT-ID is the first block across all visible journals."
  [db session current-id]
  (journal-block-at-offset db session current-id -1))
