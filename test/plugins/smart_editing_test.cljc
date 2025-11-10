(ns plugins.smart-editing-test
  "Tests for smart editing behaviors: merge, list formatting, checkboxes."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.query :as q]
            [kernel.intent :as intent]
            [plugins.smart-editing]))  ;; Load to register intents

;; ── Test Setup ────────────────────────────────────────────────────────────────

(defn setup-blocks
  "Create test blocks:
     doc
     ├─ a 'First block'
     ├─ b 'Second block'
     │  └─ c 'Child of b'
     └─ d 'Third block'"
  []
  (:db (tx/interpret (db/empty-db)
                     [{:op :create-node :id "a" :type :block :props {:text "First block"}}
                      {:op :create-node :id "b" :type :block :props {:text "Second block"}}
                      {:op :create-node :id "c" :type :block :props {:text "Child of b"}}
                      {:op :create-node :id "d" :type :block :props {:text "Third block"}}
                      {:op :place :id "a" :under :doc :at :last}
                      {:op :place :id "b" :under :doc :at :last}
                      {:op :place :id "c" :under "b" :at :last}
                      {:op :place :id "d" :under :doc :at :last}])))

;; ── Merge with Next Tests ─────────────────────────────────────────────────────

(deftest merge-with-next-test
  (let [db (setup-blocks)]
    (testing "merge with next combines text"
      (let [{:keys [ops]} (intent/apply-intent db {:type :merge-with-next :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= "First blockSecond block" (get-in db' [:nodes "a" :props :text])))
        ;; Next block should be in trash
        (is (= :trash (q/parent-of db' "b")))))

    (testing "merge with next migrates children"
      (let [{:keys [ops]} (intent/apply-intent db {:type :merge-with-next :block-id "a"})
            db' (:db (tx/interpret db ops))]
        ;; Child c should now be under a
        (is (= "a" (q/parent-of db' "c")))
        (is (= ["c"] (q/children db' "a")))))

    (testing "merge with next on last block does nothing"
      (let [{:keys [ops]} (intent/apply-intent db {:type :merge-with-next :block-id "d"})]
        (is (empty? ops))))

    (testing "merge with next when next has no children"
      (let [{:keys [ops]} (intent/apply-intent db {:type :merge-with-next :block-id "b"})
            db' (:db (tx/interpret db ops))]
        (is (= "Second blockThird block" (get-in db' [:nodes "b" :props :text])))
        (is (= :trash (q/parent-of db' "d")))))))

;; ── Unformat Empty List Tests ─────────────────────────────────────────────────

(deftest unformat-empty-list-test
  (let [db (:db (tx/interpret (db/empty-db)
                              [{:op :create-node :id "a" :type :block :props {:text "- "}}
                               {:op :create-node :id "b" :type :block :props {:text "* "}}
                               {:op :create-node :id "c" :type :block :props {:text "1. "}}
                               {:op :create-node :id "d" :type :block :props {:text "- item"}}
                               {:op :create-node :id "e" :type :block :props {:text "plain"}}
                               {:op :place :id "a" :under :doc :at :last}
                               {:op :place :id "b" :under :doc :at :last}
                               {:op :place :id "c" :under :doc :at :last}
                               {:op :place :id "d" :under :doc :at :last}
                               {:op :place :id "e" :under :doc :at :last}]))]

    (testing "unformat dash marker"
      (let [{:keys [ops]} (intent/apply-intent db {:type :unformat-empty-list :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= "" (get-in db' [:nodes "a" :props :text])))))

    (testing "unformat asterisk marker"
      (let [{:keys [ops]} (intent/apply-intent db {:type :unformat-empty-list :block-id "b"})
            db' (:db (tx/interpret db ops))]
        (is (= "" (get-in db' [:nodes "b" :props :text])))))

    (testing "unformat numbered marker"
      (let [{:keys [ops]} (intent/apply-intent db {:type :unformat-empty-list :block-id "c"})
            db' (:db (tx/interpret db ops))]
        (is (= "" (get-in db' [:nodes "c" :props :text])))))

    (testing "non-empty list item unchanged"
      (let [{:keys [ops]} (intent/apply-intent db {:type :unformat-empty-list :block-id "d"})]
        (is (empty? ops))))

    (testing "plain text unchanged"
      (let [{:keys [ops]} (intent/apply-intent db {:type :unformat-empty-list :block-id "e"})]
        (is (empty? ops))))))

;; ── Split with List Increment Tests ──────────────────────────────────────────

(deftest split-with-list-increment-test
  (let [db (:db (tx/interpret (db/empty-db)
                              [{:op :create-node :id "a" :type :block :props {:text "1. First item"}}
                               {:op :create-node :id "b" :type :block :props {:text "- Plain item"}}
                               {:op :create-node :id "c" :type :block :props {:text "10. Tenth item"}}
                               {:op :place :id "a" :under :doc :at :last}
                               {:op :place :id "b" :under :doc :at :last}
                               {:op :place :id "c" :under :doc :at :last}]))]

    (testing "split numbered list increments number"
      (let [{:keys [ops]} (intent/apply-intent db {:type :split-with-list-increment
                                                   :block-id "a"
                                                   :cursor-pos 9}) ;; After "1. First "
            db' (:db (tx/interpret db ops))
            children (q/children db' :doc)
            new-block-id (second children)]
        (is (= "1. First " (get-in db' [:nodes "a" :props :text])))
        (is (= "2. item" (get-in db' [:nodes new-block-id :props :text])))))

    (testing "split plain list item doesn't add number"
      (let [{:keys [ops]} (intent/apply-intent db {:type :split-with-list-increment
                                                   :block-id "b"
                                                   :cursor-pos 8}) ;; After "- Plain "
            db' (:db (tx/interpret db ops))
            children (q/children db' :doc)
            new-block-id (first (remove #{"a" "b" "c"} children))]
        (is (= "- Plain " (get-in db' [:nodes "b" :props :text])))
        (is (= "item" (get-in db' [:nodes new-block-id :props :text])))))

    (testing "split multi-digit numbered list"
      (let [{:keys [ops]} (intent/apply-intent db {:type :split-with-list-increment
                                                   :block-id "c"
                                                   :cursor-pos 10}) ;; After "10. Tenth "
            db' (:db (tx/interpret db ops))
            children (q/children db' :doc)
            new-block-id (first (remove #{"a" "b" "c"} children))]
        (is (= "10. Tenth " (get-in db' [:nodes "c" :props :text])))
        (is (= "11. item" (get-in db' [:nodes new-block-id :props :text])))))

    (testing "split at beginning of numbered list"
      (let [{:keys [ops]} (intent/apply-intent db {:type :split-with-list-increment
                                                   :block-id "a"
                                                   :cursor-pos 0})
            db' (:db (tx/interpret db ops))
            children (q/children db' :doc)
            new-block-id (second children)]
        (is (= "" (get-in db' [:nodes "a" :props :text])))
        (is (= "2. 1. First item" (get-in db' [:nodes new-block-id :props :text])))))))

;; ── Toggle Checkbox Tests ─────────────────────────────────────────────────────

(deftest toggle-checkbox-test
  (let [db (:db (tx/interpret (db/empty-db)
                              [{:op :create-node :id "a" :type :block :props {:text "[ ] Unchecked task"}}
                               {:op :create-node :id "b" :type :block :props {:text "[x] Checked task"}}
                               {:op :create-node :id "c" :type :block :props {:text "[X] Capital X task"}}
                               {:op :create-node :id "d" :type :block :props {:text "No checkbox"}}
                               {:op :create-node :id "e" :type :block :props {:text "Multiple [ ] checkboxes [ ]"}}
                               {:op :place :id "a" :under :doc :at :last}
                               {:op :place :id "b" :under :doc :at :last}
                               {:op :place :id "c" :under :doc :at :last}
                               {:op :place :id "d" :under :doc :at :last}
                               {:op :place :id "e" :under :doc :at :last}]))]

    (testing "toggle unchecked to checked"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-checkbox :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= "[x] Unchecked task" (get-in db' [:nodes "a" :props :text])))))

    (testing "toggle checked to unchecked"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-checkbox :block-id "b"})
            db' (:db (tx/interpret db ops))]
        (is (= "[ ] Checked task" (get-in db' [:nodes "b" :props :text])))))

    (testing "toggle capital X normalizes to lowercase"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-checkbox :block-id "c"})
            db' (:db (tx/interpret db ops))]
        (is (= "[ ] Capital X task" (get-in db' [:nodes "c" :props :text])))))

    (testing "toggle without checkbox does nothing"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-checkbox :block-id "d"})]
        (is (empty? ops))))

    (testing "toggle with multiple checkboxes affects first only"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-checkbox :block-id "e"})
            db' (:db (tx/interpret db ops))]
        (is (= "Multiple [x] checkboxes [ ]" (get-in db' [:nodes "e" :props :text])))))))

;; ── Paired Character Tests ────────────────────────────────────────────────────

(deftest paired-char-insertion-test
  (testing "Opening bracket auto-closes"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :insert-paired-char
                                                  :block-id "a"
                                                  :cursor-pos 5
                                                  :char "["})
          db' (:db (tx/interpret db ops))]
      (is (= "hello[]" (get-in db' [:nodes "a" :props :text])))
      (is (= 6 (get-in db' [:nodes "session/ui" :props :cursor-position])))))

  (testing "Closing bracket skips over when next char matches"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello[]"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :insert-paired-char
                                                  :block-id "a"
                                                  :cursor-pos 6
                                                  :char "]"})
          db' (:db (tx/interpret db ops))]
      ;; Should move cursor, not insert
      (is (= "hello[]" (get-in db' [:nodes "a" :props :text])))
      (is (= 7 (get-in db' [:nodes "session/ui" :props :cursor-position])))))

  (testing "Non-paired char inserts normally"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :insert-paired-char
                                                  :block-id "a"
                                                  :cursor-pos 5
                                                  :char "x"})
          db' (:db (tx/interpret db ops))]
      (is (= "hellox" (get-in db' [:nodes "a" :props :text])))
      (is (= 6 (get-in db' [:nodes "session/ui" :props :cursor-position])))))

  (testing "Parentheses auto-close"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text ""}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :insert-paired-char
                                                  :block-id "a"
                                                  :cursor-pos 0
                                                  :char "("})
          db' (:db (tx/interpret db ops))]
      (is (= "()" (get-in db' [:nodes "a" :props :text])))
      (is (= 1 (get-in db' [:nodes "session/ui" :props :cursor-position])))))

  (testing "Markup pairs auto-close"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "text"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :insert-paired-char
                                                  :block-id "a"
                                                  :cursor-pos 0
                                                  :char "**"})
          db' (:db (tx/interpret db ops))]
      (is (= "****text" (get-in db' [:nodes "a" :props :text])))
      (is (= 2 (get-in db' [:nodes "session/ui" :props :cursor-position]))))))

(deftest paired-deletion-test
  (testing "Backspace after [ deletes both [ and ]"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "[]"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-with-pair-check
                                                  :block-id "a"
                                                  :cursor-pos 1})
          db' (:db (tx/interpret db ops))]
      (is (= "" (get-in db' [:nodes "a" :props :text])))
      (is (= 0 (get-in db' [:nodes "session/ui" :props :cursor-position])))))

  (testing "Backspace with no pair deletes one char"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-with-pair-check
                                                  :block-id "a"
                                                  :cursor-pos 5})
          db' (:db (tx/interpret db ops))]
      (is (= "hell" (get-in db' [:nodes "a" :props :text])))
      (is (= 4 (get-in db' [:nodes "session/ui" :props :cursor-position])))))

  (testing "Backspace with markup pairs"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "****"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-with-pair-check
                                                  :block-id "a"
                                                  :cursor-pos 2})
          db' (:db (tx/interpret db ops))]
      (is (= "" (get-in db' [:nodes "a" :props :text])))))

  (testing "Backspace when next char doesn't match"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "[x"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-with-pair-check
                                                  :block-id "a"
                                                  :cursor-pos 1})
          db' (:db (tx/interpret db ops))]
      (is (= "x" (get-in db' [:nodes "a" :props :text])))))

  (testing "Backspace at position 0 does nothing"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "text"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :delete-with-pair-check
                                                  :block-id "a"
                                                  :cursor-pos 0})]
      (is (empty? ops)))))

;; ── Integration Tests ─────────────────────────────────────────────────────────

(deftest smart-editing-integration
  (testing "merge and split round-trip"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Hello"}}
                                 {:op :create-node :id "b" :type :block :props {:text "World"}}
                                 {:op :place :id "a" :under :doc :at :last}
                                 {:op :place :id "b" :under :doc :at :last}]))
          ;; Merge
          {:keys [ops]} (intent/apply-intent db {:type :merge-with-next :block-id "a"})
          db' (:db (tx/interpret db ops))
          ;; Split back
          {:keys [ops]} (intent/apply-intent db' {:type :split-with-list-increment
                                                  :block-id "a"
                                                  :cursor-pos 5})
          db'' (:db (tx/interpret db' ops))
          children (q/children db'' :doc)
          new-id (second children)]
      (is (= "Hello" (get-in db'' [:nodes "a" :props :text])))
      (is (= "World" (get-in db'' [:nodes new-id :props :text])))))

  (testing "list increment after unformat"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "1. "}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          ;; Unformat
          {:keys [ops]} (intent/apply-intent db {:type :unformat-empty-list :block-id "a"})
          db' (:db (tx/interpret db ops))
          ;; Add new content and split
          db'' (:db (tx/interpret db' [{:op :update-node :id "a" :props {:text "New content"}}]))
          {:keys [ops]} (intent/apply-intent db'' {:type :split-with-list-increment
                                                   :block-id "a"
                                                   :cursor-pos 3})
          db''' (:db (tx/interpret db'' ops))
          children (q/children db''' :doc)
          new-id (second children)]
      (is (= "New" (get-in db''' [:nodes "a" :props :text])))
      ;; Should not have number since original was unformatted
      (is (= " content" (get-in db''' [:nodes new-id :props :text]))))))
