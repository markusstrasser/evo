(ns plugins.smart-editing-test
  "Tests for smart editing behaviors: merge, list formatting, checkboxes."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.query :as q]
            [kernel.intent :as intent]
            [plugins.smart-editing])) ;; Load to register intents

;; ── Session helpers ──────────────────────────────────────────────────────────

(defn empty-session
  "Create an empty session for testing."
  []
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :buffer {:block-id nil :text "" :dirty? false}
   :ui {:folded #{}
        :zoom-root nil
        :zoom-stack []
        :current-page nil
        :editing-block-id nil
        :cursor-position nil}
   :sidebar {:right []}})

(defn apply-session-updates
  "Apply session-updates returned by a handler to a session."
  [session session-updates]
  (if session-updates
    (merge-with merge session session-updates)
    session))

(defn run-intent
  "Run intent and return {:db ... :session ...}"
  [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (if (seq ops) (:db (tx/interpret db ops)) db)
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session}))

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
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :merge-with-next :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= "First blockSecond block" (get-in db' [:nodes "a" :props :text])))
        ;; Next block should be in trash
        (is (= :trash (q/parent-of db' "b")))))

    (testing "merge with next migrates children"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :merge-with-next :block-id "a"})
            db' (:db (tx/interpret db ops))]
        ;; Child c should now be under a
        (is (= "a" (q/parent-of db' "c")))
        (is (= ["c"] (q/children db' "a")))))

    (testing "merge with next on last block does nothing"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :merge-with-next :block-id "d"})]
        (is (empty? ops))))

    (testing "merge with next when next has no children"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :merge-with-next :block-id "b"})
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
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :unformat-empty-list :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= "" (get-in db' [:nodes "a" :props :text])))))

    (testing "unformat asterisk marker"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :unformat-empty-list :block-id "b"})
            db' (:db (tx/interpret db ops))]
        (is (= "" (get-in db' [:nodes "b" :props :text])))))

    (testing "unformat numbered marker"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :unformat-empty-list :block-id "c"})
            db' (:db (tx/interpret db ops))]
        (is (= "" (get-in db' [:nodes "c" :props :text])))))

    (testing "non-empty list item unchanged"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :unformat-empty-list :block-id "d"})]
        (is (empty? ops))))

    (testing "plain text unchanged"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :unformat-empty-list :block-id "e"})]
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
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :split-with-list-increment
                                                   :block-id "a"
                                                   :cursor-pos 9}) ;; After "1. First "
            db' (:db (tx/interpret db ops))
            children (q/children db' :doc)
            new-block-id (second children)]
        (is (= "1. First " (get-in db' [:nodes "a" :props :text])))
        (is (= "2. item" (get-in db' [:nodes new-block-id :props :text])))))

    (testing "split plain list item doesn't add number"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :split-with-list-increment
                                                   :block-id "b"
                                                   :cursor-pos 8}) ;; After "- Plain "
            db' (:db (tx/interpret db ops))
            children (q/children db' :doc)
            new-block-id (first (remove #{"a" "b" "c"} children))]
        (is (= "- Plain " (get-in db' [:nodes "b" :props :text])))
        (is (= "item" (get-in db' [:nodes new-block-id :props :text])))))

    (testing "split multi-digit numbered list"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :split-with-list-increment
                                                   :block-id "c"
                                                   :cursor-pos 10}) ;; After "10. Tenth "
            db' (:db (tx/interpret db ops))
            children (q/children db' :doc)
            new-block-id (first (remove #{"a" "b" "c"} children))]
        (is (= "10. Tenth " (get-in db' [:nodes "c" :props :text])))
        (is (= "11. item" (get-in db' [:nodes new-block-id :props :text])))))

    (testing "split at beginning of numbered list"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :split-with-list-increment
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
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :toggle-checkbox :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= "[x] Unchecked task" (get-in db' [:nodes "a" :props :text])))))

    (testing "toggle checked to unchecked"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :toggle-checkbox :block-id "b"})
            db' (:db (tx/interpret db ops))]
        (is (= "[ ] Checked task" (get-in db' [:nodes "b" :props :text])))))

    (testing "toggle capital X normalizes to lowercase"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :toggle-checkbox :block-id "c"})
            db' (:db (tx/interpret db ops))]
        (is (= "[ ] Capital X task" (get-in db' [:nodes "c" :props :text])))))

    (testing "toggle without checkbox does nothing"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :toggle-checkbox :block-id "d"})]
        (is (empty? ops))))

    (testing "toggle with multiple checkboxes affects first only"
      (let [{:keys [ops]} (intent/apply-intent db nil {:type :toggle-checkbox :block-id "e"})
            db' (:db (tx/interpret db ops))]
        (is (= "Multiple [x] checkboxes [ ]" (get-in db' [:nodes "e" :props :text])))))))

;; ── Paired Character Tests ────────────────────────────────────────────────────

(deftest paired-char-insertion-test
  (testing "Opening bracket auto-closes"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :insert-paired-char
                                                       :block-id "a"
                                                       :cursor-pos 5
                                                       :input-char "["})]
      (is (= "hello[]" (get-in db [:nodes "a" :props :text])))
      (is (= 6 (get-in session [:ui :cursor-position])))))

  (testing "Closing bracket skips over when next char matches"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello[]"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :insert-paired-char
                                                       :block-id "a"
                                                       :cursor-pos 6
                                                       :input-char "]"})]
      ;; Should move cursor, not insert
      (is (= "hello[]" (get-in db [:nodes "a" :props :text])))
      (is (= 7 (get-in session [:ui :cursor-position])))))

  (testing "Non-paired char inserts normally"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :insert-paired-char
                                                       :block-id "a"
                                                       :cursor-pos 5
                                                       :input-char "x"})]
      (is (= "hellox" (get-in db [:nodes "a" :props :text])))
      (is (= 6 (get-in session [:ui :cursor-position])))))

  (testing "Parentheses auto-close"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text ""}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :insert-paired-char
                                                       :block-id "a"
                                                       :cursor-pos 0
                                                       :input-char "("})]
      (is (= "()" (get-in db [:nodes "a" :props :text])))
      (is (= 1 (get-in session [:ui :cursor-position])))))

  (testing "Markup pairs auto-close"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "text"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :insert-paired-char
                                                       :block-id "a"
                                                       :cursor-pos 0
                                                       :input-char "**"})]
      (is (= "****text" (get-in db [:nodes "a" :props :text])))
      (is (= 2 (get-in session [:ui :cursor-position]))))))

(deftest paired-deletion-test
  (testing "Backspace after [ deletes both [ and ]"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "[]"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :delete-with-pair-check
                                                       :block-id "a"
                                                       :cursor-pos 1})]
      (is (= "" (get-in db [:nodes "a" :props :text])))
      (is (= 0 (get-in session [:ui :cursor-position])))))

  (testing "Backspace with no pair deletes one char"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :delete-with-pair-check
                                                       :block-id "a"
                                                       :cursor-pos 5})]
      (is (= "hell" (get-in db [:nodes "a" :props :text])))
      (is (= 4 (get-in session [:ui :cursor-position])))))

  (testing "Backspace with markup pairs"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "****"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :delete-with-pair-check
                                               :block-id "a"
                                               :cursor-pos 2})]
      (is (= "" (get-in db [:nodes "a" :props :text])))))

  (testing "Backspace when next char doesn't match"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "[x"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :delete-with-pair-check
                                               :block-id "a"
                                               :cursor-pos 1})]
      (is (= "x" (get-in db [:nodes "a" :props :text])))))

  (testing "Backspace at position 0 does nothing"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "text"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [ops]} (intent/apply-intent db session {:type :delete-with-pair-check
                                                         :block-id "a"
                                                         :cursor-pos 0})]
      (is (empty? ops)))))

