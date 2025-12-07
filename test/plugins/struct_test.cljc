(ns plugins.struct-test
  "Integration tests for structural editing intent compiler.
   Tests verify end-to-end behavior: intent → ops → final DB state."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as D]
            [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [plugins.struct :as S]))

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

(defn session-with-editing
  "Create a session with editing-block-id set."
  [block-id]
  (assoc-in (empty-session) [:ui :editing-block-id] block-id))

(defn session-with-selection
  "Create a session with selection set."
  [node-ids]
  (-> (empty-session)
      (assoc-in [:selection :nodes] (set node-ids))
      (assoc-in [:selection :focus] (first node-ids))))

(defn session-with-zoom
  "Create a session with zoom-root set."
  [zoom-root]
  (assoc-in (empty-session) [:ui :zoom-root] zoom-root))

(defn session-with-zoom-and-editing
  "Create a session with zoom-root and editing-block-id set."
  [zoom-root editing-id]
  (-> (empty-session)
      (assoc-in [:ui :zoom-root] zoom-root)
      (assoc-in [:ui :editing-block-id] editing-id)))

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn build-doc
  "Creates a test DB with structure: doc1 -> [a, b]"
  []
  (let [DB0 (D/empty-db)
        {:keys [db issues]} (tx/interpret DB0
                                          [{:op :create-node :id "doc1" :type :doc :props {}}
                                           {:op :place :id "doc1" :under :doc :at :last}
                                           {:op :create-node :id "a" :type :p :props {}}
                                           {:op :place :id "a" :under "doc1" :at :last}
                                           {:op :create-node :id "b" :type :p :props {}}
                                           {:op :place :id "b" :under "doc1" :at :last}])]
    (assert (empty? issues) (str "Fixture setup failed: " (pr-str issues)))
    db))

;; ── Integration tests ─────────────────────────────────────────────────────────

(deftest delete-archives-to-trash
  (testing "Delete intent compiles to :place under :trash"
    (let [db (build-doc)
          {:keys [ops]} (intent/apply-intent db nil {:type :delete :id "a"})
          res (tx/interpret db ops)]
      (is (empty? (:issues res))
          "Delete operation should not generate issues")
      (is (= ["b"] (get-in res [:db :children-by-parent "doc1"]))
          "Node 'a' should be removed from doc1")
      (is (= ["a"] (get-in res [:db :children-by-parent :trash]))
          "Node 'a' should appear in trash"))))

(deftest 
  indent-then-outdent-is-alpha-equivalent
  (testing "Indent followed by outdent restores original structure"
    (let [db (build-doc)
          ;; Indent b under a: doc1 -> [a -> [b]]
          {:keys [ops]} (intent/apply-intent db nil {:type :indent :id "b"})
          r1 (tx/interpret db ops)
          db1 (:db r1)
          ;; Outdent b back to doc1: doc1 -> [a, b]
          {:keys [ops]} (intent/apply-intent db1 nil {:type :outdent :id "b"})
          r2 (tx/interpret db1 ops)]
      (is (empty? (:issues r1))
          "Indent operation should not generate issues")
      (is (empty? (:issues r2))
          "Outdent operation should not generate issues")
      (is (= ["b"] (get-in r1 [:db :children-by-parent "a"]))
          "After indent, b should be under a")
      (is (= ["a" "b"] (get-in r2 [:db :children-by-parent "doc1"]))
          "After outdent, structure should be restored"))))

