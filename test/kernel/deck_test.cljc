(ns kernel.deck-test
  (:require [kernel.deck :as deck]
            [clojure.string :as str]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(deftest test-node-exists-rule
  (testing "node-exists rule catches missing parent nodes"
    (let [broken-db {:nodes {"child1" {:type :div}}
                     :child-ids/by-parent {"nonexistent-parent" ["child1"]}}
          findings (deck/run broken-db)]
      (is (= 1 (count findings)))
      (is (= :node-exists (:rule (first findings))))
      (is (= :error (:level (first findings))))
      (is (= [:child-ids/by-parent "nonexistent-parent"] (:at (first findings)))))))

(deftest test-child-ids-known-rule
  (testing "child-ids-known rule catches missing child nodes"
    (let [broken-db {:nodes {"root" {:type :root}}
                     :child-ids/by-parent {"root" ["nonexistent-child"]}}
          findings (deck/run broken-db)]
      (is (= 1 (count findings)))
      (is (= :child-ids-known (:rule (first findings))))
      (is (= :error (:level (first findings)))))))

(deftest test-unique-ids-rule
  (testing "unique-ids rule catches duplicate children"
    (let [broken-db {:nodes {"root" {:type :root} "child1" {:type :div}}
                     :child-ids/by-parent {"root" ["child1" "child1"]}}
          findings (deck/run broken-db {:when #{:post}})]
      (is (= 1 (count findings)))
      (is (= :unique-ids (:rule (first findings))))
      (is (= :error (:level (first findings)))))))

(deftest test-index-consistent-rule
  (testing "index-consistent rule catches index mismatches"
    (let [broken-db {:nodes {"root" {:type :root} "a" {:type :div} "b" {:type :div}}
                     :child-ids/by-parent {"root" ["a" "b"]}
                     :derived {:index-of {"a" 1 "b" 0}}} ; Wrong indices
          findings (deck/run broken-db {:when #{:post}})]
      (is (>= (count findings) 1))
      (is (some #(= :index-consistent (:rule %)) findings)))))

(deftest test-acyclic-rule
  (testing "acyclic rule catches cycles in parent relationships"
    (let [broken-db {:nodes {"a" {:type :div} "b" {:type :div}}
                     :child-ids/by-parent {"a" ["b"] "b" ["a"]}
                     :derived {:parent-id-of {"a" "b" "b" "a"}}}
          findings (deck/run broken-db {:when #{:post}})]
      (is (>= (count findings) 1))
      (is (some #(= :acyclic (:rule %)) findings)))))

(deftest test-valid-db-passes
  (testing "valid database structure passes all checks"
    (let [valid-db {:nodes {"root" {:type :root} "child1" {:type :div} "child2" {:type :div}}
                    :child-ids/by-parent {"root" ["child1" "child2"]}
                    :derived {:parent-id-of {"child1" "root" "child2" "root"}
                              :index-of {"child1" 0 "child2" 1}}}
          findings (deck/run valid-db)]
      (is (empty? findings)))))

(deftest test-run-options
  (testing "run options filter correctly"
    (let [broken-db {:nodes {"root" {:type :root}}
                     :child-ids/by-parent {"root" ["missing"]}}

          ;; Test :when filtering
          pre-findings (deck/run broken-db {:when #{:pre}})
          post-findings (deck/run broken-db {:when #{:post}})

          ;; Test level filtering (our rules are all :error level)
          error-findings (deck/run broken-db {:level>= :error})
          warn-findings (deck/run broken-db {:level>= :warn})]

      (is (>= (count pre-findings) 1)) ; Should catch child-ids-known in pre
      (is (>= (count post-findings) 1)) ; Should catch child-ids-known in post
      (is (>= (count error-findings) 1)) ; Should include error-level findings
      (is (>= (count warn-findings) 1)))) ; Should include all findings

  (testing "findings-summary produces readable output"
    (let [findings [{:rule :test-rule :level :error :msg "Test error"}]
          summary (deck/findings-summary findings)]
      (is (clojure.string/includes? summary "1 violation"))
      (is (clojure.string/includes? summary "test-rule"))
      (is (clojure.string/includes? summary "Test error")))

    (let [empty-summary (deck/findings-summary [])]
      (is (clojure.string/includes? empty-summary "No invariant violations")))))

(comment
  ;; REPL test examples:

  ;; Run individual tests
  (test-node-exists-rule)
  (test-valid-db-passes)

  ;; Run all tests
  #?(:clj (clojure.test/run-tests)
     :cljs (cljs.test/run-tests)))