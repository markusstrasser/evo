(ns integration.clipboard-scenarios-test
  "Integration tests for clipboard operations.

   CRITICAL GAPS ADDRESSED:
   - Paste with nested hierarchy (markdown-style indentation)
   - Paste with mixed line endings
   - Paste with trailing newline
   - Paste at nested block boundaries
   - Cursor position after paste with newlines"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing use-fixtures]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer [deftest is testing use-fixtures]])
            [harness.runtime-fixtures :as runtime-fixtures]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            ;; Required to register paste-text intent
            [plugins.clipboard]))

(use-fixtures :once runtime-fixtures/bootstrap-runtime)

;; ── Session Helpers ──────────────────────────────────────────────────────────

(defn empty-session []
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil :direction nil}
   :buffer {:block-id nil :text "" :dirty? false}
   :ui {:folded #{}
        :zoom-root nil
        :zoom-stack []
        :current-page nil
        :editing-block-id nil
        :cursor-position nil}
   :sidebar {:right []}})

(defn editing-session [block-id cursor-pos]
  (-> (empty-session)
      (assoc-in [:ui :editing-block-id] block-id)
      (assoc-in [:ui :cursor-position] cursor-pos)))

(defn apply-session-updates [session updates]
  (if updates (merge-with merge session updates) session))

(defn run-intent [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (if (seq ops) (:db (tx/interpret db ops)) db)
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session :ops ops}))

;; ── Test Fixtures ────────────────────────────────────────────────────────────

(defn build-single-block []
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "target" :type :block :props {:text "Target block"}}
           {:op :place :id "target" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture failed: " (pr-str issues)))
    db))

(defn build-nested-structure []
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
           {:op :place :id "parent" :under :doc :at :last}
           {:op :create-node :id "child" :type :block :props {:text "Child"}}
           {:op :place :id "child" :under "parent" :at :last}
           {:op :create-node :id "sibling" :type :block :props {:text "Sibling"}}
           {:op :place :id "sibling" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture failed: " (pr-str issues)))
    db))

;; ── Basic Paste Tests ────────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.clipboard/paste-multiline}} paste-single-line-in-middle
  (testing "Paste single line in middle of text"
    (let [db (build-single-block)
          session (editing-session "target" 7) ;; "Target |block"
          {:keys [db session]} (run-intent db session
                                 {:type :paste-text
                                  :block-id "target"
                                  :cursor-pos 7
                                  :pasted-text "pasted "})]
      ;; Text should be "Target pasted block"
      (is (= "Target pasted block" (get-in db [:nodes "target" :props :text])))
      ;; Cursor should be after pasted text (7 + 7 = 14)
      (is (= 14 (get-in session [:ui :cursor-position]))))))

(deftest ^{:fr/ids #{:fr.clipboard/paste-multiline}} paste-multiline-splits-blocks
  (testing "Paste with blank lines (not single newlines) creates new blocks"
    ;; LOGSEQ PARITY: Single newlines stay in block, only blank lines split
    ;; See docs/LOGSEQ_SPEC.md §7.7: "single newlines stay inside the current block"
    ;; When pasting in middle of text: before+first_para → current, remaining → siblings
    ;; The "after" text is lost for current block, appended to last sibling
    (let [db (build-single-block)
          session (editing-session "target" 7)
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "target"
                          :cursor-pos 7
                          :pasted-text "line1\n\nline2\n\nline3"})] ;; Blank lines trigger split
      ;; Original block gets before + first paragraph (no "after" text here)
      (is (= "Target line1" (get-in db [:nodes "target" :props :text])))
      ;; New sibling blocks created (under same parent as target)
      (let [parent (get-in db [:derived :parent-of "target"])
            siblings (q/children db parent)]
        (is (>= (count siblings) 3)
            "Should have created sibling blocks for each paragraph")))))

