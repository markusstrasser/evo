(ns plugins.clipboard-test
  "Unit tests for clipboard plugin (paste semantics).

   Tests FR-Clipboard-03: Paste behavior with blank lines."
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            ;; Load plugin to register intent handlers
            [plugins.clipboard]
            [utils.session-patch :as session-patch]))

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
  (session-patch/merge-patch session session-updates))

(defn run-intent
  "Run intent and return {:db ... :session ...}"
  [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (if (seq ops) (:db (tx/interpret db ops)) db)
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session}))

;; ── Paste Tests ──────────────────────────────────────────────────────────────

(deftest paste-single-newline-inline-test
  (testing "FR-Clipboard-03: Single newlines stay inline"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Before"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :paste-text
                                                       :block-id "a"
                                                       :cursor-pos 6 ; After "Before"
                                                       :pasted-text "Line 1\nLine 2"})]
      ;; Should stay as single block with literal newlines
      (is (= 1 (count (q/children db :doc))))
      (is (= "BeforeLine 1\nLine 2" (get-in db [:nodes "a" :props :text])))
      ;; Cursor should be at end of pasted text
      (is (= (+ 6 (count "Line 1\nLine 2")) (get-in session [:ui :cursor-position]))))))

(deftest paste-blank-lines-create-blocks-test
  (testing "FR-Clipboard-03: Blank lines create multiple blocks"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "First"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 5 ; At end of "First"
                                               :pasted-text "\n\nSecond\n\nThird"})]
      ;; Should create 3 blocks total (original + 2 new)
      (is (= 3 (count (q/children db :doc))))
      ;; First block gets before + first paragraph
      (is (= "First" (get-in db [:nodes "a" :props :text])))
      ;; New blocks created with remaining paragraphs
      (let [children (q/children db :doc)
            second-id (second children)
            third-id (nth children 2)]
        (is (= "Second" (get-in db [:nodes second-id :props :text])))
        (is (= "Third" (get-in db [:nodes third-id :props :text])))))))

(deftest paste-preserves-list-markers-test
  (testing "FR-Clipboard-03: List markers preserved on paste"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text ""}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 0
                                               :pasted-text "- Item 1\n\n- Item 2\n\n- [ ] Todo"})]
      ;; Should create 3 blocks with markers
      (is (= 3 (count (q/children db :doc))))
      (let [children (q/children db :doc)]
        (is (= "- Item 1" (get-in db [:nodes (first children) :props :text])))
        (is (= "- Item 2" (get-in db [:nodes (second children) :props :text])))
        (is (= "- [ ] Todo" (get-in db [:nodes (nth children 2) :props :text])))))))

(deftest paste-at-cursor-position-test
  (testing "FR-Clipboard-03: Paste at cursor position works correctly"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "StartEnd"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 5 ; Between "Start" and "End"
                                               :pasted-text "Middle\n\nAnother"})]
      ;; Should create 2 blocks
      (is (= 2 (count (q/children db :doc))))
      (let [children (q/children db :doc)]
        ;; First block: "Start" + first paragraph
        (is (= "StartMiddle" (get-in db [:nodes (first children) :props :text])))
        ;; Second block: rest of paragraphs (note: "End" stays with last pasted block)
        ;; Actually per the implementation, remaining text after cursor isn't moved
        (is (= "Another" (get-in db [:nodes (second children) :props :text])))))))

(deftest paste-no-op-empty-text-test
  (testing "Paste with empty text does nothing"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Original"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 0
                                               :pasted-text ""})]
      ;; Text should be unchanged
      (is (= "Original" (get-in db [:nodes "a" :props :text]))))))

;; ── Depth Normalization Tests ────────────────────────────────────────────────

(deftest paste-markdown-depth-normalization-test
  (testing "Space-indented markdown normalizes depth gaps"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text ""}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          ;; 4 spaces = raw depth 2, but should normalize to depth 1
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 0
                                               :pasted-text "- parent\n    - child"})]
      ;; Should create parent with child underneath (not grandchild)
      (is (= 1 (count (q/children db :doc))) "One root block under doc")
      (let [root-id (first (q/children db :doc))
            children-of-root (q/children db root-id)]
        (is (= "parent" (get-in db [:nodes root-id :props :text])))
        (is (= 1 (count children-of-root)) "One child under parent")
        (is (= "child" (get-in db [:nodes (first children-of-root) :props :text])))))))

(deftest paste-markdown-siblings-normalized-test
  (testing "Siblings at same raw depth stay siblings after normalization"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text ""}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          ;; Both children at 4 spaces = raw depth 2, should normalize to siblings at depth 1
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 0
                                               :pasted-text "- parent\n    - child1\n    - child2"})
          root-id (first (q/children db :doc))
          children-of-root (q/children db root-id)]
      (is (= 2 (count children-of-root)) "Two siblings under parent")
      (is (= "child1" (get-in db [:nodes (first children-of-root) :props :text])))
      (is (= "child2" (get-in db [:nodes (second children-of-root) :props :text]))))))

