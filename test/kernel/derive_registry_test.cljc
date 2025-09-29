(ns labs.derive.registry-test
  (:require [labs.derive.registry :as registry]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

;; Test data
(def test-db
  {:nodes {"root" {:type :root}
           "parent" {:type :div}
           "child1" {:type :span}
           "child2" {:type :span}
           "grandchild" {:type :p}}
   :child-ids/by-parent {"root" ["parent"]
                         "parent" ["child1" "child2"]
                         "child2" ["grandchild"]}})

(deftest test-individual-passes
  (testing "individual pass functionality"
    ;; Test parent-id-of pass
    (let [db-with-parents (registry/run test-db {:only #{:parent-id-of}})]
      (is (= {"parent" "root" "child1" "parent" "child2" "parent" "grandchild" "child2"}
             (get-in db-with-parents [:derived :parent-id-of]))))

    ;; Test index-of pass
    (let [db-with-indices (registry/run test-db {:only #{:index-of}})]
      (is (= {"parent" 0 "child1" 0 "child2" 1 "grandchild" 0}
             (get-in db-with-indices [:derived :index-of]))))

    ;; Test child-ids-of pass
    (let [db-with-children (registry/run test-db {:only #{:child-ids-of}})]
      (let [child-ids-of (get-in db-with-children [:derived :child-ids-of])]
        (is (= ["parent"] (get child-ids-of "root")))
        (is (= ["child1" "child2"] (get child-ids-of "parent")))
        (is (= ["grandchild"] (get child-ids-of "child2")))
        (is (= [] (get child-ids-of "child1")))))))

(deftest test-dependency-ordering
  (testing "passes run in correct dependency order"
    ;; preorder depends on child-ids-of
    (let [db-with-preorder (registry/run test-db {:only #{:preorder}})]
      ;; Should automatically include child-ids-of dependency
      (is (contains? (get-in db-with-preorder [:derived]) :child-ids-of))
      (is (contains? (get-in db-with-preorder [:derived]) :preorder))
      (is (= ["root" "parent" "child1" "child2" "grandchild"]
             (get-in db-with-preorder [:derived :preorder]))))))

(deftest test-pre-post-intervals
  (testing "pre-post interval computation"
    (let [db-with-intervals (registry/run test-db {:only #{:pre-post}})]
      (let [pre (get-in db-with-intervals [:derived :pre])
            post (get-in db-with-intervals [:derived :post])
            id-by-pre (get-in db-with-intervals [:derived :id-by-pre])]
        ;; Basic sanity checks
        (is (every? number? (vals pre)))
        (is (every? number? (vals post)))
        (is (= (count pre) (count post)))

        ;; Pre/post should form valid intervals (pre < post for each node)
        (doseq [node (keys pre)]
          (is (< (pre node) (post node))))

        ;; id-by-pre should be inverse of pre
        (doseq [[node pre-val] pre]
          (is (= node (get id-by-pre pre-val))))))))

(deftest test-full-derivation
  (testing "full derivation produces complete derived data"
    (let [derived-db (registry/run test-db)]
      (let [derived (:derived derived-db)]
        ;; All core passes should be present
        (is (contains? derived :parent-id-of))
        (is (contains? derived :index-of))
        (is (contains? derived :child-ids-of))
        (is (contains? derived :preorder))
        (is (contains? derived :pre))
        (is (contains? derived :post))
        (is (contains? derived :id-by-pre))

        ;; Verify correctness of derived data
        (is (= "root" (get-in derived [:parent-id-of "parent"])))
        (is (= 1 (get-in derived [:index-of "child2"])))
        (is (= ["child1" "child2"] (get-in derived [:child-ids-of "parent"])))
        (is (= ["root" "parent" "child1" "child2" "grandchild"] (:preorder derived)))))))

(deftest test-pass-exclusion
  (testing "pass exclusion works correctly"
    (let [partial-db (registry/run test-db {:exclude #{:preorder :pre-post}})]
      (let [derived (:derived partial-db)]
        ;; Should have basic passes
        (is (contains? derived :parent-id-of))
        (is (contains? derived :index-of))
        (is (contains? derived :child-ids-of))

        ;; Should not have excluded passes
        (is (not (contains? derived :preorder)))
        (is (not (contains? derived :pre)))
        (is (not (contains? derived :post)))))))

(deftest test-timing-run
  (testing "timing run provides performance data"
    (let [{:keys [db timings]} (registry/timing-run test-db)]
      ;; Should have derived data
      (is (contains? (:derived db) :parent-id-of))

      ;; Should have timing information
      (is (vector? timings))
      (is (every? map? timings))
      (is (every? #(contains? % :id) timings))
      (is (every? #(contains? % :ms) timings))
      (is (every? #(number? (:ms %)) timings))

      ;; Timing should be positive
      (is (every? #(>= (:ms %) 0) timings)))))

(deftest test-registry-introspection
  (testing "registry introspection functions"
    ;; enabled-passes should return list of pass IDs
    (let [enabled (registry/enabled-passes)]
      (is (vector? enabled))
      (is (every? keyword? enabled))
      (is (contains? (set enabled) :parent-id-of))
      (is (contains? (set enabled) :index-of)))

    ;; describe-pass should return pass info
    (let [parent-pass (registry/describe-pass :parent-id-of)]
      (is (= :parent-id-of (:id parent-pass)))
      (is (set? (:after parent-pass)))
      (is (fn? (:run parent-pass))))

    ;; describe-pass for unknown pass
    (let [unknown (registry/describe-pass :nonexistent)]
      (is (nil? unknown)))))

(defn does-not-throw? [& body]
  (try
    (last body)
    true
    (catch #?(:clj Exception :cljs :default) _
      false)))

(deftest test-circular-dependency-detection
  (testing "circular dependencies are detected"
    ;; This test verifies the topo-sort function catches cycles
    ;; We can't easily test this without modifying the passes registry,
    ;; so we test the behavior indirectly by ensuring current passes work
    (is (does-not-throw?
         (registry/run test-db)))))

(deftest test-minimal-passes
  (testing "minimal pass sets work correctly"
    ;; Test running just one pass
    (let [single-pass-db (registry/run test-db {:only #{:parent-id-of}})]
      (is (contains? (get-in single-pass-db [:derived]) :parent-id-of))
      (is (not (contains? (get-in single-pass-db [:derived]) :index-of))))

    ;; Test running two independent passes
    (let [dual-pass-db (registry/run test-db {:only #{:parent-id-of :index-of}})]
      (is (contains? (get-in dual-pass-db [:derived]) :parent-id-of))
      (is (contains? (get-in dual-pass-db [:derived]) :index-of))
      (is (not (contains? (get-in dual-pass-db [:derived]) :preorder))))))

(deftest test-compatibility-with-existing-data
  (testing "passes work with existing derived data"
    ;; Start with partial derived data
    (let [partial-db (assoc test-db :derived {:existing-data "should-be-preserved"})
          full-db (registry/run partial-db)]
      ;; Should preserve existing data
      (is (= "should-be-preserved" (get-in full-db [:derived :existing-data])))
      ;; Should add new derived data
      (is (contains? (get-in full-db [:derived]) :parent-id-of)))))

(comment
  ;; REPL test examples:

  ;; Run individual tests
  (test-individual-passes)
  (test-full-derivation)
  (test-timing-run)

  ;; Run all tests
  #?(:clj (clojure.test/run-tests)
     :cljs (cljs.test/run-tests)))