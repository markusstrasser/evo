(ns parser.inline-format-test
  (:require [clojure.test :refer [deftest is testing]]
            [parser.inline-format :as fmt]))

(defn- types [text]
  (mapv :type (fmt/split-with-formatting text)))

(defn- all-text?
  "True when every segment is plain text (order/splits irrelevant)."
  [text]
  (every? #(= :text (:type %)) (fmt/split-with-formatting text)))

(deftest intraword-underscores-stay-literal
  (testing "identifiers with underscores render as plain text only"
    (is (all-text? "cljs._key(map_entry)"))
    (is (all-text? "foo_bar_baz"))
    (is (all-text? "cljs$core$key(map_entry){return cljs.core._key(map_entry)}")))
  (testing "surrounding whitespace/punctuation still italicizes"
    (is (= [:italic] (types "_italic_")))
    (is (= [:text :italic :text] (types "say _hi_ now")))))

(deftest intraword-stars-stay-literal
  (testing "code like a*b*c stays literal"
    (is (all-text? "a*b*c")))
  (testing "bounded stars italicize"
    (is (= [:italic] (types "*italic*")))))

(deftest intraword-dollars-stay-literal
  (testing "cljs$core$key does not become inline math"
    (is (all-text? "cljs$core$key")))
  (testing "bounded dollars still parse as math"
    (is (= [:math-inline] (types "$x+y$")))
    (is (= [:text :math-inline :text] (types "see $x+y$ now")))))

(deftest multichar-markers-still-work
  (is (= [:bold] (types "**bold**")))
  (is (= [:bold] (types "__bold__")))
  (is (= [:highlight] (types "==hi==")))
  (is (= [:strikethrough] (types "~~gone~~")))
  (is (= [:math-block] (types "$$x^2$$"))))

(deftest mixed-inline-content
  (is (= [:text :bold :text :italic :text]
         (types "hello **world** and _emph_ here"))))
