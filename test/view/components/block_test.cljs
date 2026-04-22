(ns view.components.block-test
  (:require [clojure.test :refer [deftest testing is]]
            [view.util :as vu]
            [components.block :as sut]
            [kernel.db :as db]
            [kernel.transaction :as tx]))

(defn- create-db [text]
  (let [ops [{:op :create-node :id "a" :type :block :props {:text text}}
             {:op :place :id "a" :under :doc :at :last}]
        base (db/empty-db)]
    (:db (tx/interpret base ops))))

(deftest block-rendering-test
  (testing "Block renders correctly"
    (let [db (create-db "Hello World")
          props {:db db :block-id "a" :depth 0 :on-intent (constantly nil)}
          view (sut/Block props)]
      (is (vector? view) "Returns hiccup vector")
      ;; data-block-id is on parent div.block, not on .block-content
      (is (= "a" (vu/select-attribute view :div.block :data-block-id))
          "Correct block ID attribute on parent")
      (let [el (vu/find-element view :.block-content)]
        (is (some? el) "Found .block-content element")
        ;; View mode now renders text as children (with page-ref support)
        ;; instead of using innerHTML via on-render hook
        (is (some #(= "Hello World" %) (rest el))
            "Text content rendered as children")))))

(defn- create-db-with-page-link-fixtures []
  (let [ops [{:op :create-node :id "target" :type :page :props {:title "Target Page"}}
             {:op :place :id "target" :under :doc :at :last}
             {:op :create-node :id "target-block" :type :block :props {:text "First excerpt line"}}
             {:op :place :id "target-block" :under "target" :at :last}
             {:op :create-node :id "demo" :type :page :props {:title "Demo"}}
             {:op :place :id "demo" :under :doc :at :last}
             {:op :create-node :id "card" :type :block
              :props {:text "[Read page](evo://page/Target%20Page)"}}
             {:op :place :id "card" :under "demo" :at :last}
             {:op :create-node :id "inline" :type :block
              :props {:text "See [Read page](evo://page/Target%20Page) now"}}
             {:op :place :id "inline" :under "demo" :at :last}]
        base (db/empty-db)]
    (:db (tx/interpret base ops))))

(deftest evo-page-link-only-block-renders-card
  (let [db (create-db-with-page-link-fixtures)
        view (sut/Block {:db db :block-id "card" :depth 0 :on-intent identity})]
    (testing "renders page preview card"
      (is (some? (vu/find-element view :.evo-page-card)))
      (is (= "evo://page/Target%20Page"
             (vu/select-attribute view :.evo-page-card-link :href)))
      (is (some? (vu/find-element view :.evo-page-card-title)))
      (is (= "Read pageTarget PageEvo pageFirst excerpt line"
             (vu/extract-text (vu/find-element view :.evo-page-card-link)))))))

(deftest evo-page-link-inline-renders-anchor
  (let [db (create-db-with-page-link-fixtures)
        view (sut/Block {:db db :block-id "inline" :depth 0 :on-intent identity})]
    (testing "renders inline evo link"
      (is (some? (vu/find-element view :.evo-page-link)))
      (is (= "evo://page/Target%20Page"
             (vu/select-attribute view :.evo-page-link :href)))
      (is (= "See Read page now"
             (vu/extract-text (vu/find-element view :.block-content)))))))