;; ── Context-Aware Enter Tests ──────────────────────────────────────────────────

(deftest context-aware-enter-markup-test
  (testing "Enter inside bold exits markup first"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "**hello world**"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :context-aware-enter
                                                       :block-id "a"
                                                       :cursor-pos 5})] ; After "**hel"
      ;; Should move cursor to after **, not split
      (is (= 15 (get-in session [:ui :cursor-position])))
      ;; Text should be unchanged
      (is (= "**hello world**" (get-in db [:nodes "a" :props :text])))))

  (testing "Enter after exiting markup creates new block"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "**bold**"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :context-aware-enter
                                               :block-id "a"
                                               :cursor-pos 8})] ; After closing **
      ;; Should create new block
      (is (= 2 (count (q/children db :doc)))))))

(deftest context-aware-enter-code-block-test
  (testing "Enter inside code block inserts newline"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "```clojure\n(+ 1 2)\n```"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :context-aware-enter
                                                       :block-id "a"
                                                       :cursor-pos 18})] ; After "(+ 1 2)"
      ;; Should insert newline, not create new block
      (is (= "```clojure\n(+ 1 2)\n\n```" (get-in db [:nodes "a" :props :text])))
      (is (= 19 (get-in session [:ui :cursor-position])))
      ;; No new block created
      (is (= 1 (count (q/children db :doc)))))))