(deftest 
  logical-outdenting-right-siblings-stay
  (testing "LOGSEQ PARITY: Logical outdenting leaves right siblings under parent"
    ;; Build structure:
    ;; - doc1 (document node under :doc root)
    ;;   - parent
    ;;     - child-a
    ;;     - child-b  <- will outdent this
    ;;     - child-c
    ;;     - child-d
    (let [db (-> (D/empty-db)
                 (tx/interpret
                  [{:op :create-node :id "doc1" :type :doc :props {}}
                   {:op :place :id "doc1" :under :doc :at :last}
                   {:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                   {:op :place :id "parent" :under "doc1" :at :last}
                   {:op :create-node :id "child-a" :type :block :props {:text "A"}}
                   {:op :place :id "child-a" :under "parent" :at :last}
                   {:op :create-node :id "child-b" :type :block :props {:text "B"}}
                   {:op :place :id "child-b" :under "parent" :at :last}
                   {:op :create-node :id "child-c" :type :block :props {:text "C"}}
                   {:op :place :id "child-c" :under "parent" :at :last}
                   {:op :create-node :id "child-d" :type :block :props {:text "D"}}
                   {:op :place :id "child-d" :under "parent" :at :last}])
                 :db)
          ;; Verify initial structure
          _ (is (= ["child-a" "child-b" "child-c" "child-d"]
                   (get-in db [:children-by-parent "parent"])))
          ;; Outdent child-b
          {:keys [ops]} (intent/apply-intent db nil {:type :outdent :id "child-b"})
          {:keys [db issues]} (tx/interpret db ops)]
      (is (empty? issues))
      ;; child-b should now be sibling of parent (under doc1)
      (is (= "doc1" (get-in db [:derived :parent-of "child-b"]))
          "child-b should be under doc1 after outdent")
      ;; child-b should be positioned RIGHT AFTER parent
      (is (= ["parent" "child-b"] (get-in db [:children-by-parent "doc1"]))
          "child-b should appear after parent in doc1 children")
      ;; CRITICAL: Right siblings (C, D) should STAY under parent
      (is (= ["child-a" "child-c" "child-d"]
             (get-in db [:children-by-parent "parent"]))
          "Right siblings C and D should stay under parent (no kidnapping)"))))

(deftest safety-noop-when-not-applicable
  (testing "Indent on first child returns empty ops (no-op safety)"
    (let [db (build-doc)
          first-id (first (get-in db [:children-by-parent "doc1"]))
          {:keys [ops]} (intent/apply-intent db nil {:type :indent :id first-id})]
      (is (= [] ops)
          "Cannot indent first child (no previous sibling)")))

  (testing "Outdent on direct child of root returns empty ops"
    (let [db (build-doc)
          {:keys [ops]} (intent/apply-intent db nil {:type :outdent :id "doc1"})]
      (is (= [] ops)
          "Cannot outdent top-level document (no grandparent)"))))

(deftest compile-multiple-intents
  (testing "Multiple intents compile into sequential ops"
    (let [db (build-doc)
          {ops1 :ops} (intent/apply-intent db nil {:type :indent :id "b"})
          {ops2 :ops} (intent/apply-intent db nil {:type :delete :id "a"})
          all-ops (concat ops1 ops2)]
      (is (= 2 (count all-ops))
          "Two intents should produce two ops")
      (is (every? #(contains? % :op) all-ops)
          "All compiled outputs should be valid ops"))))

(deftest unknown-intent-type-is-noop
  (testing "Unknown intent type returns empty ops"
    (let [db (build-doc)
          result (intent/apply-intent db nil {:type :unknown-intent :id "a"})]
      (is (= [] (:ops result))
          "Unknown intent should have no ops")
      (is (= db (:db result))
          "DB should be unchanged"))))

;; ── Move climb semantics tests ────────────────────────────────────────────────

(defn build-nested-doc
  "Creates a test DB with nested structure:
   doc
     └─ parent
          ├─ child-a
          ├─ child-b
          └─ child-c
     └─ uncle"
  []
  (let [DB0 (D/empty-db)
        {:keys [db issues]} (tx/interpret DB0
                                          [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                           {:op :place :id "parent" :under :doc :at :last}
                                           {:op :create-node :id "uncle" :type :block :props {:text "Uncle"}}
                                           {:op :place :id "uncle" :under :doc :at :last}
                                           {:op :create-node :id "child-a" :type :block :props {:text "Child A"}}
                                           {:op :place :id "child-a" :under "parent" :at :last}
                                           {:op :create-node :id "child-b" :type :block :props {:text "Child B"}}
                                           {:op :place :id "child-b" :under "parent" :at :last}
                                           {:op :create-node :id "child-c" :type :block :props {:text "Child C"}}
                                           {:op :place :id "child-c" :under "parent" :at :last}])]
    (assert (empty? issues) (str "Fixture setup failed: " (pr-str issues)))
    db))

(deftest 
  move-up-climb-out-semantics
  (testing "Move up on first child climbs out to parent's level"
    (let [db (build-nested-doc)
          session (session-with-editing "child-a")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})
          result (tx/interpret db ops)]
      (is (empty? (:issues result))
          "Climb operation should not generate issues")
      ;; child-a should now be a sibling of parent, positioned before parent
      (is (= ["child-a" "parent" "uncle"] (get-in result [:db :children-by-parent :doc]))
          "child-a should climb to doc level, before parent")
      (is (= ["child-b" "child-c"] (get-in result [:db :children-by-parent "parent"]))
          "child-b and child-c should remain under parent"))))

