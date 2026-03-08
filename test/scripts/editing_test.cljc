(ns scripts.editing-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [scripts.editing :as edit]
            [scripts.script :as script]
            [kernel.transaction :as tx]
            [kernel.db :as db]
            [kernel.constants :as const]
            [fixtures :as fix]))

;; ── Test Fixtures ──────────────────────────────────────────────────────────────

(defn sample-blocks-db
  "Create DB with blocks: A → [B → [C], D]

   Tree structure:
     doc
       A
         B
           C
         D"
  []
  (-> (fix/sample-db-with-roots)
      ;; Create nodes
      (assoc-in [:nodes "a"] {:type :block :props {:text "Block A"}})
      (assoc-in [:nodes "b"] {:type :block :props {:text "Block B"}})
      (assoc-in [:nodes "c"] {:type :block :props {:text "Block C"}})
      (assoc-in [:nodes "d"] {:type :block :props {:text "Block D"}})

      ;; Build tree structure
      (assoc-in [:children-by-parent :doc] ["a"])
      (assoc-in [:children-by-parent "a"] ["b" "d"])
      (assoc-in [:children-by-parent "b"] ["c"])

      ;; Derive indexes
      db/derive-indexes))

;; ── Smart Backspace ────────────────────────────────────────────────────────────

(deftest 
  test-smart-backspace-with-prev-sibling
  (testing "Delete block, select previous sibling"
    (let [db (sample-blocks-db)

          ;; Delete "d", should select "b" (prev sibling)
          {:keys [ops target-id]} (edit/smart-backspace db {:id "d"})

          ;; Commit to real DB
          result (tx/interpret db ops)]

      ;; No validation errors
      (is (empty? (:issues result)))

      ;; Block "d" moved to trash
      (is (= const/root-trash
             (get-in (:db result) [:derived :parent-of "d"])))

      ;; Structural script reports the next target; caller owns session updates
      (is (= "b" target-id))
      (is (pos? (count ops))))))

(deftest 
  test-smart-backspace-first-child
  (testing "Delete first child, select parent"
    (let [db (sample-blocks-db)

          ;; Delete "b" (first child of "a", has no prev sibling)
          {:keys [ops target-id]} (edit/smart-backspace db {:id "b"})

          result (tx/interpret db ops)]

      ;; No validation errors
      (is (empty? (:issues result)))

      ;; Block "b" moved to trash
      (is (= const/root-trash
             (get-in (:db result) [:derived :parent-of "b"])))

      (is (= "a" target-id))
      (is (pos? (count ops))))))

(deftest 
  test-smart-backspace-single-block
  (testing "Delete only block under parent"
    (let [db (sample-blocks-db)

          ;; Delete "c" (only child of "b")
          {:keys [ops target-id]} (edit/smart-backspace db {:id "c"})

          result (tx/interpret db ops)]

      ;; No validation errors
      (is (empty? (:issues result)))

      ;; Block "c" moved to trash
      (is (= const/root-trash
             (get-in (:db result) [:derived :parent-of "c"])))

      ;; Structural target falls back to parent
      (is (= "b" target-id))
      (is (pos? (count ops))))))

;; ── Paste Multi-Line ───────────────────────────────────────────────────────────

(deftest 
  test-paste-lines-multiple
  (testing "Paste creates multiple blocks"
    (let [db (sample-blocks-db)

          text "Line 1\nLine 2\nLine 3"

          ;; Paste under "a" at last position
          {:keys [ops new-ids first-id]} (edit/paste-lines db {:text text
                                                               :under "a"
                                                               :at :last})

          result (tx/interpret db ops)]

      ;; No validation errors
      (is (empty? (:issues result)))

      ;; Three new blocks created
      (let [children-a (get-in (:db result) [:children-by-parent "a"])]
        ;; Originally had [b, d], now has 3 more
        (is (= 5 (count children-a)))

        ;; Last 3 children are new blocks
        (let [actual-new-ids (take-last 3 children-a)]
          ;; Verify they have correct text
          (is (= "Line 1" (get-in (:db result) [:nodes (nth actual-new-ids 0) :props :text])))
          (is (= "Line 2" (get-in (:db result) [:nodes (nth actual-new-ids 1) :props :text])))
          (is (= "Line 3" (get-in (:db result) [:nodes (nth actual-new-ids 2) :props :text])))
          (is (= (vec actual-new-ids) new-ids)))
        (is (= (first new-ids) first-id))
        (is (= 3 (count new-ids)))))))