(deftest context-aware-enter-list-test
  (testing "LOGSEQ PARITY: Enter on empty list unformats AND creates peer block"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                 {:op :create-node :id "child" :type :block :props {:text "- "}}
                                 {:op :place :id "parent" :under :doc :at :last}
                                 {:op :place :id "child" :under "parent" :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :context-aware-enter
                                                       :block-id "child"
                                                       :cursor-pos 2})]
      ;; Current block should be unformatted
      (is (= "" (get-in db [:nodes "child" :props :text])))
      ;; New peer block should be created after parent
      (is (= 2 (count (q/children db :doc)))
          "Should have parent + new peer block at doc level")
      (let [doc-children (q/children db :doc)
            new-peer-id (second doc-children)]
        (is (= "parent" (first doc-children)))
        (is (= "" (get-in db [:nodes new-peer-id :props :text])))
        ;; Cursor should be in new peer block
        (is (= new-peer-id (get-in session [:ui :editing-block-id])))
        (is (= 0 (get-in session [:ui :cursor-position]))))))

  (testing "Enter on numbered list increments"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "1. First item"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :context-aware-enter
                                                       :block-id "a"
                                                       :cursor-pos 13})] ; At end
      (let [children (q/children db :doc)
            new-block-id (second children)]
        (is (= "1. First item" (get-in db [:nodes "a" :props :text])))
        (is (= "2. " (get-in db [:nodes new-block-id :props :text])))
        (is (= 3 (get-in session [:ui :cursor-position]))))))

  (testing "Enter on simple list continues marker"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "- Item one"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :context-aware-enter
                                                       :block-id "a"
                                                       :cursor-pos 10})]
      (let [children (q/children db :doc)
            new-block-id (second children)]
        (is (= "- Item one" (get-in db [:nodes "a" :props :text])))
        (is (= "- " (get-in db [:nodes new-block-id :props :text])))
        (is (= 2 (get-in session [:ui :cursor-position])))))))

(deftest context-aware-enter-checkbox-test
  (testing "Enter on empty checkbox unformats"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "- [ ] "}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :context-aware-enter
                                               :block-id "a"
                                               :cursor-pos 6})]
      (is (= "" (get-in db [:nodes "a" :props :text])))
      (is (= 1 (count (q/children db :doc))))))

  (testing "Enter on checkbox continues pattern"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "- [ ] Task one"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :context-aware-enter
                                                       :block-id "a"
                                                       :cursor-pos 14})]
      (let [children (q/children db :doc)
            new-block-id (second children)]
        (is (= "- [ ] Task one" (get-in db [:nodes "a" :props :text])))
        (is (= "- [ ] " (get-in db [:nodes new-block-id :props :text])))
        (is (= 6 (get-in session [:ui :cursor-position])))))))

(deftest context-aware-enter-plain-text-test
  (testing "Enter on plain text splits normally (Logseq parity: trim left)"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello world"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :context-aware-enter
                                                       :block-id "a"
                                                       :cursor-pos 5})]
      (let [children (q/children db :doc)
            new-block-id (second children)]
        (is (= "hello" (get-in db [:nodes "a" :props :text])))
        ;; LOGSEQ PARITY: Second block text is left-trimmed
        (is (= "world" (get-in db [:nodes new-block-id :props :text]))
            "Second block should have leading whitespace trimmed (Logseq parity)")
        (is (= 0 (get-in session [:ui :cursor-position])))))))

;; ── Integration Tests ─────────────────────────────────────────────────────────

(deftest smart-editing-integration
  (testing "merge and split round-trip"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Hello"}}
                                 {:op :create-node :id "b" :type :block :props {:text "World"}}
                                 {:op :place :id "a" :under :doc :at :last}
                                 {:op :place :id "b" :under :doc :at :last}]))
          ;; Merge
          {:keys [ops]} (intent/apply-intent db nil {:type :merge-with-next :block-id "a"})
          db' (:db (tx/interpret db ops))
          ;; Split back
          {:keys [ops]} (intent/apply-intent db' nil {:type :split-with-list-increment
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
          {:keys [ops]} (intent/apply-intent db nil {:type :unformat-empty-list :block-id "a"})
          db' (:db (tx/interpret db ops))
          ;; Add new content and split
          db'' (:db (tx/interpret db' [{:op :update-node :id "a" :props {:text "New content"}}]))
          {:keys [ops]} (intent/apply-intent db'' nil {:type :split-with-list-increment
                                                   :block-id "a"
                                                   :cursor-pos 3})
          db''' (:db (tx/interpret db'' ops))
          children (q/children db''' :doc)
          new-id (second children)]
      (is (= "New" (get-in db''' [:nodes "a" :props :text])))
      ;; Should not have number since original was unformatted
      (is (= " content" (get-in db''' [:nodes new-id :props :text]))))))