(deftest move-up-normal-case
  (testing "Move up on non-first child moves before previous sibling"
    (let [db (build-nested-doc)
          session (session-with-editing "child-b")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})
          result (tx/interpret db ops)]
      (is (empty? (:issues result))
          "Move up should not generate issues")
      ;; child-b should now be before child-a
      (is (= ["child-b" "child-a" "child-c"] (get-in result [:db :children-by-parent "parent"]))
          "child-b should move before child-a"))))

(deftest 
  move-down-descend-semantics
  (testing "Move down on last child descends into parent's next sibling"
    (let [db (build-nested-doc)
          session (session-with-editing "child-c")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-down})
          result (tx/interpret db ops)]
      (is (empty? (:issues result))
          "Descend operation should not generate issues")
      ;; child-c should descend into uncle as first child
      (is (= ["child-a" "child-b"] (get-in result [:db :children-by-parent "parent"]))
          "child-c should be removed from parent")
      (is (= ["child-c"] (get-in result [:db :children-by-parent "uncle"]))
          "child-c should descend into uncle as first child"))))

(deftest move-down-normal-case
  (testing "Move down on non-last child moves after next sibling"
    (let [db (build-nested-doc)
          session (session-with-editing "child-a")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-down})
          result (tx/interpret db ops)]
      (is (empty? (:issues result))
          "Move down should not generate issues")
      ;; child-a should now be after child-b
      (is (= ["child-b" "child-a" "child-c"] (get-in result [:db :children-by-parent "parent"]))
          "child-a should move after child-b"))))

(deftest move-up-climb-multi-select
  (testing "Move up with multi-select climbs all selected nodes together"
    (let [db (build-nested-doc)
          session (session-with-selection ["child-a" "child-b"])
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})
          result (tx/interpret db ops)]
      (is (empty? (:issues result))
          "Multi-select climb should not generate issues")
      ;; Both nodes should climb together, preserving order
      (is (= ["child-a" "child-b" "parent" "uncle"] (get-in result [:db :children-by-parent :doc]))
          "child-a and child-b should climb together to doc level, before parent")
      (is (= ["child-c"] (get-in result [:db :children-by-parent "parent"]))
          "Only child-c should remain under parent"))))

(deftest move-down-descend-multi-select
  (testing "Move down with multi-select descends all selected nodes together"
    (let [db (build-nested-doc)
          session (session-with-selection ["child-b" "child-c"])
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-down})
          result (tx/interpret db ops)]
      (is (empty? (:issues result))
          "Multi-select descend should not generate issues")
      ;; Both nodes should descend together into uncle
      (is (= ["child-a"] (get-in result [:db :children-by-parent "parent"]))
          "Only child-a should remain under parent")
      (is (= ["child-b" "child-c"] (get-in result [:db :children-by-parent "uncle"]))
          "child-b and child-c should descend into uncle, preserving order"))))

