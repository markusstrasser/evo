(ns view-util-test
  "Tests for view-util functions"
  (:require [clojure.test :refer [deftest testing is]]
            [view-util :as vu]))

(deftest parse-tag-test
  (testing "Parse simple tag"
    (is (= {:tag "div" :classes [] :id nil}
           (vu/parse-tag :div))))

  (testing "Parse tag with class"
    (is (= {:tag "span" :classes ["content-view"] :id nil}
           (vu/parse-tag :span.content-view))))

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
    (is (vu/tag-matches? [:span.content-view {}] :span.content-view))
    (is (vu/tag-matches? [:span.content-view {}] :.content-view)
        ":.content-view (class-only) should match :span.content-view")
    (is (vu/tag-matches? [:span.content-view {}] "content-view")))

  (testing "Match tag with class in attrs"
    (is (vu/tag-matches? [:span {:class ["content-view"]}] :span.content-view))
    (is (vu/tag-matches? [:span {:class ["content-view"]}] :.content-view))
    (is (vu/tag-matches? [:span {:class ["content-view"]}] "content-view"))))

(deftest find-element-test
  (testing "Find simple element"
    (let [hiccup [:div [:span "Hello"]]]
      (is (= [:span "Hello"]
             (vu/find-element hiccup :span)))))

  (testing "Find element with class in tag"
    (let [hiccup [:div [:span.content-view {} "Hello"]]]
      (is (= [:span.content-view {} "Hello"]
             (vu/find-element hiccup :span.content-view)))
      (is (= [:span.content-view {} "Hello"]
             (vu/find-element hiccup :.content-view)))))

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
