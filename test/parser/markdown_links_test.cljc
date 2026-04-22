(ns parser.markdown-links-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [parser.markdown-links :as md-links]))

(deftest split-with-links-test
  (testing "text with one evo link"
    (is (= [{:type :text :value "See "}
            {:type :link
             :label "Essay"
             :target "evo://page/Crafting%20a%20Structural%20Text%20Editors"}
            {:type :text :value " now"}]
           (md-links/split-with-links
            "See [Essay](evo://page/Crafting%20a%20Structural%20Text%20Editors) now"))))

  (testing "text without links stays plain text"
    (is (= [{:type :text :value "plain text"}]
           (md-links/split-with-links "plain text"))))

  (testing "nil and empty are stable"
    (is (= [{:type :text :value ""}]
           (md-links/split-with-links nil)))
    (is (= [{:type :text :value ""}]
           (md-links/split-with-links "")))))

(deftest link-only-test
  (testing "detects a single markdown link with outer whitespace"
    (is (= {:label "Journal"
            :target "evo://page/Apr%2020th%2C%202026"}
           (md-links/link-only? "  [Journal](evo://page/Apr%2020th%2C%202026)  "))))

  (testing "rejects surrounding prose"
    (is (nil? (md-links/link-only? "See [Journal](evo://page/Apr%2020th%2C%202026) now")))))

(deftest parse-evo-target-test
  (testing "decodes page titles"
    (is (= {:type :page
            :page-name "Crafting a Structural Text Editors"}
           (md-links/parse-evo-target
            "evo://page/Crafting%20a%20Structural%20Text%20Editors"))))

  (testing "returns nil for non-evo links"
    (is (nil? (md-links/parse-evo-target "https://example.com")))))