(deftest move-up-at-top-level-noop
  (testing "Move up on first child at top level is no-op"
    (let [db (build-nested-doc)
          session (session-with-editing "parent")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})
          result (tx/interpret db ops)]
      (is (= [] ops)
          "Cannot climb out of root-level parent")
      (is (= db (:db result))
          "DB should remain unchanged"))))

(deftest move-down-no-uncle-noop
  (testing "Move down on last child with no uncle is no-op"
    (let [db (build-nested-doc)
          ;; Add a child to uncle so we can test moving its last child
          {:keys [db]} (tx/interpret db
                                     [{:op :create-node :id "nephew" :type :block :props {:text "Nephew"}}
                                      {:op :place :id "nephew" :under "uncle" :at :last}])
          session (session-with-editing "nephew")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-down})
          result (tx/interpret db ops)]
      (is (= [] ops)
          "Cannot descend when parent has no next sibling")
      (is (= db (:db result))
          "DB should remain unchanged"))))

(deftest move-up-parent-child-selection-filters-child
  (testing "When parent and child both selected, only parent moves (Logseq parity)"
    (let [db (build-nested-doc)
          ;; Select both parent and child-a
          session (session-with-selection ["parent" "child-a"])
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})]
      ;; Should be no-op because after filtering, only "parent" remains
      ;; and "parent" is already first child of :doc
      (is (= [] ops)
          "Should be no-op: parent is already at top level, child filtered out"))))

(deftest move-up-non-consecutive-selection-noop
  (testing "Non-consecutive selection is rejected (Logseq parity)"
    (let [db (build-nested-doc)
          ;; Select child-a and child-c but NOT child-b (non-consecutive)
          session (session-with-selection ["child-a" "child-c"])
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})]
      (is (= [] ops)
          "Non-consecutive selections should be rejected"))))

(deftest move-down-non-consecutive-selection-noop
  (testing "Non-consecutive selection is rejected for move-down (Logseq parity)"
    (let [db (build-nested-doc)
          ;; Select child-a and child-c but NOT child-b (non-consecutive)
          session (session-with-selection ["child-a" "child-c"])
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-down})]
      (is (= [] ops)
          "Non-consecutive selections should be rejected"))))

(defn build-page-doc
  "Creates a test DB with page structure (matches real app):
   doc
     └─ page (type: :page)
          ├─ block-a
          ├─ block-b
          └─ block-c"
  []
  (let [DB0 (D/empty-db)
        {:keys [db issues]} (tx/interpret DB0
                                          [{:op :create-node :id "page" :type :page :props {:title "Test Page"}}
                                           {:op :place :id "page" :under :doc :at :last}
                                           {:op :create-node :id "block-a" :type :block :props {:text "Block A"}}
                                           {:op :place :id "block-a" :under "page" :at :last}
                                           {:op :create-node :id "block-b" :type :block :props {:text "Block B"}}
                                           {:op :place :id "block-b" :under "page" :at :last}
                                           {:op :create-node :id "block-c" :type :block :props {:text "Block C"}}
                                           {:op :place :id "block-c" :under "page" :at :last}])]
    (assert (empty? issues) (str "Fixture setup failed: " (pr-str issues)))
    db))

(deftest move-up-page-boundary-noop
  (testing "Blocks cannot climb out of their page (Logseq parity)"
    (let [db (build-page-doc)
          ;; block-a is first child of page, should NOT climb to :doc level
          session (session-with-editing "block-a")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})]
      (is (= [] ops)
          "Should be no-op: cannot escape page boundary")
      ;; Verify block-a is still under page
      (is (= "page" (get-in db [:derived :parent-of "block-a"]))
          "block-a should still be under page"))))

;; ── Zoom Boundary Tests (FR-Scope-02) ────────────────────────────────────────

