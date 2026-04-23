(ns shell.render-registry-test
  "Unit tests for shell.render-registry.

   Uses private tag names (:render-test.*) so tests never collide with
   the production manifest's handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [shell.render-registry :as r]))

(deftest register-and-dispatch
  (testing "handler receives node and ctx"
    (r/register-render! :render-test.foo
      {:handler (fn [node ctx] [:div {:render-test.tag (nth node 0) :ctx ctx} (nth node 2)])})
    (is (= [:div {:render-test.tag :render-test.foo :ctx {:k 1}} "x"]
           (r/render-node [:render-test.foo {} "x"] {:k 1})))))

(deftest unknown-tag-throws
  (testing "render-node throws on unknown tag"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (r/render-node [:never-registered {} "x"] {})))))

(deftest render-all-maps-siblings
  (testing "render-all dispatches per child"
    (r/register-render! :render-test.t
      {:handler (fn [node _] (str "<" (nth node 2) ">"))})
    (is (= ["<a>" "<b>" "<c>"]
           (r/render-all [[:render-test.t {} "a"] [:render-test.t {} "b"] [:render-test.t {} "c"]] nil)))))

(deftest re-register-replaces
  (testing "re-registering the same tag replaces the handler"
    (r/register-render! :render-test.r {:handler (fn [_ _] :first)})
    (is (= :first (r/render-node [:render-test.r {} ""] nil)))
    (r/register-render! :render-test.r {:handler (fn [_ _] :second)})
    (is (= :second (r/render-node [:render-test.r {} ""] nil)))))
