(ns integration.editing-scenarios-test
  "Integration tests for editing scenarios that bridge intent → ops → session.

   These tests verify the full pipeline including session state updates,
   which unit tests often miss. They catch bugs where DB operations succeed
   but cursor/selection state is incorrect.

   KEY EDGE CASES TESTED:
   - Cursor position after merge at parent boundary
   - Enter split preserving cursor position
   - Backspace merge re-parenting children
   - Multi-block operations with intermediate state"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.api :as api]
            [kernel.query :as q]
            ;; Required to register merge-with-prev intent
            [plugins.editing]))

;; ── Session Helpers ──────────────────────────────────────────────────────────

(defn empty-session
  "Create an empty session for testing."
  []
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil :direction nil}
   :buffer {:block-id nil :text "" :dirty? false}
   :ui {:folded #{}
        :zoom-root nil
        :zoom-stack []
        :current-page nil
        :editing-block-id nil
        :cursor-position nil
        :cursor-memory nil}
   :sidebar {:right []}})

(defn editing-session
  "Create session with block in edit mode."
  [block-id cursor-pos]
  (-> (empty-session)
      (assoc-in [:ui :editing-block-id] block-id)
      (assoc-in [:ui :cursor-position] cursor-pos)))

(defn apply-session-updates
  "Apply session-updates returned by a handler to a session."
  [session session-updates]
  (if session-updates
    (merge-with merge session session-updates)
    session))

(defn run-intent
  "Run intent through full pipeline and return {:db ... :session ...}."
  [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (if (seq ops) (:db (tx/interpret db ops)) db)
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session :ops ops}))

;; ── Complex Fixtures ─────────────────────────────────────────────────────────

(defn build-3-level-hierarchy
  "Creates a 3-level hierarchy for boundary tests:

   doc
   └── parent (text: 'Parent')
       ├── child-1 (text: 'First child')
       │   └── grandchild (text: 'Grandchild')
       └── child-2 (text: 'Second child')
   └── sibling (text: 'Sibling')"
  []
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
           {:op :place :id "parent" :under :doc :at :last}
           {:op :create-node :id "child-1" :type :block :props {:text "First child"}}
           {:op :place :id "child-1" :under "parent" :at :last}
           {:op :create-node :id "grandchild" :type :block :props {:text "Grandchild"}}
           {:op :place :id "grandchild" :under "child-1" :at :last}
           {:op :create-node :id "child-2" :type :block :props {:text "Second child"}}
           {:op :place :id "child-2" :under "parent" :at :last}
           {:op :create-node :id "sibling" :type :block :props {:text "Sibling"}}
           {:op :place :id "sibling" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture setup failed: " (pr-str issues)))
    db))

(defn build-simple-doc
  "Creates simple flat structure: doc → [a, b, c]"
  []
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "a" :type :block :props {:text "First block"}}
           {:op :place :id "a" :under :doc :at :last}
           {:op :create-node :id "b" :type :block :props {:text "Second block"}}
           {:op :place :id "b" :under :doc :at :last}
           {:op :create-node :id "c" :type :block :props {:text "Third block"}}
           {:op :place :id "c" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture setup failed: " (pr-str issues)))
    db))

;; ── Enter/Split Tests ────────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.edit/smart-split}} enter-split-middle-preserves-cursor
  (testing "Enter in middle of text splits and positions cursor at start of new block"
    (let [db (build-simple-doc)
          session (editing-session "a" 6) ;; "First |block"
          {:keys [db session]} (run-intent db session
                                 {:type :context-aware-enter
                                  :block-id "a"
                                  :cursor-pos 6})]
      ;; Original block should have "First "
      (is (= "First " (get-in db [:nodes "a" :props :text])))
      ;; New block should have "block"
      (let [new-id (first (q/children db "a"))]
        (when new-id
          (is (= "block" (get-in db [:nodes new-id :props :text])))))
      ;; Cursor should be at position 0 of new block
      (is (= 0 (get-in session [:ui :cursor-position]))))))

(deftest ^{:fr/ids #{:fr.edit/smart-split}} enter-at-start-creates-above
  (testing "Enter at start of block creates new block above"
    (let [db (build-simple-doc)
          session (editing-session "b" 0) ;; Cursor at start
          {:keys [db session]} (run-intent db session
                                 {:type :context-aware-enter
                                  :block-id "b"
                                  :cursor-pos 0})]
      ;; Original block text unchanged
      (is (= "Second block" (get-in db [:nodes "b" :props :text])))
      ;; New empty block should be above "b"
      (let [siblings (q/children db :doc)]
        (is (> (count siblings) 3) "Should have created new block")))))

;; ── Backspace/Merge Tests ────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.edit/backspace-merge}} backspace-merge-cursor-at-join-point
  (testing "Backspace merge places cursor at original text length of target block"
    (let [db (build-simple-doc)
          session (editing-session "b" 0) ;; Cursor at start of "Second block"
          {:keys [db session]} (run-intent db session
                                 {:type :merge-with-prev
                                  :block-id "b"})]
      ;; Blocks should be merged
      (is (= "First blockSecond block" (get-in db [:nodes "a" :props :text])))
      ;; Cursor should be at join point (length of "First block" = 11)
      (is (= 11 (get-in session [:ui :cursor-position]))))))

