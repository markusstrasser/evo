(ns integration.anchor-test
  "Integration tests for unified anchor algebra.

   Tests that anchor resolution is consistent across validation and apply phases.
   This locks down the 'single source of truth' behavior from Step 1 refactor."
  (:require [clojure.test :refer [deftest is testing]]
            [test-helper :as helper]
            [kernel.api :as api]
            [kernel.query :as q]))

(deftest anchor-first
  (testing "Anchor :first places at beginning"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["d"]
                                           :parent "page"
                                           :anchor :first})]
      (is (= ["d" "a" "b" "c"] (q/children db "page"))
          "Node should move to first position"))))

(deftest anchor-last
  (testing "Anchor :last places at end"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["a"]
                                           :parent "page"
                                           :anchor :last})]
      (is (= ["b" "c" "d" "a"] (q/children db "page"))
          "Node should move to last position"))))

(deftest anchor-before
  (testing "Anchor {:before x} places before specified sibling"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["d"]
                                           :parent "page"
                                           :anchor {:before "b"}})]
      (is (= ["a" "d" "b" "c"] (q/children db "page"))
          "Node should move before target"))))

(deftest anchor-after
  (testing "Anchor {:after x} places after specified sibling"
    (let [db0 (helper/demo-db)
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["a"]
                                           :parent "page"
                                           :anchor {:after "c"}})]
      (is (= ["b" "c" "a" "d"] (q/children db "page"))
          "Node should move after target"))))

(deftest anchor-references-moving-node
  (testing "Anchor references the moving node (legal, should behave as if removed first)"
    (let [db0 (helper/demo-db)
          ;; Move 'b' to position {:after "b"} - should be treated as moving to
          ;; position after 'b' in the list without 'b', which is after 'c'
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["b"]
                                           :parent "page"
                                           :anchor {:after "c"}})]
      (is (= ["a" "c" "b" "d"] (q/children db "page"))
          "Moving node with anchor to itself should work (removed before resolution)"))))

(deftest anchor-after-last-element
  (testing "Anchor {:after last} places at end"
    (let [db0 (helper/demo-db)
          ;; Move 'a' after 'd' (the last element)
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["a"]
                                           :parent "page"
                                           :anchor {:after "d"}})]
      (is (= ["b" "c" "d" "a"] (q/children db "page"))
          "Moving after last element should place at end"))))

(deftest anchor-before-first-element
  (testing "Anchor {:before first} places at beginning"
    (let [db0 (helper/demo-db)
          ;; Move 'd' before 'a' (the first element)
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["d"]
                                           :parent "page"
                                           :anchor {:before "a"}})]
      (is (= ["d" "a" "b" "c"] (q/children db "page"))
          "Moving before first element should place at beginning"))))

(deftest multiple-moves-with-anchors
  (testing "Multiple move operations maintain correct anchor resolution"
    (let [db0 (helper/demo-db)
          ;; Move 'a' after 'c'
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["a"]
                                           :parent "page"
                                           :anchor {:after "c"}})
          ;; Then move 'd' before 'b'
          {:keys [db]} (api/dispatch db {:type :move
                                          :selection ["d"]
                                          :parent "page"
                                          :anchor {:before "b"}})]
      (is (= ["d" "b" "c" "a"] (q/children db "page"))
          "Multiple moves should maintain anchor consistency"))))

(deftest anchor-cross-parent-move
  (testing "Anchor resolution works when moving across parents"
    (let [db0 (helper/demo-db)
          ;; Move 'a' under 'd' at first position
          {:keys [db]} (api/dispatch db0 {:type :move
                                           :selection ["a"]
                                           :parent "d"
                                           :anchor :first})]
      (is (= ["b" "c" "d"] (q/children db "page"))
          "Original parent should have node removed")
      (is (= ["a" "d1"] (q/children db "d"))
          "Target parent should have node at anchor position"))))
