(ns parser.parse-roundtrip-test
  "Round-trip invariant: (parse (render-as-source (parse s))) = (parse s)
   for every source string s. Covers the full composed parse + all
   individual parsers (since parse emits a `:doc` wrapping every tag)."
  (:require #_{:clj-kondo/ignore [:unused-referred-var :unused-namespace]}
            [clojure.test :refer [deftest is testing]]
            #_{:clj-kondo/ignore [:unused-namespace]}
            [clojure.test.check.generators :as gen]
            #_{:clj-kondo/ignore [:unused-namespace]}
            [clojure.test.check.properties :as prop]
            #_{:clj-kondo/ignore [:unused-namespace]}
            [clojure.test.check.clojure-test :refer [defspec]]
            [parser.parse :as p]))

;; ── Shape-validity invariant ─────────────────────────────────────────────────

(deftest parse-shape
  (testing "parse returns a :doc root with known tags"
    (let [[tag attrs _] (p/parse "hello")]
      (is (= :doc tag))
      (is (map? attrs)))))

(deftest round-trip-concrete-cases
  (testing "round-trip holds on hand-crafted inputs"
    (doseq [s ["hello"
               "hello **world**"
               "hello __world__ end"
               "See [[Foo]] now"
               "See [[Foo]] and [[Bar]]"
               "See [[Journal/2026-04-25]] and [[日本語]]"
               "See [[Page, With. Punctuation's Stuff]]"
               "Invalid [[]] and [[outer [[inner]] rest]] stay plain"
               "pre ![cat](cat.png) post"
               "![cat](cat.png){width=200}"
               "text [label](https://x.com) trailing"
               "See [Foo](evo://page/Foo)"
               "See [today](evo://journal/2026-04-22)"
               "mix ==hi== and ~~bye~~ end"
               "math $E=mc^2$ inline"
               "block math $$\\int_0^\\infty x dx$$"
               "multi\nline\ntext"
               ""]]
      (is (= (p/parse s)
             (p/parse (p/render-as-source (p/parse s))))
          (str "round-trip failed for: " (pr-str s))))))

;; ── Property: round-trip on parser output ────────────────────────────────────

(def ^:private source-char-gen
  (gen/frequency
    [[6 gen/char-alphanumeric]
     [2 (gen/elements [\space \space \space \tab])]
     [1 (gen/elements [\* \_ \= \~ \$ \[ \] \( \) \! \{ \} \| \. \, \; \:])]]))

#_{:clj-kondo/ignore [:unused-private-var]} ; used inside defspec macro body
(def ^:private source-gen
  (gen/fmap #(apply str %) (gen/vector source-char-gen 0 60)))

(defspec round-trip-on-parser-output 100
  (prop/for-all [s source-gen]
    (let [ast (p/parse s)]
      (= ast (p/parse (p/render-as-source ast))))))