(deftest ^{:fr/ids #{:fr.edit/backspace-merge}} backspace-merge-with-children-reparents
  (testing "Backspace merge reparents children to merged-into block"
    (let [db (build-3-level-hierarchy)
          session (editing-session "child-2" 0)
          {:keys [db session]} (run-intent db session
                                 {:type :merge-with-prev
                                  :block-id "child-2"})]
      ;; child-2 should be merged into child-1
      (is (= "First childSecond child" (get-in db [:nodes "child-1" :props :text])))
      ;; grandchild should still be under child-1
      (is (= "child-1" (get-in db [:derived :parent-of "grandchild"]))))))

(deftest ^{:fr/ids #{:fr.edit/backspace-merge}} backspace-merge-child-into-parent
  (testing "Backspace at first child merges into parent at end"
    (let [db (build-3-level-hierarchy)
          session (editing-session "child-1" 0)
          {:keys [db session]} (run-intent db session
                                 {:type :merge-with-prev
                                  :block-id "child-1"})]
      ;; child-1 should be merged into parent
      (is (= "ParentFirst child" (get-in db [:nodes "parent" :props :text])))
      ;; Cursor at join point (length of "Parent" = 6)
      (is (= 6 (get-in session [:ui :cursor-position])))
      ;; grandchild should be reparented to parent
      (is (= "parent" (get-in db [:derived :parent-of "grandchild"]))))))

;; ── Delete Forward Tests ─────────────────────────────────────────────────────

(deftest delete-forward-merge-cursor-stays
  (testing "Delete forward merge keeps cursor at same position"
    (let [db (build-simple-doc)
          session (editing-session "a" 11) ;; At end of "First block"
          {:keys [db session]} (run-intent db session
                                 {:type :delete-forward
                                  :block-id "a"
                                  :cursor-pos 11
                                  :has-selection? false})]
      ;; Blocks should be merged
      (is (= "First blockSecond block" (get-in db [:nodes "a" :props :text])))
      ;; Cursor stays at position 11
      (is (= 11 (get-in session [:ui :cursor-position]))))))

;; ── Indent/Outdent with Cursor Tests ─────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.struct/indent-outdent}} indent-preserves-editing-state
  (testing "Indent while editing preserves cursor position"
    (let [db (build-simple-doc)
          session (editing-session "b" 5) ;; "Secon|d block"
          {:keys [db session]} (run-intent db session
                                 {:type :indent-selected})]
      ;; Block "b" should now be under "a"
      (is (= "a" (get-in db [:derived :parent-of "b"])))
      ;; Cursor position should be preserved
      (is (= 5 (get-in session [:ui :cursor-position])))
      ;; Should still be editing the same block
      (is (= "b" (get-in session [:ui :editing-block-id]))))))

;; ── Multi-Step Script Operations ─────────────────────────────────────────────

(deftest delete-block-selects-previous
  (testing "Deleting block should select previous block"
    (let [db (build-simple-doc)
          ;; Start with "b" selected (not editing)
          session (-> (empty-session)
                      (assoc-in [:selection :nodes] #{"b"})
                      (assoc-in [:selection :focus] "b"))
          {:keys [db session]} (run-intent db session {:type :delete-selected})]
      ;; Block "b" should be deleted
      (is (= :trash (get-in db [:derived :parent-of "b"])))
      ;; Focus should move to previous block "a"
      (is (= "a" (get-in session [:selection :focus]))))))

;; ── State Machine Boundary Tests ─────────────────────────────────────────────

(deftest editing-intent-blocked-when-not-editing
  (testing "Editing intents should be blocked when not in edit mode"
    (let [db (build-simple-doc)
          session (empty-session) ;; Not editing
          result (api/dispatch db session {:type :context-aware-enter
                                           :block-id "a"
                                           :cursor-pos 5})]
      ;; Should be a no-op (state machine blocks it)
      (is (= db (:db result)) "DB should be unchanged"))))

(deftest selection-intent-works-from-idle
  (testing "Selection intents should work from idle state"
    (let [db (build-simple-doc)
          session (empty-session)
          {:keys [session-updates]} (intent/apply-intent db session
                                      {:type :selection :mode :replace :ids "a"})]
      ;; Should have selection updates
      (is (some? session-updates))
      (is (= #{"a"} (get-in session-updates [:selection :nodes]))))))
