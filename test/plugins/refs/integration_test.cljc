(ns plugins.refs.integration-test
  "Integration tests for typed refs plugin.
   
   Tests:
   - Structural invariance (links/highlights don't affect :parent-of, :index-of, etc.)
   - Scrub correctness (dangling refs removed by ops)
   - Lint detection (dangling and circular refs)
   - Ref kind separation (links vs highlights)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [core.db :as db]
            [core.interpret :as interp]
            [plugins.refs.core :as refs]
            [plugins.registry :as reg]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(use-fixtures :each
  (fn [f]
    ;; Re-register plugin (may have been cleared by other tests)
    (reg/register! ::refs/refs refs/derive-indexes)
    (f)))

(defn interpret-ops
  "Helper to interpret ops and return resulting db."
  [db ops]
  (:db (interp/interpret db ops)))

(defn create-base-db
  "Create a base db with doc nodes."
  []
  (-> (db/empty-db)
      (interpret-ops
       [{:op :create-node :id "doc-a" :type :paragraph :props {}}
        {:op :create-node :id "doc-b" :type :paragraph :props {}}
        {:op :create-node :id "doc-c" :type :paragraph :props {}}
        {:op :place :id "doc-a" :under :doc :at :last}
        {:op :place :id "doc-b" :under :doc :at :last}
        {:op :place :id "doc-c" :under :doc :at :last}])))

(defn structural-keys
  "Extract structural derived keys."
  [db]
  (select-keys (:derived db) [:parent-of :index-of :prev-id-of :next-id-of :pre :post :id-by-pre]))

;; =============================================================================
;; Structural Invariance Tests
;; =============================================================================

(deftest structural-invariance-test
  (testing "Links do not affect structural indexes"
    (let [db0 (create-base-db)
          struct0 (structural-keys db0)

          ;; Add link from doc-a to doc-b
          db1 (interpret-ops db0 [(refs/add-link-op db0 "doc-a" "doc-b")])
          struct1 (structural-keys db1)

          ;; Add more links
          db2 (interpret-ops db1 [(refs/add-link-op db1 "doc-a" "doc-c")])
          struct2 (structural-keys db2)]

      (is (= struct0 struct1) "Adding first link should not change structural indexes")
      (is (= struct0 struct2) "Adding second link should not change structural indexes")

      ;; Verify links are actually there
      (is (= #{"doc-b" "doc-c"} (get-in db2 [:derived :ref/outgoing "doc-a"])))
      (is (= #{"doc-a"} (get-in db2 [:derived :link/backlinks "doc-b"]))))))

(deftest highlights-do-not-affect-structure-test
  (testing "Highlights do not affect structural indexes"
    (let [db0 (create-base-db)
          struct0 (structural-keys db0)

          ;; Add highlight with anchor
          anchor {:path [:props :text] :range [10 24]}
          db1 (interpret-ops db0 [(refs/add-highlight-op db0 "doc-a" "doc-b" anchor)])
          struct1 (structural-keys db1)]

      (is (= struct0 struct1) "Adding highlight should not change structural indexes")

      ;; Verify highlight is there
      (is (= #{"doc-a"} (get-in db1 [:derived :highlight/backlinks "doc-b"]))))))

;; =============================================================================
;; Link Operations Tests
;; =============================================================================

(deftest add-link-idempotent-test
  (testing "add-link-op is idempotent"
    (let [db0 (create-base-db)
          op1 (refs/add-link-op db0 "doc-a" "doc-b")
          db1 (interpret-ops db0 [op1])
          op2 (refs/add-link-op db1 "doc-a" "doc-b")]

      (is (some? op1) "First add should return op")
      (is (nil? op2) "Second add should return nil (already exists)"))))

;; =============================================================================
;; Scrub Dangling Refs Tests
;; =============================================================================

(deftest scrub-dangling-refs-test
  (testing "Scrub removes dangling refs after node deletion"
    (let [db0 (create-base-db)

          ;; Add link from doc-a to doc-b
          db1 (interpret-ops db0 [(refs/add-link-op db0 "doc-a" "doc-b")])

          ;; Delete doc-b by placing it under :trash
          db2 (interpret-ops db1 [{:op :place :id "doc-b" :under :trash :at :last}])

          ;; doc-b is no longer in :doc tree, so derived should not count it
          ;; But the ref still exists in props
          refs-before (get-in db2 [:nodes "doc-a" :props :refs])

          ;; Generate scrub ops
          scrub-ops (refs/scrub-dangling-ops db2)

          ;; Apply scrub ops
          db3 (interpret-ops db2 scrub-ops)
          refs-after (get-in db3 [:nodes "doc-a" :props :refs])]

      (is (seq refs-before) "Before scrub, refs should exist in props")
      (is (seq scrub-ops) "Scrub should generate ops")
      (is (empty? refs-after) "After scrub, dangling refs should be removed")
      (is (= #{} (get-in db3 [:derived :link/backlinks "doc-b"] #{}))
          "After scrub, backlinks should be empty"))))

(deftest scrub-preserves-valid-refs-test
  (testing "Scrub only removes dangling refs, preserves valid ones"
    (let [db0 (create-base-db)

          ;; Add links to multiple nodes
          db1 (interpret-ops db0 [(refs/add-link-op db0 "doc-a" "doc-b")
                                  (refs/add-link-op db0 "doc-a" "doc-c")])

          ;; Delete only doc-b
          db2 (interpret-ops db1 [{:op :place :id "doc-b" :under :trash :at :last}])

          ;; Scrub
          scrub-ops (refs/scrub-dangling-ops db2)
          db3 (interpret-ops db2 scrub-ops)

          refs-after (get-in db3 [:nodes "doc-a" :props :refs])]

      (is (= 1 (count refs-after)) "After scrub, should have 1 ref remaining")
      (is (= "doc-c" (:target (first refs-after))) "Remaining ref should be to doc-c")
      (is (= #{"doc-a"} (get-in db3 [:derived :link/backlinks "doc-c"]))
          "Backlinks to doc-c should still exist"))))

;; =============================================================================
;; Derived View Tests
;; =============================================================================

(deftest derived-views-only-count-doc-nodes-test
  (testing "Derived views only count refs whose target is in :doc tree"
    (let [db0 (create-base-db)

          ;; Create overlay node under :session
          db1 (interpret-ops db0 [{:op :create-node :id "marker" :type :overlay :props {}}
                                  {:op :place :id "marker" :under :session :at :last}])

          ;; Add ref from doc-a to doc-b (valid, both in :doc)
          ;; Add ref from doc-a to marker (overlay, should be filtered)
          db2 (interpret-ops db1 [{:op :update-node
                                   :id "doc-a"
                                   :props {:refs [{:target "doc-b" :kind :link}
                                                  {:target "marker" :kind :link}]}}])

          outgoing (get-in db2 [:derived :ref/outgoing "doc-a"])]

      (is (= #{"doc-b"} outgoing)
          "Only refs to :doc nodes should appear in derived views")
      (is (not (contains? outgoing "marker"))
          "Refs to overlay nodes should be filtered out"))))

;; =============================================================================
;; Lint Tests
;; =============================================================================

(deftest lint-detects-dangling-refs-test
  (testing "Lint detects dangling refs"
    (let [db0 (create-base-db)

          ;; Add ref to non-existent node
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:refs [{:target "missing" :kind :link}]}}])

          issues (refs/lint db1)]

      (is (seq issues) "Lint should find issues")
      (is (some #(= :dangling-ref (:reason %)) issues)
          "Should detect dangling ref"))))

(deftest lint-detects-circular-refs-test
  (testing "Lint detects circular refs"
    (let [db0 (create-base-db)

          ;; Add self-ref
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:refs [{:target "doc-a" :kind :link}]}}])

          issues (refs/lint db1)]

      (is (seq issues) "Lint should find issues")
      (is (some #(= :circular-ref (:reason %)) issues)
          "Should detect circular ref"))))

;; =============================================================================
;; Ref Kind Tests
;; =============================================================================

(deftest different-ref-kinds-test
  (testing "Different ref kinds are tracked separately"
    (let [db0 (create-base-db)

          ;; Add different kinds of refs from doc-a to doc-b
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "doc-a"
                                   :props {:refs [{:target "doc-b" :kind :link}
                                                  {:target "doc-b" :kind :highlight :anchor {:range [0 10]}}]}}])

          backlinks-by-kind (get-in db1 [:derived :ref/backlinks-by-kind])]

      (is (= #{"doc-a"} (get-in backlinks-by-kind [:link "doc-b"])))
      (is (= #{"doc-a"} (get-in backlinks-by-kind [:highlight "doc-b"])))
      (is (= 2 (get-in db1 [:derived :ref/citations "doc-b"]))
          "Citation count should include all kinds"))))