(deftest paste-markdown-deep-hierarchy-normalized-test
  (testing "Deep hierarchy with gaps normalizes to sequential depths"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text ""}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          ;; 8 spaces = raw depth 4, should normalize to depth 2
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 0
                                               :pasted-text "- root\n    - mid\n        - deep"})
          root-id (first (q/children db :doc))
          mid-id (first (q/children db root-id))
          deep-id (first (q/children db mid-id))]
      (is (= "root" (get-in db [:nodes root-id :props :text])))
      (is (= "mid" (get-in db [:nodes mid-id :props :text])))
      (is (= "deep" (get-in db [:nodes deep-id :props :text]))))))

;; ── Smart URL Paste Tests (Logseq Parity) ────────────────────────────────────

(deftest paste-url-over-selection-creates-link-test
  (testing "Pasting URL over selected text creates markdown link"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "click here for info"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          ;; Select "here" (positions 6-10)
          {:keys [db session]} (run-intent db session {:type :paste-text
                                                       :block-id "a"
                                                       :cursor-pos 6
                                                       :selection-end 10
                                                       :pasted-text "https://example.com"})]
      ;; "here" should become a link
      (is (= "click [here](https://example.com) for info"
             (get-in db [:nodes "a" :props :text])))
      ;; Cursor should be after the link
      (is (= (+ 6 (count "[here](https://example.com)"))
             (get-in session [:ui :cursor-position]))))))

(deftest paste-text-over-url-selection-creates-link-test
  (testing "Pasting text over selected URL creates markdown link"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Visit https://example.com today"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          ;; Select the URL (positions 6-25)
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 6
                                               :selection-end 25
                                               :pasted-text "my site"})]
      ;; URL should become a link with pasted text as label
      (is (= "Visit [my site](https://example.com) today"
             (get-in db [:nodes "a" :props :text]))))))

(deftest paste-url-no-selection-stays-inline-test
  (testing "Pasting URL without selection stays as plain text"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "Check out "}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 10 ; At end
                                               :pasted-text "https://example.com"})]
      ;; Just inline paste, no link formatting
      (is (= "Check out https://example.com"
             (get-in db [:nodes "a" :props :text]))))))

(deftest paste-www-url-creates-link-test
  (testing "Pasting www. URL over selection creates link"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "go here"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          ;; Select "here" (positions 3-7)
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 3
                                               :selection-end 7
                                               :pasted-text "www.example.com"})]
      (is (= "go [here](www.example.com)"
             (get-in db [:nodes "a" :props :text]))))))

(deftest paste-both-urls-stays-plain-test
  (testing "When both selection and paste are URLs, no link formatting"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "see https://old.com for more"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          ;; Select old URL, paste new URL
          {:keys [db]} (run-intent db session {:type :paste-text
                                               :block-id "a"
                                               :cursor-pos 4
                                               :selection-end 19
                                               :pasted-text "https://new.com"})]
      ;; Both are URLs, so just replace (no link formatting)
      (is (= "see https://new.com for more"
             (get-in db [:nodes "a" :props :text]))))))

;; ── Block-reference copy ────────────────────────────────────────────────────
;; FR-Clipboard-05: Cmd+Shift+C copies `((id))` for embedding.

(deftest ^{:fr/ids #{:fr.clipboard/block-reference}}
  copy-block-reference-explicit-id
  (testing "explicit :block-id wins"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "hello"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (empty-session)
          {:keys [session]} (run-intent db session
                                        {:type :copy-block-reference :block-id "a"})]
      (is (= "((a))" (get-in session [:ui :clipboard-text]))
          "clipboard-text is the block-reference marker, not the block content")
      (is (= [{:id "a" :depth 0 :text "((a))"}]
             (get-in session [:ui :clipboard-blocks]))
          "internal clipboard-blocks carry the reference text, not the source text"))))

(deftest ^{:fr/ids #{:fr.clipboard/block-reference}}
  copy-block-reference-editing-fallback
  (testing "no :block-id → editing block is copied"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "x"}}
                                 {:op :place :id "a" :under :doc :at :last}]))
          session (assoc-in (empty-session) [:ui :editing-block-id] "a")
          {:keys [session]} (run-intent db session {:type :copy-block-reference})]
      (is (= "((a))" (get-in session [:ui :clipboard-text]))))))

(deftest ^{:fr/ids #{:fr.clipboard/block-reference}}
  copy-block-reference-single-selection-fallback
  (testing "no :block-id + no editing → use sole selected block"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "b" :type :block :props {:text "y"}}
                                 {:op :place :id "b" :under :doc :at :last}]))
          session (assoc-in (empty-session) [:selection :nodes] #{"b"})
          {:keys [session]} (run-intent db session {:type :copy-block-reference})]
      (is (= "((b))" (get-in session [:ui :clipboard-text]))))))

(deftest ^{:fr/ids #{:fr.clipboard/block-reference}}
  copy-block-reference-multi-selection-noop
  (testing "ambiguous target (multiple selected, no editing) → no clipboard write"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "a" :type :block :props {:text "x"}}
                                 {:op :create-node :id "b" :type :block :props {:text "y"}}
                                 {:op :place :id "a" :under :doc :at :last}
                                 {:op :place :id "b" :under :doc :at :last}]))
          session (assoc-in (empty-session) [:selection :nodes] #{"a" "b"})
          {:keys [session]} (run-intent db session {:type :copy-block-reference})]
      (is (nil? (get-in session [:ui :clipboard-text]))
          "multi-select with no explicit target and no editing block is ambiguous — don't guess"))))
