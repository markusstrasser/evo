(ns plugins.navigation-test
  "Unit tests for navigation plugin - cursor memory and vertical navigation."
  (:require [clojure.test :refer [deftest testing is]]
            [plugins.navigation :as nav]
            [kernel.intent :as intent]
            [kernel.db :as DB]
            [kernel.transaction :as tx]
            [kernel.constants :as const]))

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn sample-db
  "Create a simple DB with two blocks for testing navigation."
  []
  (-> (DB/empty-db)
      (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "hello world"}}
                     {:op :place :id "a" :under :doc :at :last}
                     {:op :create-node :id "b" :type :block :props {:text "foo bar baz"}}
                     {:op :place :id "b" :under :doc :at :last}])
      :db))

;; ── Line position calculation tests ──────────────────────────────────────────

(deftest get-line-pos-single-line-test
  (testing "Cursor position on single line"
    (is (= 5 (nav/get-line-pos "hello world" 5)))
    (is (= 0 (nav/get-line-pos "hello" 0)))
    (is (= 5 (nav/get-line-pos "hello" 5)))))

(deftest get-line-pos-multi-line-test
  (testing "Cursor on second line"
    ;; "line one\nline two" = positions: l(0)i(1)n(2)e(3) (4)o(5)n(6)e(7)\n(8)l(9)i(10)n(11)e(12)
    ;; Cursor at position 12 is 'e' in "line", which is 3 chars into second line
    (is (= 3 (nav/get-line-pos "line one\nline two" 12))))

  (testing "Cursor on third line"
    ;; "a\nb\ncd" = a(0)\n(1)b(2)\n(3)c(4)d(5)
    ;; Cursor at position 5 is 'd', which is 1 char into third line (line starts at 4)
    (is (= 1 (nav/get-line-pos "a\nb\ncd" 5)))))

(deftest get-line-pos-at-line-boundaries-test
  (testing "Cursor at start of line (after newline)"
    ;; "hello\nworld" = hello(0-4)\n(5)world(6-10)
    ;; Position 6 is 'w', start of second line
    (is (= 0 (nav/get-line-pos "hello\nworld" 6))))

  (testing "Cursor at end of line (before newline)"
    ;; "hello\nworld" - position 5 is '\n', which is 5 chars into first line
    (is (= 5 (nav/get-line-pos "hello\nworld" 5)))))

;; ── Target cursor position calculation tests ──────────────────────────────────

(deftest get-target-cursor-pos-simple-test
  (testing "Target longer than line-pos - navigate down"
    ;; line-pos=5, target="hello world" (11 chars)
    ;; Should place cursor at position 5
    (is (= 5 (nav/get-target-cursor-pos "hello world" 5 :down))))

  (testing "Target shorter than line-pos - fall back to end"
    ;; line-pos=10, target="hi" (2 chars)
    ;; Should place cursor at end (position 2)
    (is (= 2 (nav/get-target-cursor-pos "hi" 10 :down)))))

(deftest get-target-cursor-pos-direction-test
  (testing "Navigate up - use LAST line (Logseq behavior)"
    ;; Target: "first line\nsecond line"
    ;; line-pos=5, direction=:up
    ;; LOGSEQ: UP uses LAST line: "second line"
    ;; Position in last line = 5, absolute position = 11 (first line) + 1 (\n) + 5 = 16
    (is (= 16 (nav/get-target-cursor-pos "first line\nsecond line" 5 :up))))

  (testing "Navigate down - use FIRST line (Logseq behavior)"
    ;; Target: "first line\nsecond line"
    ;; line-pos=5, direction=:down
    ;; LOGSEQ: DOWN uses FIRST line: "first line"
    ;; Position 5 in "first line" = 5
    (is (= 5 (nav/get-target-cursor-pos "first line\nsecond line" 5 :down)))))

(deftest get-target-cursor-pos-multi-line-down-test
  (testing "Navigate down to multi-line block - use FIRST line (Logseq behavior)"
    ;; Target: "line 1\nline 2\nline 3"
    ;; line-pos=3, direction=:down
    ;; LOGSEQ: DOWN uses FIRST line: "line 1"
    ;; Position 3 in "line 1" = 3
    (is (= 3 (nav/get-target-cursor-pos "line 1\nline 2\nline 3" 3 :down)))))

(deftest get-target-cursor-pos-empty-block-test
  (testing "Empty target block"
    ;; line-pos=5, target=""
    ;; Should return 0
    (is (= 0 (nav/get-target-cursor-pos "" 5 :down)))
    (is (= 0 (nav/get-target-cursor-pos "" 5 :up)))))

