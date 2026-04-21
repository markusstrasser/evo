(ns utils.journal-test
  "Tests for daily journal utilities."
  (:require [cljs.test :refer [deftest is testing]]
            [utils.journal :as journal]))

;; ── Ordinal Suffix Tests ────────────────────────────────────────────────────────

(deftest ordinal-suffix-test
  (testing "ordinal suffixes for day numbers"
    ;; Standard cases
    (is (= "Dec 1st, 2025" (journal/journal-title (js/Date. 2025 11 1))))
    (is (= "Dec 2nd, 2025" (journal/journal-title (js/Date. 2025 11 2))))
    (is (= "Dec 3rd, 2025" (journal/journal-title (js/Date. 2025 11 3))))
    (is (= "Dec 4th, 2025" (journal/journal-title (js/Date. 2025 11 4))))

    ;; Special cases: 11th, 12th, 13th (not 11st, 12nd, 13rd!)
    (is (= "Dec 11th, 2025" (journal/journal-title (js/Date. 2025 11 11))))
    (is (= "Dec 12th, 2025" (journal/journal-title (js/Date. 2025 11 12))))
    (is (= "Dec 13th, 2025" (journal/journal-title (js/Date. 2025 11 13))))

    ;; 21st, 22nd, 23rd
    (is (= "Dec 21st, 2025" (journal/journal-title (js/Date. 2025 11 21))))
    (is (= "Dec 22nd, 2025" (journal/journal-title (js/Date. 2025 11 22))))
    (is (= "Dec 23rd, 2025" (journal/journal-title (js/Date. 2025 11 23))))

    ;; 31st
    (is (= "Dec 31st, 2025" (journal/journal-title (js/Date. 2025 11 31))))))

;; ── Month Formatting Tests ──────────────────────────────────────────────────────

(deftest month-formatting-test
  (testing "month abbreviations"
    (is (= "Jan 1st, 2025" (journal/journal-title (js/Date. 2025 0 1))))
    (is (= "Feb 14th, 2025" (journal/journal-title (js/Date. 2025 1 14))))
    (is (= "Mar 15th, 2025" (journal/journal-title (js/Date. 2025 2 15))))
    (is (= "Apr 1st, 2025" (journal/journal-title (js/Date. 2025 3 1))))
    (is (= "May 5th, 2025" (journal/journal-title (js/Date. 2025 4 5))))
    (is (= "Jun 21st, 2025" (journal/journal-title (js/Date. 2025 5 21))))
    (is (= "Jul 4th, 2025" (journal/journal-title (js/Date. 2025 6 4))))
    (is (= "Aug 8th, 2025" (journal/journal-title (js/Date. 2025 7 8))))
    (is (= "Sep 9th, 2025" (journal/journal-title (js/Date. 2025 8 9))))
    (is (= "Oct 31st, 2025" (journal/journal-title (js/Date. 2025 9 31))))
    (is (= "Nov 11th, 2025" (journal/journal-title (js/Date. 2025 10 11))))
    (is (= "Dec 25th, 2025" (journal/journal-title (js/Date. 2025 11 25))))))

;; ── Journal Detection Tests ─────────────────────────────────────────────────────

(deftest journal-page-detection-test
  (testing "valid journal page titles with ordinal suffix"
    (is (true? (journal/journal-page? "Dec 11th, 2025")))
    (is (true? (journal/journal-page? "Jan 1st, 2024")))
    (is (true? (journal/journal-page? "Feb 2nd, 2025")))
    (is (true? (journal/journal-page? "Mar 3rd, 2023")))
    (is (true? (journal/journal-page? "Apr 21st, 2022"))))

  (testing "ISO format is still recognized"
    (is (true? (journal/journal-page? "2025-12-11"))))

  (testing "ordinal-less human form — legacy compat with pre-fix page-ref"
    ;; Older `page-ref` produced `[[Dec 11, 2025]]` (no ordinal). If any
    ;; such page-refs have been clicked, the resulting pages have these
    ;; titles and would otherwise be invisible to the Journals view.
    (is (true? (journal/journal-page? "Dec 11, 2025")))
    (is (true? (journal/journal-page? "Apr 19, 2026")))
    (is (true? (journal/journal-page? "Jan 1, 2024"))))

  (testing "invalid journal page titles"
    (is (false? (journal/journal-page? "My Notes")))
    (is (false? (journal/journal-page? "December 11th, 2025"))) ; Full month
    (is (false? (journal/journal-page? "Dec, 2025")))           ; No day
    (is (false? (journal/journal-page? "")))
    (is (false? (journal/journal-page? nil)))))

(deftest parse-journal-date-test
  (testing "ordinal form parses to ISO date"
    (is (= "2025-12-11" (journal/parse-journal-date "Dec 11th, 2025")))
    (is (= "2024-01-01" (journal/parse-journal-date "Jan 1st, 2024"))))

  (testing "ordinal-less form parses to the same ISO date"
    (is (= "2025-12-11" (journal/parse-journal-date "Dec 11, 2025")))
    (is (= "2026-04-19" (journal/parse-journal-date "Apr 19, 2026")))
    (is (= "2024-01-01" (journal/parse-journal-date "Jan 1, 2024"))))

  (testing "ISO passes through"
    (is (= "2025-12-11" (journal/parse-journal-date "2025-12-11"))))

  (testing "non-journal titles return nil"
    (is (nil? (journal/parse-journal-date "My Notes")))
    (is (nil? (journal/parse-journal-date nil)))))

(deftest page-ref-uses-ordinal-format-test
  (testing "page-ref emits canonical ordinal format so a click-through creates a recognized journal"
    (let [ref (journal/page-ref (js/Date. 2025 11 14))] ; 14th
      (is (= "[[Dec 14th, 2025]]" ref))
      ;; And the bracket-stripped title must satisfy journal-page?
      (is (true? (journal/journal-page? "Dec 14th, 2025")))))
  (testing "ordinals are correct for edge days"
    (is (= "[[Dec 1st, 2025]]"  (journal/page-ref (js/Date. 2025 11 1))))
    (is (= "[[Dec 2nd, 2025]]"  (journal/page-ref (js/Date. 2025 11 2))))
    (is (= "[[Dec 11th, 2025]]" (journal/page-ref (js/Date. 2025 11 11))))
    (is (= "[[Dec 21st, 2025]]" (journal/page-ref (js/Date. 2025 11 21))))))

;; ── Convenience Function Tests ──────────────────────────────────────────────────

(deftest convenience-functions-test
  (testing "today-title returns valid journal format"
    (is (journal/journal-page? (journal/today-title))))

  (testing "yesterday-title returns valid journal format"
    (is (journal/journal-page? (journal/yesterday-title))))

  (testing "tomorrow-title returns valid journal format"
    (is (journal/journal-page? (journal/tomorrow-title))))

  (testing "relative dates are different"
    (is (not= (journal/yesterday-title) (journal/today-title)))
    (is (not= (journal/today-title) (journal/tomorrow-title)))
    (is (not= (journal/yesterday-title) (journal/tomorrow-title)))))
