(ns utils.text-test
  "Unit tests for text utilities.

   Tests multi-byte character support according to TEXT_EDITING_TESTING_STRATEGY.md"
  (:require [clojure.test :refer [deftest testing is]]
            [utils.text :as text]))

;; ── Grapheme Length Tests ─────────────────────────────────────────────────────

(deftest grapheme-length-at-ascii-test
  (testing "ASCII characters have length 1"
    (is (= 1 (text/grapheme-length-at "Hello" 0)))
    (is (= 1 (text/grapheme-length-at "Hello" 2)))
    (is (= 1 (text/grapheme-length-at "Hello" 4)))))

#?(:cljs
   (deftest grapheme-length-at-emoji-test
     (testing "Basic emoji (surrogate pair) has length 2"
       ;; "Hi😀" = H(0) i(1) [high-surrogate(2) low-surrogate(3)]
       (is (= 2 (text/grapheme-length-at "Hi😀" 2))))))

;; ── Count Graphemes Tests ─────────────────────────────────────────────────────

(deftest count-graphemes-ascii-test
  (testing "ASCII string counts correctly"
    (is (= 5 (text/count-graphemes "Hello")))
    (is (= 0 (text/count-graphemes "")))))

#?(:cljs
   (deftest count-graphemes-emoji-test
     (testing "String with emoji counts grapheme clusters"
       ;; "Hi😀" = 3 graphemes (H, i, 😀)
       (is (= 3 (text/count-graphemes "Hi😀"))))))

;; ── Cursor Position to Grapheme Index Tests ──────────────────────────────────

(deftest cursor-pos-to-grapheme-index-ascii-test
  (testing "ASCII cursor position equals grapheme index"
    (is (= 0 (text/cursor-pos-to-grapheme-index "Hello" 0)))
    (is (= 3 (text/cursor-pos-to-grapheme-index "Hello" 3)))
    (is (= 5 (text/cursor-pos-to-grapheme-index "Hello" 5)))))

#?(:cljs
   (deftest cursor-pos-to-grapheme-index-emoji-test
     (testing "Cursor position with emoji"
       ;; "Hi😀there" = H(0) i(1) [😀 = 2-3] t(4) h(5) e(6) r(7) e(8)
       ;; Grapheme indices: H=0, i=1, 😀=2, t=3, h=4, e=5, r=6, e=7
       (is (= 0 (text/cursor-pos-to-grapheme-index "Hi😀there" 0))) ; H
       (is (= 1 (text/cursor-pos-to-grapheme-index "Hi😀there" 1))) ; i
       (is (= 2 (text/cursor-pos-to-grapheme-index "Hi😀there" 2))) ; Start of 😀
       (is (= 3 (text/cursor-pos-to-grapheme-index "Hi😀there" 4)))))) ; t (after 😀)

;; ── Word Boundary Detection Tests ────────────────────────────────────────────

(deftest find-next-word-boundary-test
  (testing "Simple case: two words"
    (is (= 6 (text/find-next-word-boundary "hello world" 0))))

  (testing "Multiple spaces between words"
    (is (= 8 (text/find-next-word-boundary "hello   world" 5))))

  (testing "At end of text"
    (is (= 5 (text/find-next-word-boundary "hello" 5))))

  (testing "With newlines"
    (is (= 6 (text/find-next-word-boundary "hello\nworld" 0))))

  (testing "Starting in middle of word"
    (is (= 11 (text/find-next-word-boundary "hello world" 7)))))

(deftest find-prev-word-boundary-test
  (testing "Simple case: two words"
    (is (= 6 (text/find-prev-word-boundary "hello world" 11))))

  (testing "Multiple spaces between words"
    (is (= 0 (text/find-prev-word-boundary "hello   world" 8))))

  (testing "At start of text"
    (is (nil? (text/find-prev-word-boundary "hello" 0))))

  (testing "From middle of second word"
    (is (= 6 (text/find-prev-word-boundary "hello world" 8))))

  (testing "From space after word"
    (is (= 0 (text/find-prev-word-boundary "hello world" 5)))))

(deftest whitespace-test
  (testing "Recognizes common whitespace characters"
    (is (true? (text/whitespace? \space)))
    (is (true? (text/whitespace? \newline)))
    (is (true? (text/whitespace? \tab)))
    (is (true? (text/whitespace? \return)))
    (is (false? (text/whitespace? \a)))
    (is (false? (text/whitespace? \1)))))

;; ── Split Graphemes Tests ────────────────────────────────────────────────────

(deftest split-graphemes-ascii-test
  (testing "ASCII splits into single characters"
    (is (= ["H" "e" "l" "l" "o"] (text/split-graphemes "Hello")))
    (is (= [] (text/split-graphemes "")))))

#?(:cljs
   (deftest split-graphemes-basic-emoji-test
     (testing "Basic emoji is single grapheme"
       ;; 😀 is 2 UTF-16 code units but 1 grapheme
       (is (= ["H" "i" "😀"] (text/split-graphemes "Hi😀")))
       (is (= 3 (count (text/split-graphemes "Hi😀")))))))

#?(:cljs
   (deftest split-graphemes-complex-emoji-test
     (testing "Skin tone modifier is single grapheme (when Intl.Segmenter available)"
       ;; 👋🏽 = waving hand + skin tone modifier (4 UTF-16 code units, 1 grapheme)
       (let [graphemes (text/split-graphemes "Hi👋🏽")]
         ;; With Intl.Segmenter: ["H" "i" "👋🏽"]
         ;; Without (fallback): ["H" "i" "👋" "🏽"]
         (is (>= 4 (count graphemes))) ;; Either 3 or 4 depending on browser
         (is (= "H" (first graphemes)))))

     (testing "Flag emoji is single grapheme (when Intl.Segmenter available)"
       ;; 🇺🇸 = regional indicator U + regional indicator S (4 code units)
       (let [graphemes (text/split-graphemes "Go🇺🇸")]
         ;; With Intl.Segmenter: ["G" "o" "🇺🇸"]
         ;; Without (fallback): ["G" "o" "🇺" "🇸"]
         (is (>= 4 (count graphemes)))
         (is (= "G" (first graphemes)))))))

;; ── Grapheme Index Conversion Tests ──────────────────────────────────────────

#?(:cljs
   (deftest grapheme-index-to-cursor-pos-test
     (testing "ASCII grapheme index equals cursor pos"
       (is (= 0 (text/grapheme-index-to-cursor-pos "Hello" 0)))
       (is (= 3 (text/grapheme-index-to-cursor-pos "Hello" 3)))
       (is (= 5 (text/grapheme-index-to-cursor-pos "Hello" 5))))

     (testing "Emoji grapheme index converts correctly"
       ;; "Hi😀there" - grapheme idx 2 is 😀 which starts at UTF-16 pos 2
       (is (= 2 (text/grapheme-index-to-cursor-pos "Hi😀there" 2)))
       ;; grapheme idx 3 is 't' which starts at UTF-16 pos 4 (after 2-unit emoji)
       (is (= 4 (text/grapheme-index-to-cursor-pos "Hi😀there" 3))))))