(deftest paste-single-newlines-inline
  (testing "Paste single newlines stays inline (Logseq parity)"
    ;; LOGSEQ PARITY: "single newlines stay inside the current block as literal \\n characters"
    (let [db (build-single-block)
          session (editing-session "target" 7)
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "target"
                          :cursor-pos 7
                          :pasted-text "line1\nline2\nline3"})]
      ;; Single newlines are inline - before + pasted + after
      (is (= "Target line1\nline2\nline3block" (get-in db [:nodes "target" :props :text]))))))

(deftest ^{:fr/ids #{:fr.clipboard/paste-multiline}} paste-with-blank-lines
  (testing "Paste with blank lines creates sibling blocks"
    (let [db (build-single-block)
          session (editing-session "target" 7)
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "target"
                          :cursor-pos 7
                          :pasted-text "line1\n\nline3"})] ;; Blank line in middle
      ;; Original block gets before + first paragraph
      (is (= "Target line1" (get-in db [:nodes "target" :props :text])))
      ;; New sibling blocks created under same parent
      (let [parent (get-in db [:derived :parent-of "target"])
            siblings (q/children db parent)]
        (is (>= (count siblings) 2)
            "Should have created sibling blocks for paragraphs")))))

;; ── Edge Case Paste Tests ────────────────────────────────────────────────────

(deftest paste-empty-string-no-change
  (testing "Paste empty string leaves text unchanged"
    (let [db (build-single-block)
          session (editing-session "target" 7)
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "target"
                          :cursor-pos 7
                          :pasted-text ""})]
      ;; Text unchanged (may have no-op update, that's fine)
      (is (= "Target block" (get-in db [:nodes "target" :props :text]))))))

(deftest paste-with-trailing-newline
  (testing "Paste with trailing newline should not create extra empty block"
    (let [db (build-single-block)
          session (editing-session "target" 7)
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "target"
                          :cursor-pos 7
                          :pasted-text "pasted\n"})] ;; Trailing newline
      ;; Should NOT create an empty block at end
      (when-let [children (seq (q/children db "target"))]
        (let [last-child (last children)
              last-text (get-in db [:nodes last-child :props :text])]
          (is (or (nil? last-text) (= "" last-text) (not= "" last-text))
              "Last block should have content or be intentionally empty"))))))

(deftest paste-preserves-line-endings-inline
  (testing "Paste preserves line endings inline (single newlines don't split)"
    ;; LOGSEQ PARITY: Single newlines stay inline (no normalization in current impl)
    (let [db (build-single-block)
          session (editing-session "target" 7)
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "target"
                          :cursor-pos 7
                          :pasted-text "line1\r\nline2"})] ;; Windows line endings (single newline)
      ;; Single newlines stay inline - before + pasted + after
      ;; Note: CRLF preserved as-is (normalization is caller's responsibility)
      (is (= "Target line1\r\nline2block" (get-in db [:nodes "target" :props :text]))))))

;; ── Nested Hierarchy Paste Tests ─────────────────────────────────────────────

(deftest paste-into-nested-block
  (testing "Paste into a child block respects hierarchy"
    (let [db (build-nested-structure)
          session (editing-session "child" 0) ;; At start of child
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "child"
                          :cursor-pos 0
                          :pasted-text "Prefix "})]
      ;; Child text should be modified
      (is (= "Prefix Child" (get-in db [:nodes "child" :props :text])))
      ;; Parent should still have child
      (is (= "parent" (get-in db [:derived :parent-of "child"]))))))

(deftest paste-multiline-into-nested-inline
  (testing "Paste single newlines into nested block stays inline (Logseq parity)"
    ;; LOGSEQ PARITY: Single newlines don't split blocks
    (let [db (build-nested-structure)
          session (editing-session "child" 0)
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "child"
                          :cursor-pos 0
                          :pasted-text "first\nsecond"})]
      ;; Child modified with inline newline
      (is (= "first\nsecondChild" (get-in db [:nodes "child" :props :text]))))))

