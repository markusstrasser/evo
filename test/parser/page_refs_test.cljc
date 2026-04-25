(ns parser.page-refs-test
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [parser.page-refs :as page-refs]))

(deftest extracts-canonical-page-refs
  (testing "accepted grammar"
    (let [text "See [[Alpha]], [[Journal/2026-04-25]], [[日本語]], and [[Page, With. Punctuation's Stuff]]"
          refs (page-refs/extract-refs text)]
      (is (= ["Alpha"
              "Journal/2026-04-25"
              "日本語"
              "Page, With. Punctuation's Stuff"]
             (mapv :name refs)))
      (is (= ["alpha"
              "journal/2026-04-25"
              "日本語"
              "page, with. punctuation's stuff"]
             (mapv :normalized refs))))))

(deftest rejects-invalid-page-refs
  (testing "invalid refs remain plain text"
    (doseq [text ["[[]]"
                  "[[   ]]"
                  "[[unclosed"
                  "[[line\nbreak]]"
                  "[[outer [[inner]] rest]]"]]
      (is (= [] (page-refs/extract-refs text)) text)
      (is (= [{:type :text :value text}]
             (page-refs/split-with-refs text))
          text))))

(deftest unclosed-ref-does-not-swallow-later-valid-ref
  (is (= ["link"]
         (mapv :name (page-refs/extract-refs "Typo [[ here. Valid [[link]]")))))

(deftest splits-text-with-page-refs
  (is (= [{:type :text :value "A "}
          {:type :page-ref
           :raw "[[日本語]]"
           :display "日本語"
           :name "日本語"
           :normalized "日本語"
           :start 2
           :end 9
           :inner-start 4
           :inner-end 7
           :page "日本語"}
          {:type :text :value " B"}]
         (page-refs/split-with-refs "A [[日本語]] B"))))

(deftest ref-at-uses-same-grammar
  (is (= "Page, With. Punctuation's Stuff"
         (:page-name (page-refs/ref-at "[[Page, With. Punctuation's Stuff]]" 5))))
  (is (nil? (page-refs/ref-at "[[outer [[inner]] rest]]" 12))))

(deftest replaces-page-refs-by-normalized-name
  (is (= "See [[New Page]] and [[New Page]]"
         (page-refs/replace-refs "See [[old page]] and [[Old Page]]" "Old Page" "New Page"))))

(deftest long-many-ref-input-is-bounded
  (let [text (apply str (repeat 200 "x [[Alpha]] y [[日本語]] "))]
    (is (= 400 (count (page-refs/extract-refs text))))))