;; ── Navigate with cursor memory intent tests ──────────────────────────────────

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-down-with-cursor-memory-test
  (testing "Navigate down preserves cursor column position"
    (let [db (sample-db)
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                       :direction :down
                                       :current-block-id "a"
                                       :current-text "hello world"
                                       :current-cursor-pos 6})] ; After "hello "

      ;; Should store line-pos in cursor-memory
      (is (= 6 (get-in (:ops result) [0 :props :cursor-memory :line-pos])))
      (is (= "a" (get-in (:ops result) [0 :props :cursor-memory :last-block-id])))
      (is (= :down (get-in (:ops result) [0 :props :cursor-memory :direction])))

      ;; Should exit edit on current block
      (is (= nil (get-in (:ops result) [1 :props :editing-block-id])))

      ;; Should enter edit on next block
      (is (= "b" (get-in (:ops result) [2 :props :editing-block-id])))

      ;; KEY TEST: Cursor at same column (after "foo ba")
      (is (= 6 (get-in (:ops result) [2 :props :cursor-position]))))))

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-up-with-cursor-memory-test
  (testing "Navigate up preserves cursor column position"
    (let [db (sample-db)
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                 :direction :up
                                 :current-block-id "b"
                                 :current-text "foo bar baz"
                                 :current-cursor-pos 4})] ; After "foo "

      ;; Should store line-pos
      (is (= 4 (get-in (:ops result) [0 :props :cursor-memory :line-pos])))

      ;; Should navigate to previous block
      (is (= "a" (get-in (:ops result) [2 :props :editing-block-id])))

      ;; Cursor at position 4 (after "hell")
      (is (= 4 (get-in (:ops result) [2 :props :cursor-position]))))))

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-up-target-shorter-goes-to-end-test
  (testing "Arrow up with short target block goes to end"
    (let [db (-> (DB/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "hi"}}
                                {:op :place :id "a" :under :doc :at :last}
                                {:op :create-node :id "b" :type :block :props {:text "hello world"}}
                                {:op :place :id "b" :under :doc :at :last}])
                 :db)
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                 :direction :up
                                 :current-block-id "b"
                                 :current-text "hello world"
                                 :current-cursor-pos 8})] ; After "hello wo"

      ;; Target "hi" is only 2 chars, cursor should be at end
      (is (= :end (get-in (:ops result) [2 :props :cursor-position]))))))

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-with-empty-block-test
  (testing "Navigate from empty block"
    (let [db (-> (DB/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                {:op :place :id "a" :under :doc :at :last}
                                {:op :create-node :id "b" :type :block :props {:text ""}}
                                {:op :place :id "b" :under :doc :at :last}])
                 :db)
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                 :direction :up
                                 :current-block-id "b"
                                 :current-text ""
                                 :current-cursor-pos 0})]

      ;; Line pos should be 0
      (is (= 0 (get-in (:ops result) [0 :props :cursor-memory :line-pos])))

      ;; Should navigate to prev block
      (is (= "a" (get-in (:ops result) [2 :props :editing-block-id])))

      ;; Cursor should be at start of target (position 0)
      (is (= :start (get-in (:ops result) [2 :props :cursor-position]))))))

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-to-empty-block-test
  (testing "Navigate to empty block"
    (let [db (-> (DB/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                {:op :place :id "a" :under :doc :at :last}
                                {:op :create-node :id "b" :type :block :props {:text ""}}
                                {:op :place :id "b" :under :doc :at :last}])
                 :db)
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                 :direction :down
                                 :current-block-id "a"
                                 :current-text "hello"
                                 :current-cursor-pos 3})]

      ;; Target is empty, cursor should be at start (0)
      (is (= :start (get-in (:ops result) [2 :props :cursor-position]))))))

(deftest ^{:fr/ids #{:fr.nav/vertical-cursor-memory}}
  navigate-no-sibling-throws-test
  (testing "Arrow navigation with no sibling returns no-op (Logseq behavior)"
    (let [db (-> (DB/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "only block"}}
                                {:op :place :id "a" :under :doc :at :last}])
                 :db)
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                          :direction :down
                                          :current-block-id "a"
                                          :current-text "only block"
                                          :current-cursor-pos 5})]

      ;; Should not throw, but return no-op that keeps cursor in place at boundary
      (is (some? result))
      ;; Should stay in same block
      (is (= "a" (get-in result [:ops 0 :props :editing-block-id])))
      ;; Cursor should be at end (going down with no target)
      (is (= 10 (get-in result [:ops 0 :props :cursor-position]))))))

