(ns evolver.core-test
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.kernel :as k]
            [evolver.intents :as intents]
            [evolver.renderer :as renderer]))

(deftest create-child-block-test
  (testing "Creating a child block"
    (let [initial-db {:nodes {k/root-id {:type :div :props {:text "Root"}}
                              "p1" {:type :div :props {:text "Parent"}}}
                      :children-by-parent {k/root-id ["p1"]}}
          ops (intents/create-child-block initial-db {:cursor "p1"})]
      (is (= 1 (count ops)))
      (let [op (first ops)]
        (is (= :insert (:op op)))
        (is (= "p1" (:parent-id op)))
        (is (= "New child" (get-in op [:node-data :props :text])))))))

(deftest indent-block-test
  (testing "Indenting a block with siblings"
    (let [initial-db (-> {:nodes {k/root-id {:type :div :props {:text "Root"}}
                                  "p1" {:type :div :props {:text "Parent 1"}}
                                  "p2" {:type :div :props {:text "Parent 2"}}}
                          :children-by-parent {k/root-id ["p1" "p2"]}}
                         (k/update-derived)) ; Must call update-derived to populate :derived metadata
          ops (intents/indent initial-db {:cursor "p2"})]
      (is (= 1 (count ops)))
      (let [op (first ops)]
        (is (= :move (:op op)))
        (is (= "p2" (:node-id op)))
        (is (= "p1" (:new-parent-id op)))))))

(deftest render-node-with-children-test
  (testing "Node with children shows parent text"
    (let [db {:nodes {k/root-id {:type :div :props {:text "Root"}}
                      "p1" {:type :div :props {:text "Parent"}}
                      "c1" {:type :div :props {:text "Child"}}}
              :children-by-parent {k/root-id ["p1"]
                                   "p1" ["c1"]}
              :view {:selection []
                     :collapsed #{}
                     :hovered-referencers #{}}
              :computed {:referenced-nodes #{}}}
          rendered (renderer/render-node db "p1")]
      (is (vector? rendered))
      (is (= :div (first rendered)))
      (is (= "Parent" (nth rendered 2))) ; Third element should be the parent text
      (is (= 4 (count rendered))) ; Should have element, attributes, text, and child
      )))

(deftest render-node-without-children-test
  (testing "Leaf node shows text"
    (let [db {:nodes {"leaf" {:type :div :props {:text "Leaf text"}}}
              :children-by-parent {}
              :view {:selection []
                     :collapsed #{}
                     :hovered-referencers #{}}
              :computed {:referenced-nodes #{}}}
          rendered (renderer/render-node db "leaf")]
      (is (vector? rendered))
      (is (= :div (first rendered)))
      (is (= "Leaf text" (nth rendered 2))) ; Third element should be the text
      (is (= 3 (count rendered))) ; Should have element, attributes, and text only
      )))