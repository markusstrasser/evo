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
    (is (all-text? "cljs$core$key"))
    (is (all-text? "function cljs$core$key(map_entry){return cljs.core._key(map_entry)}"))
    (is (all-text? "price$100$total")))
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

(deftest spaced-operator-stays-literal
  (testing "single * between words surrounded by spaces should not italicize"
    (is (all-text? "x = a * b * c"))
    (is (all-text? "1 * 2 * 3 = 6"))
    (is (all-text? "result = a_b * c_d")))
  (testing "intraword * with a pairing partner does not italicize"
    (is (all-text? "2*3*4 = 24"))
    (is (all-text? "glob=foo*bar*baz"))
    (is (all-text? "a*b*c"))))

(deftest pointer-and-kwargs-code-stays-literal
  (testing "C/C++ pointer syntax"
    (is (all-text? "int *p, int **pp"))
    (is (all-text? "char **argv"))
    (is (all-text? "foo(*p, **pp)")))
  (testing "Python/Clojure splat syntax"
    (is (all-text? "func(*args, **kwargs)"))
    (is (all-text? "(apply f *args)"))))

(deftest unicode-intraword-stays-literal
  (testing "Cyrillic, Japanese, German with accents"
    (is (all-text? "\u043f\u0440\u0438\u0432\u0435\u0442_\u043c\u0438\u0440_\u0442\u0435\u0441\u0442"))
    (is (all-text? "\u5909\u6570_\u540d\u524d"))
    (is (all-text? "\u00fcber_cool"))
    (is (all-text? "\u4fa1\u683c$\u5408\u8a08$\u5186")))
  (testing "bounded Unicode italicizes cleanly"
    ;; "say _здравствуй_ now" — spaces outside, text inside → italic
    (is (= [:text :italic :text]
           (types "say _\u0437\u0434\u0440\u0430\u0432\u0441\u0442\u0432\u0443\u0439_ now")))))

(deftest inner-whitespace-rejects-malformed-spans
  (testing "marker followed immediately by whitespace cannot open"
    (is (all-text? "** foo **"))
    (is (all-text? "_ foo _"))
    (is (all-text? "$ x+y $")))
  (testing "marker preceded by whitespace inside cannot close"
    (is (all-text? "**foo **"))
    (is (all-text? "**  foo**"))))

(deftest has-math-only-on-real-math
  (testing "has-math? returns true for parsed math segments"
    (is (fmt/has-math? "$x+y$"))
    (is (fmt/has-math? "see $x+y$ there"))
    (is (fmt/has-math? "$$E = mc^2$$")))
  (testing "has-math? returns false when dollar runs are rejected as intraword"
    (is (not (fmt/has-math? "cljs$core$key")))
    (is (not (fmt/has-math? "price$100$total")))
    (is (not (fmt/has-math? "function cljs$core$key(map_entry){}"))))
  (testing "has-math? is false for plain text and empty"
    (is (not (fmt/has-math? "hello world")))
    (is (not (fmt/has-math? "")))
    (is (not (fmt/has-math? nil)))))
