(ns integration.ephemeral-test
  "Tests for ephemeral operations (UI/session state that doesn't enter history).

   Ephemeral policy:
   - Enter/exit edit mode
   - Update cursor position
   - Update UI state

   These operations should:
   - NOT create history entries (undo count unchanged)
   - NOT trigger derive-indexes (performance fast path)
   - NOT modify document nodes or :children-by-parent

   This locks the ephemeral policy during the Step 6 refactor."
  (:require [clojure.test :refer [deftest is testing]]
            [test-helper :as helper]
            [kernel.api :as api]
            [kernel.history :as H]
            [kernel.query :as q]
            [kernel.constants :as const]))

(deftest enter-exit-edit-ephemeral
  (testing "Enter/exit edit mode does not create history entries"
    (let [db0 (helper/demo-db)
          undo-count-0 (H/undo-count db0)

          ;; Enter edit mode
          {:keys [db]} (api/dispatch db0 {:type :enter-edit :block-id "a"})
          undo-count-1 (H/undo-count db)

          ;; Exit edit mode
          {:keys [db]} (api/dispatch db {:type :exit-edit})
          undo-count-2 (H/undo-count db)]

      (is (= undo-count-0 undo-count-1)
          "Enter edit should not add history entry")
      (is (= undo-count-0 undo-count-2)
          "Exit edit should not add history entry")
      (is (nil? (get-in db [:nodes const/session-ui-id :props :editing-block-id]))
          "Editing state should be cleared after exit"))))

(deftest cursor-update-ephemeral
  (testing "Cursor position updates do not create history"
    (let [db0 (helper/demo-db)
          undo-count-0 (H/undo-count db0)

          ;; Update cursor multiple times
          {:keys [db]} (api/dispatch db0 {:type :update-cursor-state
                                           :block-id "a"
                                           :first-row? true
                                           :last-row? true})
          undo-count-1 (H/undo-count db)

          {:keys [db]} (api/dispatch db {:type :update-cursor-state
                                          :block-id "b"
                                          :first-row? false
                                          :last-row? true})
          undo-count-2 (H/undo-count db)]

      (is (= undo-count-0 undo-count-1 undo-count-2)
          "Cursor updates should not add history entries")
      (is (= {:first-row? false :last-row? true}
             (get-in db [:nodes const/session-ui-id :props :cursor "b"]))
          "Cursor state should update correctly"))))

(deftest ephemeral-batch-no-history
  (testing "Sequence of ephemeral ops produces no history entries"
    (let [db0 (helper/demo-db)
          undo-count-0 (H/undo-count db0)

          db-final (helper/dispatch* db0
                                      {:type :enter-edit :block-id "b"}
                                      {:type :update-cursor-state
                                       :block-id "b"
                                       :first-row? true
                                       :last-row? false}
                                      {:type :update-cursor-state
                                       :block-id "b"
                                       :first-row? false
                                       :last-row? true}
                                      {:type :exit-edit})]

      (is (= undo-count-0 (H/undo-count db-final))
          "Batch of ephemeral ops should not change history"))))

(deftest ephemeral-does-not-modify-document
  (testing "Ephemeral ops do not modify document nodes or structure"
    (let [db0 (helper/demo-db)
          doc-nodes-0 (dissoc (:nodes db0) "session" const/session-selection-id const/session-ui-id)
          children-0 (:children-by-parent db0)

          db-after (helper/dispatch* db0
                                      {:type :enter-edit :block-id "c"}
                                      {:type :update-cursor-state
                                       :block-id "c"
                                       :first-row? true
                                       :last-row? true}
                                      {:type :exit-edit})

          doc-nodes-1 (dissoc (:nodes db-after) "session" const/session-selection-id const/session-ui-id)
          children-1 (:children-by-parent db-after)]

      (is (= doc-nodes-0 doc-nodes-1)
          "Document nodes should be unchanged after ephemeral ops")
      (is (= children-0 children-1)
          "Children structure should be unchanged after ephemeral ops"))))

(deftest ephemeral-interleaved-with-doc-changes
  (testing "Ephemeral ops between doc changes don't affect history"
    (let [db0 (helper/demo-db)

          ;; Doc change (should add history)
          {:keys [db]} (api/dispatch db0 {:type :update-content :block-id "a" :text "Changed A"})
          undo-count-1 (H/undo-count db)

          ;; Ephemeral ops (should not add history)
          db (helper/dispatch* db
                               {:type :enter-edit :block-id "b"}
                               {:type :exit-edit})
          undo-count-2 (H/undo-count db)

          ;; Another doc change (should add history)
          {:keys [db]} (api/dispatch db {:type :update-content :block-id "b" :text "Changed B"})
          undo-count-3 (H/undo-count db)]

      (is (= (inc (H/undo-count db0)) undo-count-1)
          "First doc change should add history")
      (is (= undo-count-1 undo-count-2)
          "Ephemeral ops should not add history")
      (is (= (inc undo-count-2) undo-count-3)
          "Second doc change should add history"))))

(deftest undo-redo-skip-ephemeral
  (testing "Undo/redo skip over interleaved ephemeral ops"
    (let [db0 (helper/demo-db)

          ;; Doc change 1
          {:keys [db]} (api/dispatch db0 {:type :update-content :block-id "a" :text "Version 1"})

          ;; Ephemeral ops
          db (helper/dispatch* db
                               {:type :enter-edit :block-id "a"}
                               {:type :exit-edit})

          ;; Doc change 2
          {:keys [db]} (api/dispatch db {:type :update-content :block-id "a" :text "Version 2"})

          ;; Undo should go back to Version 1 (skipping ephemeral)
          db-undone (H/undo db)
          ;; Redo should go forward to Version 2
          db-redone (H/redo db-undone)]

      (is (= "Version 1" (get-in db-undone [:nodes "a" :props :text]))
          "Undo should restore Version 1")
      (is (= "Version 2" (get-in db-redone [:nodes "a" :props :text]))
          "Redo should restore Version 2"))))