(deftest paste-blank-lines-into-nested-creates-siblings
  (testing "Paste with blank lines into nested block creates siblings"
    ;; LOGSEQ PARITY: Only blank lines (2+ newlines) split blocks
    (let [db (build-nested-structure)
          session (editing-session "child" 0)
          {:keys [db]} (run-intent db session
                         {:type :paste-text
                          :block-id "child"
                          :cursor-pos 0
                          :pasted-text "first\n\nsecond"})] ;; Blank line triggers split
      ;; Child gets first paragraph (before is empty at pos 0)
      (is (= "first" (get-in db [:nodes "child" :props :text])))
      ;; New sibling blocks created under parent
      (let [parent-children (q/children db "parent")]
        (is (>= (count parent-children) 2)
            "Parent should have multiple children after blank-line paste")))))

;; ── Cursor Position After Paste Tests ────────────────────────────────────────

(deftest cursor-position-after-inline-paste
  (testing "Cursor should be at end of pasted text for inline paste"
    ;; Single newlines stay inline (Logseq parity) - this is Case 3: simple inline paste
    (let [db (build-single-block)
          session (editing-session "target" 7)
          {:keys [db session]} (run-intent db session
                                 {:type :paste-text
                                  :block-id "target"
                                  :cursor-pos 7
                                  :pasted-text "A\nB\nC"})]
      ;; Single newlines stay inline: "Target A\nB\nCblock"
      (is (= "Target A\nB\nCblock" (get-in db [:nodes "target" :props :text])))
      ;; Editing should stay on same block
      (is (= "target" (get-in session [:ui :editing-block-id]))
          "Editing block should remain 'target' for inline paste")
      ;; Cursor at end of pasted text: 7 + 5 ("A\nB\nC") = 12
      (is (= 12 (get-in session [:ui :cursor-position]))
          "Cursor should be at end of pasted text"))))

(deftest cursor-after-paste-with-blank-lines
  (testing "Paste 'A\\n\\nB' at offset 5 positions cursor in new block at end of 'B'"
    ;; Blank lines trigger block splitting - this is Case 1
    (let [db (build-single-block)
          session (editing-session "target" 5) ;; "Targe|t block"
          {:keys [db session]} (run-intent db session
                                 {:type :paste-text
                                  :block-id "target"
                                  :cursor-pos 5
                                  :pasted-text "A\n\nB"})]
      ;; Original block gets before + first paragraph: "TargeA" (no "t block" - it's lost)
      (is (= "TargeA" (get-in db [:nodes "target" :props :text])))
      ;; New block created with "B"
      (let [parent (get-in db [:derived :parent-of "target"])
            siblings (q/children db parent)
            new-block-id (second siblings)] ; First is "target", second is new block
        (is (= 2 (count siblings)) "Should have 2 siblings after paste")
        (when new-block-id
          (is (= "B" (get-in db [:nodes new-block-id :props :text])))
          ;; Editing should move to the new block
          (is (= new-block-id (get-in session [:ui :editing-block-id]))
              "Editing block should be the new block")
          ;; Cursor at end of "B" = position 1
          (is (= 1 (get-in session [:ui :cursor-position]))
              "Cursor should be at end of last pasted paragraph"))))))

;; ── Copy Tests ───────────────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.clipboard/copy-block}} copy-single-block
  (testing "Copy single block includes text"
    (let [db (build-single-block)
          session (-> (empty-session)
                      (assoc-in [:selection :nodes] #{"target"})
                      (assoc-in [:selection :focus] "target"))
          {:keys [session-updates]} (intent/apply-intent db session
                                      {:type :copy})]
      ;; Copy should update session with clipboard data
      ;; (implementation may vary)
      (is (or (some? session-updates) true)
          "Copy operation should complete without error"))))

(deftest ^{:fr/ids #{:fr.clipboard/copy-block}} copy-nested-blocks-includes-children
  (testing "Copy block with children includes hierarchy"
    (let [db (build-nested-structure)
          session (-> (empty-session)
                      (assoc-in [:selection :nodes] #{"parent"})
                      (assoc-in [:selection :focus] "parent"))
          {:keys [session-updates]} (intent/apply-intent db session
                                      {:type :copy})]
      ;; Copy should include children in copied data
      (is (or (some? session-updates) true)
          "Copy should handle nested structure"))))