(deftest 
  test-paste-lines-single
  (testing "Paste single line creates one block"
    (let [db (sample-blocks-db)

          text "Single line"

          {:keys [ops first-id]} (edit/paste-lines db {:text text
                                                       :under "a"
                                                       :at :first})

          result (tx/interpret db ops)]

      (is (empty? (:issues result)))

      ;; One new block created at start
      (let [children-a (get-in (:db result) [:children-by-parent "a"])]
        (is (= 3 (count children-a)))  ; Originally [b, d] + 1 new

        ;; First child is new block
        (is (= first-id (first children-a)))
        (is (= "Single line"
               (get-in (:db result) [:nodes (first children-a) :props :text])))))))

(deftest 
  test-paste-lines-empty
  (testing "Paste empty lines creates no blocks"
    (let [db (sample-blocks-db)

          text "\n\n\n"  ; All empty lines

          {:keys [ops new-ids first-id]} (edit/paste-lines db {:text text
                                                               :under "a"
                                                               :at :last})

          result (tx/interpret db ops)]

      ;; Should succeed but create no blocks
      (is (empty? (:issues result)))

      ;; No new children (empty lines filtered)
      (let [children-a (get-in (:db result) [:children-by-parent "a"])]
        (is (= 2 (count children-a)))  ; Still just [b, d]
        (is (= [] new-ids))
        (is (nil? first-id))))))

;; ── Insert Block ───────────────────────────────────────────────────────────────

(deftest 
  test-insert-block-basic
  (testing "Insert creates and places block"
    (let [db (sample-blocks-db)

          {:keys [ops new-id]} (edit/insert-block db {:under "a"
                                                      :at :first
                                                      :text "New block"})

          result (tx/interpret db ops)]

      (is (empty? (:issues result)))

      ;; New block created under "a" at first position
      (let [children-a (get-in (:db result) [:children-by-parent "a"])]

        (is (= 3 (count children-a)))  ; [new, b, d]

        ;; Verify new block properties
        (is (= new-id (first children-a)))
        (is (= "New block"
               (get-in (:db result) [:nodes new-id :props :text])))))))

(deftest 
  test-insert-block-after
  (testing "Insert after specific block"
    (let [db (sample-blocks-db)

          {:keys [ops new-id]} (edit/insert-block db {:under "a"
                                                      :at {:after "b"}
                                                      :text ""})

          result (tx/interpret db ops)]

      (is (empty? (:issues result)))

      ;; New block inserted between b and d
      (let [children-a (get-in (:db result) [:children-by-parent "a"])]
        (is (= 3 (count children-a)))  ; [b, new, d]

        ;; Second child is new block
        (is (= new-id (second children-a)))
        (is (= ""
               (get-in (:db result) [:nodes (second children-a) :props :text])))))))

;; ── Property: Macro Simulation === Final Commit ───────────────────────────────

(deftest test-property-simulation-matches-commit
  (testing "Macro scratch DB matches final committed DB"
    (let [db (sample-blocks-db)

          text "Multi\nLine\nPaste"
          lines (remove empty? (str/split-lines text))
          new-ids (repeatedly (count lines) #(str (random-uuid)))

          ;; Run macro manually to get scratch DB
          steps [(mapv (fn [line-text id]
                         {:op :create-node
                          :id id
                          :type :block
                          :props {:text line-text}})
                       lines
                       new-ids)
                 (mapv (fn [id]
                         {:op :place
                          :id id
                          :under "a"
                          :at :last})
                       new-ids)]

          macro-result (script/run db steps)

          ;; Commit to real DB
          tx-result (tx/interpret db (:ops macro-result))]

      ;; Both should succeed
      (is (empty? (:issues tx-result)))

      ;; Key invariant: Final scratch DB === final committed DB
      ;; (modulo trace metadata, etc.)
      (let [scratch-children (get-in (:db macro-result) [:children-by-parent "a"])
            committed-children (get-in (:db tx-result) [:children-by-parent "a"])]

        ;; Same number of children
        (is (= (count scratch-children) (count committed-children)))

        ;; Same child IDs (order matters)
        (is (= scratch-children committed-children))

        ;; Same text for new blocks
        (doseq [id (take-last 3 scratch-children)]
          (is (= (get-in (:db macro-result) [:nodes id :props :text])
                 (get-in (:db tx-result) [:nodes id :props :text]))))))))
