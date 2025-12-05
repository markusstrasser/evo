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
      ;; data-block-id is on parent div.block, not on .content-view
      (is (= "a" (vu/select-attribute view :div.block :data-block-id))
          "Correct block ID attribute on parent")
      (let [el (vu/find-element view :.content-view)]
        (is (some? el) "Found .content-view element")
        ;; View mode now renders text as children (with page-ref support)
        ;; instead of using innerHTML via on-render hook
        (is (some #(= "Hello World" %) (rest el))
            "Text content rendered as children")))))
