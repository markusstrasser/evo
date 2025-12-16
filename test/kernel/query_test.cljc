(ns kernel.query-test
  "Tests for kernel.query - page metadata queries"
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.query :as q]
            [kernel.transaction :as tx]))

(defn- apply-ops
  "Apply ops and return final db."
  ([ops] (apply-ops (db/empty-db) ops))
  ([db ops]
   (let [result (tx/interpret db ops)]
     (when (seq (:issues result))
       (is false (str "Unexpected issues: " (:issues result))))
     (:db result))))

;; ── Page Metadata Queries ─────────────────────────────────────────────────────

(deftest test-created-at-query
  (testing "created-at returns node's creation timestamp"
    (let [ts 1609459200000
          db (apply-ops [{:op :create-node
                          :id "page-1"
                          :type :page
                          :props {:title "Test" :created-at ts}}])]
      (is (= ts (q/created-at db "page-1"))))))

(deftest test-updated-at-query
  (testing "updated-at returns node's last update timestamp"
    (let [ts 1609459200000
          db (apply-ops [{:op :create-node
                          :id "page-1"
                          :type :page
                          :props {:title "Test" :updated-at ts}}])]
      (is (= ts (q/updated-at db "page-1"))))))

(deftest test-page-block-count
  (testing "page-block-count counts all descendant blocks"
    (let [db (apply-ops [{:op :create-node :id "page" :type :page :props {:title "Test"}}
                         {:op :place :id "page" :under :doc :at :last}
                         {:op :create-node :id "b1" :type :block :props {:text "one"}}
                         {:op :place :id "b1" :under "page" :at :last}
                         {:op :create-node :id "b2" :type :block :props {:text "two"}}
                         {:op :place :id "b2" :under "page" :at :last}
                         {:op :create-node :id "b3" :type :block :props {:text "nested"}}
                         {:op :place :id "b3" :under "b1" :at :last}])]
      (is (= 3 (q/page-block-count db "page"))))))

(deftest test-page-word-count
  (testing "page-word-count sums words across all blocks"
    (let [db (apply-ops [{:op :create-node :id "page" :type :page :props {:title "Test"}}
                         {:op :place :id "page" :under :doc :at :last}
                         {:op :create-node :id "b1" :type :block :props {:text "hello world"}}
                         {:op :place :id "b1" :under "page" :at :last}
                         {:op :create-node :id "b2" :type :block :props {:text "foo bar baz"}}
                         {:op :place :id "b2" :under "page" :at :last}])]
      (is (= 5 (q/page-word-count db "page"))))))

(deftest test-page-metadata
  (testing "page-metadata returns all metadata in one call"
    (let [created 1609459200000
          updated 1609545600000
          db (apply-ops [{:op :create-node
                          :id "my-page"
                          :type :page
                          :props {:title "My Page"
                                  :created-at created
                                  :updated-at updated}}
                         {:op :place :id "my-page" :under :doc :at :last}
                         {:op :create-node :id "b1" :type :block :props {:text "hello world"}}
                         {:op :place :id "b1" :under "my-page" :at :last}])
          meta (q/page-metadata db "my-page" #{})]
      (is (= "my-page" (:id meta)))
      (is (= "My Page" (:title meta)))
      (is (= created (:created-at meta)))
      (is (= updated (:updated-at meta)))
      (is (= 1 (:block-count meta)))
      (is (= 2 (:word-count meta)))
      (is (false? (:favorite? meta))))))

(deftest test-page-metadata-favorites
  (testing "page-metadata correctly identifies favorites"
    (let [db (apply-ops [{:op :create-node :id "fav" :type :page :props {:title "Fav"}}
                         {:op :place :id "fav" :under :doc :at :last}])
          favorites #{"fav"}
          meta (q/page-metadata db "fav" favorites)]
      (is (true? (:favorite? meta))))))

(deftest test-page-metadata-journal-detection
  (testing "page-metadata detects journal pages"
    (let [db (apply-ops [{:op :create-node :id "j1" :type :page :props {:title "Dec 16th, 2025"}}
                         {:op :place :id "j1" :under :doc :at :last}
                         {:op :create-node :id "j2" :type :page :props {:title "2025-12-16"}}
                         {:op :place :id "j2" :under :doc :at :last}
                         {:op :create-node :id "p1" :type :page :props {:title "Regular Page"}}
                         {:op :place :id "p1" :under :doc :at :last}])]
      (is (true? (:journal? (q/page-metadata db "j1" #{}))) "Human date format")
      (is (true? (:journal? (q/page-metadata db "j2" #{}))) "ISO date format")
      (is (false? (:journal? (q/page-metadata db "p1" #{}))) "Regular page"))))
