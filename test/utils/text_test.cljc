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
       (is (= 0 (text/cursor-pos-to-grapheme-index "Hi😀there" 0)))  ; H
       (is (= 1 (text/cursor-pos-to-grapheme-index "Hi😀there" 1)))  ; i
       (is (= 2 (text/cursor-pos-to-grapheme-index "Hi😀there" 2)))  ; Start of 😀
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
