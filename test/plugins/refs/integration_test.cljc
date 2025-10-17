(ns plugins.refs.integration-test
  "Integration tests for typed refs plugin.
   
   Tests:
   - Structural invariance (selections don't affect :parent-of, :index-of, etc.)
   - Toggle idempotence (toggle twice = identity)
   - Scrub correctness (dangling refs removed by ops)"
  (:require [clojure.test :refer [deftest testing is]]
            [core.db :as db]
            [core.interpret :as interp]
            [plugins.refs.core :as refs]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn interpret-ops
  "Helper to interpret ops and return resulting db."
  [db ops]
  (:db (interp/interpret db ops)))

(defn create-base-db
  "Create a base db with doc nodes and overlay nodes."
  []
  (-> (db/empty-db)
      (interpret-ops
       [{:op :create-node :id "doc-a" :type :paragraph :props {}}
        {:op :create-node :id "doc-b" :type :paragraph :props {}}
        {:op :create-node :id "doc-c" :type :paragraph :props {}}
        {:op :place :id "doc-a" :under :doc :at :last}
        {:op :place :id "doc-b" :under :doc :at :last}
        {:op :place :id "doc-c" :under :doc :at :last}
        {:op :create-node :id "cursor" :type :overlay :props {}}
        {:op :place :id "cursor" :under :session :at :last}])))

(defn structural-keys
  "Extract structural derived keys."
  [db]
  (select-keys (:derived db) [:parent-of :index-of :prev-id-of :next-id-of :pre :post :id-by-pre]))

;; =============================================================================
;; Structural Invariance Tests
;; =============================================================================

(deftest structural-invariance-test
  (testing "Selections do not affect structural indexes"
    (let [db0 (create-base-db)
          struct0 (structural-keys db0)

          ;; Add selection from cursor to doc-a
          db1 (interpret-ops db0 [(refs/add-selection-op db0 "cursor" "doc-a")])
          struct1 (structural-keys db1)

          ;; Add more selections
          db2 (interpret-ops db1 [(refs/add-selection-op db1 "cursor" "doc-b")])
          struct2 (structural-keys db2)]

      (is (= struct0 struct1) "Adding first selection should not change structural indexes")
      (is (= struct0 struct2) "Adding second selection should not change structural indexes")

      ;; Verify selections are actually there
      (is (= #{"cursor"} (get-in db1 [:derived :selection/backlinks "doc-a"])))
      (is (= #{"cursor"} (get-in db2 [:derived :selection/backlinks "doc-b"]))))))

(deftest highlights-do-not-affect-structure-test
  (testing "Highlights do not affect structural indexes"
    (let [db0 (create-base-db)
          struct0 (structural-keys db0)

          ;; Add highlight with anchor
          anchor {:path [:props :text] :range [10 24]}
          db1 (interpret-ops db0 [(refs/add-highlight-op db0 "cursor" "doc-a" anchor)])
          struct1 (structural-keys db1)]

      (is (= struct0 struct1) "Adding highlight should not change structural indexes")

      ;; Verify highlight is there
      (is (= #{"cursor"} (get-in db1 [:derived :highlight/backlinks "doc-a"]))))))

;; =============================================================================
;; Toggle Idempotence Tests
;; =============================================================================

(deftest toggle-selection-idempotence-test
  (testing "Toggle selection twice returns to original state"
    (let [db0 (create-base-db)

          ;; First toggle: add selection
          op1 (refs/toggle-selection-op db0 "cursor" "doc-a")
          db1 (interpret-ops db0 [op1])

          ;; Second toggle: remove selection
          op2 (refs/toggle-selection-op db1 "cursor" "doc-a")
          db2 (interpret-ops db1 [op2])

          ;; Check refs prop
          refs0 (get-in db0 [:nodes "cursor" :props :refs] [])
          refs2 (get-in db2 [:nodes "cursor" :props :refs] [])]

      (is (some? op1) "First toggle should return op (add)")
      (is (some? op2) "Second toggle should return op (remove)")
      (is (= refs0 refs2) "After two toggles, refs should be same as original")
      (is (= #{} (get-in db2 [:derived :selection/backlinks "doc-a"] #{}))
          "After two toggles, backlinks should be empty"))))

(deftest toggle-selection-no-op-test
  (testing "Toggle returns nil when already in desired state (alternate implementation)"
    (let [db0 (create-base-db)

          ;; Add selection manually
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "cursor"
                                   :props {:refs [{:target "doc-a" :kind :selection}]}}])

          ;; Try to add again via add-selection-op
          op-add (refs/add-selection-op db1 "cursor" "doc-a")]

      (is (nil? op-add) "add-selection-op should return nil if selection exists"))))

;; =============================================================================
;; Scrub Dangling Refs Tests
;; =============================================================================

(deftest scrub-dangling-refs-test
  (testing "Scrub removes dangling refs after node deletion"
    (let [db0 (create-base-db)

          ;; Add selection from cursor to doc-a
          db1 (interpret-ops db0 [(refs/add-selection-op db0 "cursor" "doc-a")])

          ;; Delete doc-a by placing it under :trash
          db2 (interpret-ops db1 [{:op :place :id "doc-a" :under :trash :at :last}])

          ;; doc-a is no longer in :doc tree, so derived should not count it
          ;; But the ref still exists in props
          refs-before (get-in db2 [:nodes "cursor" :props :refs])

          ;; Generate scrub ops
          scrub-ops (refs/scrub-dangling-ops db2)

          ;; Apply scrub ops
          db3 (interpret-ops db2 scrub-ops)
          refs-after (get-in db3 [:nodes "cursor" :props :refs])]

      (is (seq refs-before) "Before scrub, refs should exist in props")
      (is (seq scrub-ops) "Scrub should generate ops")
      (is (empty? refs-after) "After scrub, dangling refs should be removed")
      (is (= #{} (get-in db3 [:derived :selection/backlinks "doc-a"] #{}))
          "After scrub, backlinks should be empty"))))

(deftest scrub-preserves-valid-refs-test
  (testing "Scrub only removes dangling refs, preserves valid ones"
    (let [db0 (create-base-db)

          ;; Add selections to multiple nodes
          db1 (interpret-ops db0 [(refs/add-selection-op db0 "cursor" "doc-a")
                                  (refs/add-selection-op db0 "cursor" "doc-b")])

          ;; Delete only doc-a
          db2 (interpret-ops db1 [{:op :place :id "doc-a" :under :trash :at :last}])

          ;; Scrub
          scrub-ops (refs/scrub-dangling-ops db2)
          db3 (interpret-ops db2 scrub-ops)

          refs-after (get-in db3 [:nodes "cursor" :props :refs])]

      (is (= 1 (count refs-after)) "After scrub, should have 1 ref remaining")
      (is (= "doc-b" (:target (first refs-after))) "Remaining ref should be to doc-b")
      (is (= #{"cursor"} (get-in db3 [:derived :selection/backlinks "doc-b"]))
          "Backlinks to doc-b should still exist"))))

;; =============================================================================
;; Derived View Tests
;; =============================================================================

(deftest derived-views-only-count-doc-nodes-test
  (testing "Derived views only count refs whose target is in :doc tree"
    (let [db0 (create-base-db)

          ;; Create another overlay node
          db1 (interpret-ops db0 [{:op :create-node :id "marker" :type :overlay :props {}}
                                  {:op :place :id "marker" :under :session :at :last}])

          ;; Add ref from cursor to doc-a (valid)
          ;; Add ref from cursor to marker (overlay, should be filtered)
          db2 (interpret-ops db1 [{:op :update-node
                                   :id "cursor"
                                   :props {:refs [{:target "doc-a" :kind :selection}
                                                  {:target "marker" :kind :selection}]}}])

          sel-outgoing (get-in db2 [:derived :selection/outgoing "cursor"])]

      (is (= #{"doc-a"} sel-outgoing)
          "Only refs to :doc nodes should appear in derived views")
      (is (not (contains? sel-outgoing "marker"))
          "Refs to overlay nodes should be filtered out"))))

;; =============================================================================
;; Lint Tests
;; =============================================================================

(deftest lint-detects-dangling-refs-test
  (testing "Lint detects dangling refs"
    (let [db0 (create-base-db)

          ;; Add ref to non-existent node
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "cursor"
                                   :props {:refs [{:target "missing" :kind :selection}]}}])

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

          ;; Add different kinds of refs
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "cursor"
                                   :props {:refs [{:target "doc-a" :kind :link}
                                                  {:target "doc-a" :kind :selection}
                                                  {:target "doc-a" :kind :highlight}]}}])

          backlinks-by-kind (get-in db1 [:derived :ref/backlinks-by-kind])]

      (is (= #{"cursor"} (get-in backlinks-by-kind [:link "doc-a"])))
      (is (= #{"cursor"} (get-in backlinks-by-kind [:selection "doc-a"])))
      (is (= #{"cursor"} (get-in backlinks-by-kind [:highlight "doc-a"])))
      (is (= 3 (get-in db1 [:derived :ref/citations "doc-a"]))
          "Citation count should include all kinds"))))
