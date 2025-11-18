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
      (let [el (vu/find-element view :.content-view)]
        (is (some? el) "Found .content-view element")
        (is (= "a" (vu/select-attribute view :.content-view :data-block-id))
            "Correct block ID attribute")
        (is (fn? (vu/select-attribute view :.content-view :replicant/on-render))
            "Lifecycle hook present for text rendering")))))
