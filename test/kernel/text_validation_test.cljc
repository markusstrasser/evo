(ns kernel.text-validation-test
  "Unit tests for the block-text tripwire predicate."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.text-validation :as tv]))

(deftest valid-text-accepts-legitimate-content
  (testing "normal prose, punctuation, Unicode, math syntax all pass"
    (is (tv/valid-text? ""))
    (is (tv/valid-text? "Hello, world!"))
    (is (tv/valid-text? "cljs$core$key(map_entry)"))
    (is (tv/valid-text? "$x^2 + y^2 = r^2$"))
    (is (tv/valid-text? "привет_мир_тест"))
    (is (tv/valid-text? "価格$合計$円"))
    (is (tv/valid-text? "line one\nline two"))
    (is (tv/valid-text? "tab\there"))
    (is (tv/valid-text? "multi\nline\ntext")))
  (testing "non-strings pass (schema handles type shape)"
    (is (tv/valid-text? nil))
    (is (tv/valid-text? 42))
    (is (tv/valid-text? {}))))

(deftest valid-text-rejects-private-use-chars
  (testing "single MathJax glyph codepoint is rejected"
    (is (not (tv/valid-text? (str "hello " (char 0xE001)))))
    (is (not (tv/valid-text? (str (char 0xE000) "at start"))))
    (is (not (tv/valid-text? (str "at end" (char 0xF8FF)))))
    (is (not (tv/valid-text? (str "mid" (char 0xE500) "dle")))))
  (testing "reason is :private-use-char"
    (is (= :private-use-char
           (:reason (tv/invalid-text-reason (str "x" (char 0xE001))))))))

(deftest valid-text-rejects-scanner-markup
  (testing "MathJax output markup"
    (is (not (tv/valid-text? "hello <mjx-container>stuff</mjx-container>")))
    (is (not (tv/valid-text? "<mjx-math>"))))
  (testing "generic HTML leakage"
    (is (not (tv/valid-text? "<script>alert(1)</script>")))
    (is (not (tv/valid-text? "plain <style>body{}</style>"))))
  (testing "reason is :scanner-markup"
    (is (= :scanner-markup
           (:reason (tv/invalid-text-reason "<mjx-foo>"))))))

(deftest valid-text-rejects-control-chars
  (testing "null, backspace, FF, vertical tab, DEL"
    (is (not (tv/valid-text? (str "x" (char 0x00) "y"))))
    (is (not (tv/valid-text? (str (char 0x08)))))
    (is (not (tv/valid-text? (str "vt" (char 0x0B) "here"))))
    (is (not (tv/valid-text? (str (char 0x0C)))))
    (is (not (tv/valid-text? (str (char 0x1F))))))
  (testing "DEL (U+007F)"
    (is (not (tv/valid-text? (str "del" (char 0x7F))))))
  (testing "reason is :control-char"
    (is (= :control-char
           (:reason (tv/invalid-text-reason (str (char 0x00))))))))

(deftest precedence-order-is-stable
  (testing "private-use beats scanner-markup when both present"
    (is (= :private-use-char
           (:reason (tv/invalid-text-reason (str (char 0xE001) "<mjx-")))))))
