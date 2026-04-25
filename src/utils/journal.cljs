(ns utils.journal
  "Daily journal utilities.

   Provides date formatting for journal page titles matching Logseq defaults.

   Format: 'MMM do, yyyy' (e.g., 'Dec 11th, 2025')

   See docs/DAILY_JOURNAL_SPEC.md for full specification."
  (:require [parser.page-refs :as page-refs]))

;; ── Date Formatting ──────────────────────────────────────────────────────────

(defn- ordinal-suffix
  "Get ordinal suffix for day number.
   1, 21, 31 → 'st'
   2, 22 → 'nd'
   3, 23 → 'rd'
   Everything else → 'th'"
  [day]
  (cond
    (contains? #{11 12 13} day) "th" ; Special case: 11th, 12th, 13th
    (= 1 (mod day 10)) "st"
    (= 2 (mod day 10)) "nd"
    (= 3 (mod day 10)) "rd"
    :else "th"))

(defn- format-month
  "Format month as 3-letter abbreviation (Jan, Feb, Mar, etc.)"
  [date]
  (.toLocaleString date "en-US" #js {:month "short"}))

(defn journal-title
  "Get journal page title for a date.

   Format: 'MMM do, yyyy' (matches Logseq default)
   Example: 'Dec 11th, 2025'

   With no args, returns today's journal title."
  ([]
   (journal-title (js/Date.)))
  ([date]
   (let [month (format-month date)
         day (.getDate date)
         suffix (ordinal-suffix day)
         year (.getFullYear date)]
     (str month " " day suffix ", " year))))

(defn today-title
  "Get today's journal page title."
  []
  (journal-title))

(defn yesterday-title
  "Get yesterday's journal page title."
  []
  (let [date (js/Date.)
        _ (.setDate date (dec (.getDate date)))]
    (journal-title date)))

(defn tomorrow-title
  "Get tomorrow's journal page title."
  []
  (let [date (js/Date.)
        _ (.setDate date (inc (.getDate date)))]
    (journal-title date)))

;; ── Page Reference Formatting ────────────────────────────────────────────────

(defn page-ref
  "Format a date as a page reference string like [[Dec 10th, 2025]].

   Uses the canonical ordinal format (`MMM do, yyyy`) so that a click
   through the ref creates a page whose title `journal-page?` recognizes.
   Prior versions omitted the ordinal (producing `[[Dec 10, 2025]]`);
   the resulting page was a valid page but not a *journal*, and the
   Journals view silently filtered it out."
  ([]
   (page-ref (js/Date.)))
  ([date]
   (let [month (format-month date)
         day (.getDate date)
         suffix (ordinal-suffix day)
         year (.getFullYear date)]
     (page-refs/format-ref (str month " " day suffix ", " year)))))

(defn today-page-ref
  "Get today's date as a page reference [[MMM d, yyyy]]."
  []
  (page-ref))

(defn yesterday-page-ref
  "Get yesterday's date as a page reference [[MMM d, yyyy]]."
  []
  (let [date (js/Date.)
        _ (.setDate date (dec (.getDate date)))]
    (page-ref date)))

(defn tomorrow-page-ref
  "Get tomorrow's date as a page reference [[MMM d, yyyy]]."
  []
  (let [date (js/Date.)
        _ (.setDate date (inc (.getDate date)))]
    (page-ref date)))

;; ── Time Formatting ──────────────────────────────────────────────────────────

(defn current-time
  "Get current time in HH:MM format."
  []
  (let [d (js/Date.)
        hours (.getHours d)
        minutes (.getMinutes d)
        pad #(if (< % 10) (str "0" %) (str %))]
    (str (pad hours) ":" (pad minutes))))

;; ── Journal Detection ────────────────────────────────────────────────────────

(defn journal-page?
  "Check if a page title matches a journal date format.
   Matches 'MMM do, yyyy' (e.g. 'Dec 14th, 2025'), the ordinal-less
   'MMM d, yyyy' (e.g. 'Dec 14, 2025') that older `page-ref` produced,
   and ISO 'YYYY-MM-DD'. The ordinal-less branch exists so user data
   created by the pre-fix `page-ref` still classifies as a journal."
  [title]
  (boolean
   (when title
     (or (re-matches #"[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th)?, \d{4}" title)
         (re-matches #"\d{4}-\d{2}-\d{2}" title)))))

(defn parse-journal-date
  "Parse journal title to sortable ISO date (YYYY-MM-DD).
   Returns nil if not a valid journal format. Accepts both ordinal and
   ordinal-less human forms (see `journal-page?`)."
  [title]
  (when title
    (cond
      ;; ISO format: 2025-12-14
      (re-matches #"\d{4}-\d{2}-\d{2}" title)
      title

      ;; Human format, ordinal suffix optional:
      ;;   "Dec 14th, 2025" or "Dec 14, 2025" → "2025-12-14"
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