(defn build-zoomed-doc
  "Creates a test DB for zoom tests:
   doc -> [page]
   page -> [parent]
   parent -> [child-a, child-b]

   Note: Zoom root is set via session, not in DB.
   Use session-with-zoom or session-with-zoom-and-editing in tests."
  []
  (let [DB0 (D/empty-db)
        {:keys [db issues]} (tx/interpret DB0
                                          [{:op :create-node :id "page" :type :page :props {:title "Page"}}
                                           {:op :place :id "page" :under :doc :at :last}
                                           {:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                           {:op :place :id "parent" :under "page" :at :last}
                                           {:op :create-node :id "child-a" :type :block :props {:text "Child A"}}
                                           {:op :place :id "child-a" :under "parent" :at :last}
                                           {:op :create-node :id "child-b" :type :block :props {:text "Child B"}}
                                           {:op :place :id "child-b" :under "parent" :at :last}])]
    (assert (empty? issues) (str "Fixture setup failed: " (pr-str issues)))
    db))

(deftest 
  outdent-blocked-by-zoom-boundary
  (testing "FR-Scope-02: Outdent is no-op when grandparent is outside zoom root"
    (let [db (build-zoomed-doc)
          session (session-with-zoom "parent")
          ;; Try to outdent child-a (would move it under "page", outside zoom root "parent")
          {:keys [ops]} (intent/apply-intent db session {:type :outdent :id "child-a"})
          result (tx/interpret db ops)]
      (is (= [] ops)
          "Outdent should be blocked when grandparent is outside zoom root")
      (is (= ["child-a" "child-b"] (get-in result [:db :children-by-parent "parent"]))
          "Structure should remain unchanged"))))

(deftest move-up-climb-blocked-by-zoom-boundary
  (testing "FR-Scope-02: Move up (climb) is no-op when grandparent is outside zoom root"
    (let [db (build-zoomed-doc)
          ;; Set child-a as editing block (first child, will try to climb out)
          session (session-with-zoom-and-editing "parent" "child-a")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})
          result (tx/interpret db ops)]
      (is (= [] ops)
          "Move up (climb) should be blocked when grandparent is outside zoom root")
      (is (= ["child-a" "child-b"] (get-in result [:db :children-by-parent "parent"]))
          "Structure should remain unchanged"))))

(deftest move-down-descend-blocked-by-zoom-boundary
  (testing "FR-Scope-02: Move down (descend) is no-op when target is outside zoom root"
    (let [db (build-zoomed-doc)
          ;; Add a sibling to parent (outside zoom root)
          {:keys [db]} (tx/interpret db
                                     [{:op :create-node :id "uncle" :type :block :props {:text "Uncle"}}
                                      {:op :place :id "uncle" :under "page" :at {:after "parent"}}])
          ;; Set child-b as editing block (last child, will try to descend into uncle)
          session (session-with-zoom-and-editing "parent" "child-b")
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-down})
          result (tx/interpret db ops)]
      (is (= [] ops)
          "Move down (descend) should be blocked when target is outside zoom root")
      (is (= ["child-a" "child-b"] (get-in result [:db :children-by-parent "parent"]))
          "Structure should remain unchanged"))))

(deftest operations-allowed-within-zoom-scope
  (testing "FR-Scope-01: Operations within zoom scope work normally"
    (let [db (build-zoomed-doc)
          ;; Outdent child-c from under child-a (stays within zoom root "parent")
          ;; This should work because grandparent is "parent" (the zoom root)
          {:keys [db]} (tx/interpret db
                                     [{:op :create-node :id "child-c" :type :block :props {:text "Child C"}}
                                      {:op :place :id "child-c" :under "child-a" :at :last}])
          session (session-with-zoom "parent")
          {:keys [ops]} (intent/apply-intent db session {:type :outdent :id "child-c"})
          result (tx/interpret db ops)]
      (is (not (empty? ops))
          "Outdent should work when staying within zoom scope")
      (is (= ["child-a" "child-c" "child-b"] (get-in result [:db :children-by-parent "parent"]))
          "child-c should outdent to become sibling of child-a"))))

