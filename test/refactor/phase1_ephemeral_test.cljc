(ns refactor.phase1-ephemeral-test
  "Phase 1: Verify buffer operations are truly ephemeral.

   Tests that buffer updates:
   1. Do not create history entries
   2. Do not trigger derive-indexes
   3. Are marked as ephemeral correctly"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.api :as api]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [plugins.buffer]))  ; Load to register :buffer/update intent

;; ── Phase 1 Tests ─────────────────────────────────────────────────────────────

(deftest buffer-ops-are-ephemeral
  (testing "Buffer update ops are correctly identified as ephemeral"
    (let [buffer-op {:op :update-node
                     :id "session/buffer"
                     :props {"block-a" "some text"}}]
      (is (api/ephemeral-op? buffer-op)
          "Buffer ops should be identified as ephemeral")))

  (testing "Session UI ops remain ephemeral"
    (let [ui-op {:op :update-node
                 :id "session/ui"
                 :props {:editing-block-id "a"}}]
      (is (api/ephemeral-op? ui-op)
          "Session UI ops should remain ephemeral")))

  (testing "Session selection ops remain ephemeral"
    (let [selection-op {:op :update-node
                        :id "session/selection"
                        :props {:nodes #{"a" "b"}}}]
      (is (api/ephemeral-op? selection-op)
          "Session selection ops should remain ephemeral")))

  (testing "Regular block ops are NOT ephemeral"
    (let [block-op {:op :update-node
                    :id "block-123"
                    :props {:text "hello"}}]
      (is (not (api/ephemeral-op? block-op))
          "Regular block ops should NOT be ephemeral"))))

(deftest buffer-updates-skip-derive-indexes
  (testing "Multiple buffer updates do not recompute derived indexes"
    (let [db0 (db/empty-db)
          derived-before (get-in db0 [:derived])

          ;; Dispatch 10 buffer updates
          db-after (reduce
                    (fn [d i]
                      (let [intent {:type :buffer/update
                                    :block-id "test-block"
                                    :text (str "text-" i)}]
                        (:db (api/dispatch d intent))))
                    db0
                    (range 10))

          derived-after (get-in db-after [:derived])]

      ;; Derived indexes should be unchanged
      (is (= derived-before derived-after)
          "Buffer updates should not trigger derive-indexes recomputation")

      ;; Buffer should contain the latest text
      (is (= "text-9" (get-in db-after [:nodes "session/buffer" :props :test-block]))
          "Buffer should contain latest text"))))

(deftest buffer-updates-do-not-create-history
  (testing "Buffer updates do not create history entries"
    (let [db0 (db/empty-db)
          ;; Track history length
          history-before (count (get-in db0 [:history :past] []))

          ;; Dispatch buffer update
          db1 (:db (api/dispatch db0 {:type :buffer/update
                                       :block-id "test-block"
                                       :text "typing..."}))

          history-after (count (get-in db1 [:history :past] []))]

      (is (= history-before history-after)
          "Buffer updates should not create history entries"))))

(deftest buffer-intent-produces-ephemeral-ops
  (testing ":buffer/update intent produces only ephemeral ops"
    (let [db (db/empty-db)
          {:keys [ops]} (intent/apply-intent db {:type :buffer/update
                                                  :block-id "test-block"
                                                  :text "hello"})]

      (is (every? api/ephemeral-op? ops)
          "All ops from :buffer/update should be ephemeral")

      (is (= 1 (count ops))
          "Buffer update should produce exactly one op")

      (is (= :update-node (:op (first ops)))
          "Buffer update should produce :update-node op")

      (is (= "session/buffer" (:id (first ops)))
          "Buffer update should target session/buffer node"))))

;; ── Performance Baseline ──────────────────────────────────────────────────────

(deftest ^{:performance true}
  buffer-performance-baseline
  (testing "Buffer updates are fast (no derive-indexes overhead)"
    (let [db0 (db/empty-db)
          !derive-count (atom 0)

          ;; Instrument derive-indexes to count calls
          ;; Note: This is for documentation/baseline only
          ;; In real implementation, we verify via derive equality above

          ;; Simulate 100 rapid buffer updates (typing)
          start-time #?(:clj (System/nanoTime)
                        :cljs (.now js/performance))

          db-final (reduce
                    (fn [d i]
                      (:db (api/dispatch d {:type :buffer/update
                                            :block-id "active-block"
                                            :text (str "The quick brown fox jumps over the lazy dog " i)})))
                    db0
                    (range 100))

          end-time #?(:clj (System/nanoTime)
                      :cljs (.now js/performance))

          elapsed-ms #?(:clj (/ (- end-time start-time) 1e6)
                        :cljs (- end-time start-time))]

      ;; Verify all updates succeeded
      (is (= "The quick brown fox jumps over the lazy dog 99"
             (get-in db-final [:nodes "session/buffer" :props :active-block]))
          "All buffer updates should complete")

      ;; Performance assertion: 100 updates should complete quickly
      ;; Note: This is a baseline - actual perf depends on machine
      (is (< elapsed-ms 100)
          (str "100 buffer updates should complete in <100ms (actual: " elapsed-ms "ms)")))))