(deftest navigate-multi-line-cursor-memory-test
  (testing "Navigate between multi-line blocks preserves column"
    (let [db (-> (DB/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block
                                 :props {:text "line 1\nline 2\nline 3"}}
                                {:op :place :id "a" :under :doc :at :last}
                                {:op :create-node :id "b" :type :block
                                 :props {:text "foo\nbar\nbaz"}}
                                {:op :place :id "b" :under :doc :at :last}])
                 :db)
          ;; Cursor on "line 3" at position 3 (after "lin")
          ;; Absolute position: "line 1\n" (7) + "line 2\n" (7) + 3 = 17
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                 :direction :down
                                 :current-block-id "a"
                                 :current-text "line 1\nline 2\nline 3"
                                 :current-cursor-pos 17})]

      ;; Line pos should be 3 (position within "line 3")
      (is (= 3 (get-in (:ops result) [0 :props :cursor-memory :line-pos])))

      ;; Target cursor position: LOGSEQ behavior - going DOWN lands on FIRST line "foo"
      ;; Position 3 in "foo" (which is only 3 chars) = numeric 3 (not :end keyword)
      (is (= 3 (get-in (:ops result) [2 :props :cursor-position])))))) ; "foo" is 3 chars, position 3

;; ── Edge cases and integration tests ──────────────────────────────────────────

(deftest cursor-at-start-of-block-test
  (testing "Navigate down from start of block"
    (let [db (sample-db)
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                 :direction :down
                                 :current-block-id "a"
                                 :current-text "hello world"
                                 :current-cursor-pos 0})]

      ;; Line pos = 0
      (is (= 0 (get-in (:ops result) [0 :props :cursor-memory :line-pos])))

      ;; Target cursor should be at start
      (is (= :start (get-in (:ops result) [2 :props :cursor-position]))))))

(deftest cursor-at-end-of-block-test
  (testing "Navigate up from end of block"
    (let [db (sample-db)
          result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                 :direction :up
                                 :current-block-id "b"
                                 :current-text "foo bar baz"
                                 :current-cursor-pos 11})] ; At end (11 chars)

      ;; Line pos = 11
      (is (= 11 (get-in (:ops result) [0 :props :cursor-memory :line-pos])))

      ;; Target "hello world" is also 11 chars, should be at end
      (is (= :end (get-in (:ops result) [2 :props :cursor-position]))))))

;; ── Navigate To Adjacent Tests ────────────────────────────────────────────────

(deftest navigate-to-adjacent-up-test
  (testing "Navigate up to previous block with max cursor position"
    (let [db (sample-db)
          {:keys [ops]} (intent/apply-intent db {:type :navigate-to-adjacent
                                                  :direction :up
                                                  :current-block-id "b"
                                                  :cursor-position :max})
          db' (:db (tx/interpret db ops))]
      ;; Should edit previous block
      (is (= "a" (get-in db' [:nodes "session/ui" :props :editing-block-id])))
      ;; Cursor at end of "hello world" (11 chars)
      (is (= 11 (get-in db' [:nodes "session/ui" :props :cursor-position]))))))

(deftest navigate-to-adjacent-down-test
  (testing "Navigate down to next block with cursor position 0"
    (let [db (sample-db)
          {:keys [ops]} (intent/apply-intent db {:type :navigate-to-adjacent
                                                  :direction :down
                                                  :current-block-id "a"
                                                  :cursor-position 0})
          db' (:db (tx/interpret db ops))]
      ;; Should edit next block
      (is (= "b" (get-in db' [:nodes "session/ui" :props :editing-block-id])))
      ;; Cursor at start
      (is (= 0 (get-in db' [:nodes "session/ui" :props :cursor-position]))))))

(deftest navigate-to-adjacent-numeric-cursor-test
  (testing "Navigate with specific cursor position"
    (let [db (sample-db)
          {:keys [ops]} (intent/apply-intent db {:type :navigate-to-adjacent
                                                  :direction :down
                                                  :current-block-id "a"
                                                  :cursor-position 3})
          db' (:db (tx/interpret db ops))]
      ;; Should edit next block
      (is (= "b" (get-in db' [:nodes "session/ui" :props :editing-block-id])))
      ;; Cursor at position 3
      (is (= 3 (get-in db' [:nodes "session/ui" :props :cursor-position]))))))

(deftest navigate-to-adjacent-no-target-test
  (testing "Navigate with no adjacent block does nothing"
    (let [db (-> (DB/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "Only"}}
                                {:op :place :id "a" :under :doc :at :last}])
                 :db)
          {:keys [ops]} (intent/apply-intent db {:type :navigate-to-adjacent
                                                  :direction :down
                                                  :current-block-id "a"
                                                  :cursor-position 0})]
      ;; Should return empty ops (no target)
      (is (empty? ops)))))
