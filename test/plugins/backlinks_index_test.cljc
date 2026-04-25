(ns plugins.backlinks-index-test
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [plugins.backlinks-index :as backlinks]))

(defn- sample-db []
  (:db (tx/interpret
        (db/empty-db)
        [{:op :create-node :id "source" :type :page :props {:title "Source"}}
         {:op :place :id "source" :under :doc :at :last}
         {:op :create-node :id "target" :type :page :props {:title "日本語"}}
         {:op :place :id "target" :under :doc :at :last}
         {:op :create-node :id "punct" :type :page :props {:title "Page, With. Punctuation's Stuff"}}
         {:op :place :id "punct" :under :doc :at :last}
         {:op :create-node :id "b1" :type :block :props {:text "See [[日本語]] and [[Page, With. Punctuation's Stuff]]"}}
         {:op :place :id "b1" :under "source" :at :last}
         {:op :create-node :id "b2" :type :block :props {:text "Ignore [[]] [[outer [[inner]] rest]] [[unclosed"}}
         {:op :place :id "b2" :under "source" :at :last}
         {:op :create-node :id "b3" :type :block :props {:text "Self [[日本語]]"}}
         {:op :place :id "b3" :under "target" :at :last}])))

(deftest backlinks-use-canonical-page-ref-grammar
  (let [db (update (sample-db) :derived merge (backlinks/compute-backlinks-index (sample-db)))]
    (testing "unicode and punctuation refs are indexed"
      (is (= ["b1"]
             (mapv :block-id (backlinks/get-backlinks db "日本語"))))
      (is (= ["b1"]
             (mapv :block-id (backlinks/get-backlinks db "Page, With. Punctuation's Stuff")))))

    (testing "invalid and nested refs are ignored"
      (is (= [] (backlinks/get-backlinks db "")))
      (is (= [] (backlinks/get-backlinks db "inner"))))))
