(ns plugins.autocomplete-test
  "Tests for plugins.autocomplete - page-ref filtering"
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [plugins.autocomplete :as ac]))

(defn- apply-ops
  "Apply ops and return final db."
  ([ops] (apply-ops (db/empty-db) ops))
  ([db ops]
   (let [result (tx/interpret db ops)]
     (:db result))))

(defn- make-page [id title]
  [{:op :create-node :id id :type :page :props {:title title}}
   {:op :place :id id :under :doc :at :last}])

;; ── Page Ref Filtering Tests ──────────────────────────────────────────────────

(deftest test-page-ref-excludes-untitled
  (testing "search-items :page-ref excludes Untitled pages"
    (let [db (apply-ops (concat
                         (make-page "p1" "Real Page")
                         (make-page "p2" "Untitled")
                         (make-page "p3" "Another Page")))
          results (ac/search-items db {:type :page-ref :query ""})]
      (is (= 2 (count results)) "Should only have 2 pages")
      (is (not (some #(= "Untitled" (:title %)) results)) "Untitled should be excluded"))))

(deftest test-page-ref-excludes-blank-titles
  (testing "search-items :page-ref excludes blank titles"
    (let [db (apply-ops (concat
                         (make-page "p1" "Real Page")
                         (make-page "p2" "")
                         (make-page "p3" "   ")))
          results (ac/search-items db {:type :page-ref :query ""})]
      (is (= 1 (count results)) "Should only have 1 valid page")
      (is (= "Real Page" (:title (first results)))))))

(deftest test-page-ref-excludes-nil-titles
  (testing "search-items :page-ref excludes nil titles"
    (let [db (-> (db/empty-db)
                 (apply-ops [{:op :create-node :id "p1" :type :page :props {:title "Valid"}}
                             {:op :place :id "p1" :under :doc :at :last}
                             {:op :create-node :id "p2" :type :page :props {}}
                             {:op :place :id "p2" :under :doc :at :last}]))
          results (ac/search-items db {:type :page-ref :query ""})]
      (is (= 1 (count results)))
      (is (= "Valid" (:title (first results)))))))

(deftest test-page-ref-fuzzy-search-works
  (testing "search-items :page-ref filters by query"
    (let [db (apply-ops (concat
                         (make-page "p1" "Projects")
                         (make-page "p2" "Tasks")
                         (make-page "p3" "Project Notes")))
          results (ac/search-items db {:type :page-ref :query "proj"})
          ;; Filter out :create-new option to check actual matches
          existing (filter #(= :existing (:type %)) results)]
      (is (= 2 (count existing)) "Should match both 'Projects' and 'Project Notes'"))))

(deftest test-page-ref-create-new-option
  (testing "search-items :page-ref adds create-new option when no exact match"
    (let [db (apply-ops (make-page "p1" "Existing"))
          results (ac/search-items db {:type :page-ref :query "New Page"})]
      (is (some #(= :create-new (:type %)) results) "Should have create-new option"))))
