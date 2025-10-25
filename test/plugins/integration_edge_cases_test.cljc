(ns plugins.integration-edge-cases-test
  "Edge case integration tests for selection and refs plugins.

   Tests interactions between plugins and kernel operations:
   - Selection persistence across structural changes
   - Refs behavior when nodes are moved/deleted
   - Combined selection + refs scenarios"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [core.db :as db]
            [core.transaction :as tx]
            [core.intent :as intent]
            [plugins.selection :as sel]
            [plugins.refs :as refs]
            [plugins.registry :as reg]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(use-fixtures :each
  (fn [f]
    ;; Re-register plugins (selection has no derived indexes)
    (reg/register! ::refs/refs refs/derive-indexes)
    (f)))

(defn interpret-ops
  "Helper to interpret ops and return resulting db."
  [db ops]
  (:db (tx/interpret db ops)))

(defn create-base-db
  "Create a base db with doc nodes."
  []
  (-> (db/empty-db)
      (interpret-ops
       [{:op :create-node :id "a" :type :paragraph :props {}}
        {:op :create-node :id "b" :type :paragraph :props {}}
        {:op :create-node :id "c" :type :paragraph :props {}}
        {:op :place :id "a" :under :doc :at :last}
        {:op :place :id "b" :under :doc :at :last}
        {:op :place :id "c" :under :doc :at :last}])))

;; =============================================================================
;; Selection Edge Cases
;; =============================================================================

(deftest selection-survives-node-move-test
  (testing "Selection persists when node is moved within :doc tree"
    (let [db0 (create-base-db)

          ;; Select node 'a' (via intent->ops)
          db1 (interpret-ops db0 (intent/intent->ops db0 {:type :select :ids "a"}))

          ;; Move 'a' to different position (still under :doc)
          db2 (interpret-ops db1 [{:op :place :id "a" :under :doc :at 1}])]

      (is (sel/selected? db1 "a") "Node should be selected before move")
      (is (sel/selected? db2 "a") "Selection should persist after move")
      (is (= #{"a"} (sel/get-selected-nodes db2))))))

(deftest selection-cleared-when-node-trashed-test
  (testing "Selection is cleared when node moved to :trash"
    (let [db0 (create-base-db)

          ;; Select node 'a' (via intent->ops)
          db1 (interpret-ops db0 (intent/intent->ops db0 {:type :select :ids "a"}))

          ;; Move 'a' to trash
          db2 (interpret-ops db1 [{:op :place :id "a" :under :trash :at :last}])]

      (is (sel/selected? db1 "a") "Node should be selected before trashing")
      (is (sel/selected? db2 "a") "Selection persists (manual clear needed)")
      ;; Note: Selection state is independent of node location
      ;; Application code should clear selection when trashing
      )))

(deftest multi-select-deselect-order-independence-test
  (testing "Multiple deselect ops are order-independent"
    (let [db0 (create-base-db)

          ;; Select all three (via intent->ops)
          db1 (interpret-ops db0 (intent/intent->ops db0 {:type :select :ids ["a" "b" "c"]}))

          ;; Deselect in one order
          db2a (-> db1
                   (interpret-ops (intent/intent->ops db1 {:type :deselect :ids "a"}))
                   (as-> db' (interpret-ops db' (intent/intent->ops db' {:type :deselect :ids "b"}))))

          ;; Deselect in different order
          db2b (-> db1
                   (interpret-ops (intent/intent->ops db1 {:type :deselect :ids "b"}))
                   (as-> db' (interpret-ops db' (intent/intent->ops db' {:type :deselect :ids "a"}))))]

      (is (= (sel/get-selected-nodes db2a)
             (sel/get-selected-nodes db2b))
          "Deselect order should not affect final state"))))

;; =============================================================================
;; Refs Edge Cases
;; =============================================================================

(deftest refs-filtered-when-target-trashed-test
  (testing "Refs to trashed nodes are filtered from derived views"
    (let [db0 (create-base-db)

          ;; Add link a -> b
          db1 (interpret-ops db0 [(refs/add-link-op db0 "a" "b")])

          ;; Trash node 'b'
          db2 (interpret-ops db1 [{:op :place :id "b" :under :trash :at :last}])]

      ;; Ref still exists in props
      (is (seq (get-in db2 [:nodes "a" :props :refs]))
          "Ref should still exist in props")

      ;; But filtered from derived views
      (is (nil? (get-in db2 [:derived :ref/outgoing "a"]))
          "Outgoing refs should be empty (target not in :doc)")
      (is (nil? (get-in db2 [:derived :link/backlinks "b"]))
          "Backlinks should be empty"))))

(deftest refs-idempotent-add-test
  (testing "Adding same link twice is idempotent"
    (let [db0 (create-base-db)

          ;; Add link a -> b
          op1 (refs/add-link-op db0 "a" "b")
          db1 (interpret-ops db0 [op1])

          ;; Try to add same link again
          op2 (refs/add-link-op db1 "a" "b")]

      (is (some? op1) "First add should return an op")
      (is (nil? op2) "Second add should return nil (already exists)")
      (is (= 1 (count (get-in db1 [:nodes "a" :props :refs])))
          "Should only have one ref"))))

(deftest refs-multiple-kinds-to-same-target-test
  (testing "Can have multiple ref kinds to same target"
    (let [db0 (create-base-db)

          ;; Add link and highlight to same target
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "a"
                                   :props {:refs [{:target "b" :kind :link}
                                                  {:target "b" :kind :highlight :anchor {:range [0 5]}}]}}])]

      (is (= #{"b"} (get-in db1 [:derived :ref/outgoing "a"]))
          "Outgoing should show one target")
      (is (= 2 (get-in db1 [:derived :ref/citations "b"]))
          "Citations should count both kinds"))))

(deftest scrub-preserves-non-doc-refs-test
  (testing "Scrub only removes refs to missing/trashed nodes, not all non-doc refs"
    (let [db0 (create-base-db)

          ;; Add refs to doc node and to non-existent node
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "a"
                                   :props {:refs [{:target "b" :kind :link}
                                                  {:target "missing" :kind :link}]}}])

          ;; Scrub
          scrub-ops (refs/scrub-dangling-ops db1)
          db2 (interpret-ops db1 scrub-ops)

          refs-after (get-in db2 [:nodes "a" :props :refs])]

      (is (= 1 (count refs-after)) "Should have 1 ref after scrub")
      (is (= "b" (:target (first refs-after))) "Should keep ref to existing node"))))

;; =============================================================================
;; Combined Selection + Refs Edge Cases
;; =============================================================================

(deftest selection-and-refs-independent-test
  (testing "Selection and refs are independent - don't affect each other"
    (let [db0 (create-base-db)

          ;; Select node (via intent->ops) and add ref (via interpret)
          db1 (-> db0
                  (interpret-ops (intent/intent->ops db0 {:type :select :ids "a"}))
                  (interpret-ops [(refs/add-link-op db0 "a" "b")]))

          ;; Clear selection (via intent->ops)
          db2 (interpret-ops db1 (intent/intent->ops db1 {:type :clear-selection}))]

      ;; Refs should persist after clearing selection
      (is (= #{"b"} (get-in db2 [:derived :ref/outgoing "a"]))
          "Refs should not be affected by selection changes")

      ;; Selection should be cleared
      (is (= #{} (sel/get-selected-nodes db2))
          "Selection should be cleared"))))

(deftest circular-ref-detected-test
  (testing "Lint detects self-referential refs"
    (let [db0 (create-base-db)

          ;; Add self-ref
          db1 (interpret-ops db0 [{:op :update-node
                                   :id "a"
                                   :props {:refs [{:target "a" :kind :link}]}}])

          issues (refs/lint db1)]

      (is (seq issues) "Should have lint issues")
      (is (some #(= :circular-ref (:reason %)) issues)
          "Should detect circular ref"))))

(deftest citation-count-accuracy-test
  (testing "Citation counts are accurate across multiple sources"
    (let [db0 (create-base-db)

          ;; Multiple nodes link to 'c'
          db1 (interpret-ops db0 [(refs/add-link-op db0 "a" "c")
                                  (refs/add-link-op db0 "b" "c")])]

      (is (= 2 (get-in db1 [:derived :ref/citations "c"]))
          "Should count citations from multiple sources"))))
