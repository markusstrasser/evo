(ns view.util-test
  "Tests for view.util helper functions"
  (:require [clojure.test :refer [deftest testing is]]
            [view.util :as vu]))

(deftest parse-tag-test
  (testing "Parse simple tag"
    (is (= {:tag "div" :classes [] :id nil}
           (vu/parse-tag :div))))

  (testing "Parse tag with class"
    (is (= {:tag "span" :classes ["block-content"] :id nil}
           (vu/parse-tag :span.block-content))))

  (testing "Parse tag with multiple classes"
    (is (= {:tag "div" :classes ["foo" "bar"] :id nil}
           (vu/parse-tag :div.foo.bar))))

  (testing "Parse tag with id"
    (is (= {:tag "div" :classes [] :id "foo"}
           (vu/parse-tag :div#foo))))

  (testing "Parse tag with class and id"
    (is (= {:tag "div" :classes ["foo"] :id "bar"}
           (vu/parse-tag :div.foo#bar)))))

(deftest tag-matches-test
  (testing "Match simple tag"
    (is (vu/tag-matches? [:div] :div))
    (is (not (vu/tag-matches? [:span] :div))))

  (testing "Match tag with class in tag"
    (is (vu/tag-matches? [:span.block-content {}] :span.block-content))
    (is (vu/tag-matches? [:span.block-content {}] :.block-content)
        ":.block-content (class-only) should match :span.block-content")
    (is (vu/tag-matches? [:span.block-content {}] "block-content")))

  (testing "Match tag with class in attrs"
    (is (vu/tag-matches? [:span {:class ["block-content"]}] :span.block-content))
    (is (vu/tag-matches? [:span {:class ["block-content"]}] :.block-content))
    (is (vu/tag-matches? [:span {:class ["block-content"]}] "block-content"))))

(deftest find-element-test
  (testing "Find simple element"
    (let [hiccup [:div [:span "Hello"]]]
      (is (= [:span "Hello"]
             (vu/find-element hiccup :span)))))

  (testing "Find element with class in tag"
    (let [hiccup [:div [:span.block-content {} "Hello"]]]
      (is (= [:span.block-content {} "Hello"]
             (vu/find-element hiccup :span.block-content)))
      (is (= [:span.block-content {} "Hello"]
             (vu/find-element hiccup :.block-content)))))

  (testing "Find nested element"
    (let [hiccup [:div [:p [:span.foo {} "Nested"]]]]
      (is (= [:span.foo {} "Nested"]
             (vu/find-element hiccup :.foo))))))

(deftest extract-text-test
  (testing "Extract from simple string"
    (is (= "Hello" (vu/extract-text "Hello"))))

  (testing "Extract from element with string"
    (is (= "Hello" (vu/extract-text [:div "Hello"]))))

  (testing "Extract from element with seq of strings"
    (is (= "Hello world" (vu/extract-text [:div (list "Hello world")]))))

  (testing "Extract from nested elements"
    (is (= "HelloWorld" (vu/extract-text [:div "Hello" [:span "World"]])))))