(deftest create-block-in-page-for-empty-pages
  (testing "create-block-in-page creates block and enters edit mode"
    (let [;; Start with empty page
          db (-> (D/empty-db)
                 (tx/interpret [{:op :create-node :id "page1" :type :page :props {:title "Empty Page"}}])
                 :db)
          session (empty-session)
          {:keys [ops session-updates]} (intent/apply-intent db session
                                                             {:type :create-block-in-page
                                                              :page-id "page1"
                                                              :block-id "new-block"})
          result (tx/interpret db ops)]
      (is (= 2 (count ops)) "Should emit create-node and place ops")
      (is (= ["new-block"] (get-in result [:db :children-by-parent "page1"]))
          "New block should be placed under page")
      (is (= "new-block" (get-in session-updates [:ui :editing-block-id]))
          "Should enter edit mode on new block")
      (is (= 0 (get-in session-updates [:ui :cursor-position]))
          "Cursor should be at position 0"))))

(deftest multi-select-repeated-moves-stay-consecutive
  (testing "Blocks stay consecutive after repeated move operations"
    (let [db (build-nested-doc)
          ;; Select child-a and child-b (consecutive)
          initial-selection ["child-a" "child-b"]
          session1 (session-with-selection initial-selection)

          ;; Move down once
          {:keys [ops]} (intent/apply-intent db session1 {:type :move-selected-down})
          result1 (tx/interpret db ops)
          db1 (:db result1)]

      (is (empty? (:issues result1))
          "First move should not generate issues")
      (is (= ["child-c" "child-a" "child-b"] (get-in db1 [:children-by-parent "parent"]))
          "After first move down, order should be [child-c, child-a, child-b]")

      ;; Move down again with same selection (now in new positions)
      (let [session2 (session-with-selection initial-selection)
            {:keys [ops]} (intent/apply-intent db1 session2 {:type :move-selected-down})
            result2 (tx/interpret db1 ops)
            db2 (:db result2)]

        (is (empty? (:issues result2))
            "Second move should not generate issues")
        ;; child-a and child-b should descend together into uncle
        (is (= ["child-c"] (get-in db2 [:children-by-parent "parent"]))
            "Only child-c should remain under parent after descend")
        (is (= ["child-a" "child-b"] (get-in db2 [:children-by-parent "uncle"]))
            "child-a and child-b should descend together into uncle, staying consecutive")))))

(deftest multi-select-repeated-moves-up-stay-consecutive
  (testing "Blocks stay consecutive after repeated move-up operations"
    (let [db (build-nested-doc)
          ;; Select child-b and child-c (consecutive)
          initial-selection ["child-b" "child-c"]
          session1 (session-with-selection initial-selection)

          ;; Move up once
          {:keys [ops]} (intent/apply-intent db session1 {:type :move-selected-up})
          result1 (tx/interpret db ops)
          db1 (:db result1)]

      (is (empty? (:issues result1))
          "First move should not generate issues")
      (is (= ["child-b" "child-c" "child-a"] (get-in db1 [:children-by-parent "parent"]))
          "After first move up, order should be [child-b, child-c, child-a]")

      ;; Move up again with same selection (now in new positions)
      (let [session2 (session-with-selection initial-selection)
            {:keys [ops]} (intent/apply-intent db1 session2 {:type :move-selected-up})
            result2 (tx/interpret db1 ops)
            db2 (:db result2)]

        (is (empty? (:issues result2))
            "Second move should not generate issues")
        ;; child-b and child-c should climb together to doc level
        (is (= ["child-b" "child-c" "parent" "uncle"] (get-in db2 [:children-by-parent :doc]))
            "child-b and child-c should climb together to doc level, staying consecutive")
        (is (= ["child-a"] (get-in db2 [:children-by-parent "parent"]))
            "Only child-a should remain under parent")))))
