(ns plugins.refs.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [plugins.refs.core :as refs]))

(defn make-test-db-with-refs
  "Create test DB with refs for testing."
  []
  {:nodes {"A" {:type :p :props {:refs ["X" "Y"]}}
           "B" {:type :p :props {:refs []}}
           "X" {:type :p :props {}}
           "Y" {:type :p :props {}}}
   :children-by-parent {}
   :roots #{:doc :trash}
   :derived {}})

(deftest test-derive-indexes
  (testing "Derive ref indexes from node props"
    (let [db (make-test-db-with-refs)
          derived (refs/derive-indexes db)]
      (is (= {"A" #{"X" "Y"}}
             (:ref-outgoing derived))
          "Should compute outgoing refs")

      (is (= {"X" #{"A"}
              "Y" #{"A"}}
             (:ref-backlinks derived))
          "Should invert to compute backlinks")

      (is (= {"X" 1 "Y" 1}
             (:ref-citation-count derived))
          "Should count citations"))))

(deftest test-derive-indexes-empty
  (testing "Empty refs"
    (let [db {:nodes {"A" {:type :p :props {}}}
              :children-by-parent {}
              :roots #{:doc :trash}
              :derived {}}
          derived (refs/derive-indexes db)]
      (is (empty? (:ref-outgoing derived)))
      (is (empty? (:ref-backlinks derived)))
      (is (empty? (:ref-citation-count derived))))))

(deftest test-find-dangling-refs
  (testing "Find refs to non-existent nodes"
    (let [db (-> (make-test-db-with-refs)
                 (assoc-in [:nodes "A" :props :refs] ["X" "MISSING"])
                 (assoc :derived (refs/derive-indexes (assoc-in (make-test-db-with-refs)
                                                                 [:nodes "A" :props :refs]
                                                                 ["X" "MISSING"]))))
          issues (refs/find-dangling-refs db)]
      (is (= 1 (count issues)))
      (is (= ::refs/dangling-ref (:reason (first issues))))
      (is (= "MISSING" (:dst (first issues))))
      (is (= #{"A"} (:srcs (first issues)))))))

(deftest test-scrub-dangling-refs
  (testing "Generate ops to remove dangling refs"
    (let [db (-> (make-test-db-with-refs)
                 (assoc-in [:nodes "A" :props :refs] ["X" "MISSING"]))
          ops (refs/scrub-dangling-refs db)]
      (is (= 1 (count ops)))
      (is (= :update-node (:op (first ops))))
      (is (= "A" (:id (first ops))))
      (is (= ["X"] (get-in (first ops) [:props :refs]))
          "Should remove MISSING but keep X"))))

(deftest test-lint-circular-ref
  (testing "Detect circular refs (self-reference)"
    (let [db (-> (make-test-db-with-refs)
                 (assoc-in [:nodes "A" :props :refs] ["A" "X"])
                 (assoc :derived (refs/derive-indexes (assoc-in (make-test-db-with-refs)
                                                                 [:nodes "A" :props :refs]
                                                                 ["A" "X"]))))
          issues (refs/lint db)
          circular (filter #(= ::refs/circular-ref (:reason %)) issues)]
      (is (pos? (count circular)))
      (is (= "A" (:node (first circular)))))))

(deftest test-add-ref
  (testing "Add ref from src to dst"
    (let [db (make-test-db-with-refs)
          op (refs/add-ref db "B" "X")]
      (is (some? op))
      (is (= :update-node (:op op)))
      (is (= "B" (:id op)))
      (is (= ["X"] (get-in op [:props :refs])))))

  (testing "Add ref when already exists is no-op"
    (let [db (make-test-db-with-refs)
          op (refs/add-ref db "A" "X")]
      (is (nil? op) "Should return nil for no-op"))))

(deftest test-remove-ref
  (testing "Remove ref from src to dst"
    (let [db (make-test-db-with-refs)
          op (refs/remove-ref db "A" "X")]
      (is (some? op))
      (is (= :update-node (:op op)))
      (is (= "A" (:id op)))
      (is (= ["Y"] (get-in op [:props :refs]))
          "Should keep Y but remove X")))

  (testing "Remove ref when doesn't exist is no-op"
    (let [db (make-test-db-with-refs)
          op (refs/remove-ref db "B" "X")]
      (is (nil? op) "Should return nil for no-op"))))

(deftest test-get-backlinks
  (testing "Get nodes that reference target"
    (let [db (-> (make-test-db-with-refs)
                 (assoc :derived (refs/derive-indexes (make-test-db-with-refs))))]
      (is (= #{"A"} (refs/get-backlinks db "X")))
      (is (= #{} (refs/get-backlinks db "B")))
      (is (= #{} (refs/get-backlinks db "MISSING"))))))

(deftest test-get-outgoing-refs
  (testing "Get nodes referenced by source"
    (let [db (-> (make-test-db-with-refs)
                 (assoc :derived (refs/derive-indexes (make-test-db-with-refs))))]
      (is (= #{"X" "Y"} (refs/get-outgoing-refs db "A")))
      (is (= #{} (refs/get-outgoing-refs db "X"))))))

(deftest test-citation-count
  (testing "Get citation count for node"
    (let [db (-> (make-test-db-with-refs)
                 (assoc :derived (refs/derive-indexes (make-test-db-with-refs))))]
      (is (= 1 (refs/citation-count db "X")))
      (is (= 0 (refs/citation-count db "A")))
      (is (= 0 (refs/citation-count db "MISSING"))))))
