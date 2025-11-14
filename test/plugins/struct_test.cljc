(ns plugins.struct-test
  "Integration tests for structural editing intent compiler.
   Tests verify end-to-end behavior: intent → ops → final DB state."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as D]
            [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [kernel.constants :as const]
            [plugins.struct :as S]))

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
          {:keys [ops]} (intent/apply-intent db {:type :delete :id "a"})
          res (tx/interpret db ops)]
      (is (empty? (:issues res))
          "Delete operation should not generate issues")
      (is (= ["b"] (get-in res [:db :children-by-parent "doc1"]))
          "Node 'a' should be removed from doc1")
      (is (= ["a"] (get-in res [:db :children-by-parent :trash]))
          "Node 'a' should appear in trash"))))

(deftest indent-then-outdent-is-alpha-equivalent
  (testing "Indent followed by outdent restores original structure"
    (let [db (build-doc)
          ;; Indent b under a: doc1 -> [a -> [b]]
          {:keys [ops]} (intent/apply-intent db {:type :indent :id "b"})
          r1 (tx/interpret db ops)
          db1 (:db r1)
          ;; Outdent b back to doc1: doc1 -> [a, b]
          {:keys [ops]} (intent/apply-intent db1 {:type :outdent :id "b"})
          r2 (tx/interpret db1 ops)]
      (is (empty? (:issues r1))
          "Indent operation should not generate issues")
      (is (empty? (:issues r2))
          "Outdent operation should not generate issues")
      (is (= ["b"] (get-in r1 [:db :children-by-parent "a"]))
          "After indent, b should be under a")
      (is (= ["a" "b"] (get-in r2 [:db :children-by-parent "doc1"]))
          "After outdent, structure should be restored"))))

(deftest safety-noop-when-not-applicable
  (testing "Indent on first child returns empty ops (no-op safety)"
    (let [db (build-doc)
          first-id (first (get-in db [:children-by-parent "doc1"]))
          {:keys [ops]} (intent/apply-intent db {:type :indent :id first-id})]
      (is (= [] ops)
          "Cannot indent first child (no previous sibling)")))

  (testing "Outdent on direct child of root returns empty ops"
    (let [db (build-doc)
          {:keys [ops]} (intent/apply-intent db {:type :outdent :id "doc1"})]
      (is (= [] ops)
          "Cannot outdent top-level document (no grandparent)"))))

(deftest compile-multiple-intents
  (testing "Multiple intents compile into sequential ops"
    (let [db (build-doc)
          {ops1 :ops} (intent/apply-intent db {:type :indent :id "b"})
          {ops2 :ops} (intent/apply-intent db {:type :delete :id "a"})
          all-ops (concat ops1 ops2)]
      (is (= 2 (count all-ops))
          "Two intents should produce two ops")
      (is (every? #(contains? % :op) all-ops)
          "All compiled outputs should be valid ops"))))

(deftest unknown-intent-type-is-noop
  (testing "Unknown intent type returns empty ops"
    (let [db (build-doc)
          result (intent/apply-intent db {:type :unknown-intent :id "a"})]
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

(deftest move-up-climb-out-semantics
  (testing "Move up on first child climbs out to parent's level"
    (let [db (build-nested-doc)
          ;; Set child-a as editing block (simulates selection)
          db (assoc-in db [:nodes const/session-ui-id :props :editing-block-id] "child-a")
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-up})
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
          ;; Set child-b as editing block
          db (assoc-in db [:nodes const/session-ui-id :props :editing-block-id] "child-b")
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-up})
          result (tx/interpret db ops)]
      (is (empty? (:issues result))
          "Move up should not generate issues")
      ;; child-b should now be before child-a
      (is (= ["child-b" "child-a" "child-c"] (get-in result [:db :children-by-parent "parent"]))
          "child-b should move before child-a"))))

(deftest move-down-descend-semantics
  (testing "Move down on last child descends into parent's next sibling"
    (let [db (build-nested-doc)
          ;; Set child-c as editing block (last child)
          db (assoc-in db [:nodes const/session-ui-id :props :editing-block-id] "child-c")
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-down})
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
          ;; Set child-a as editing block
          db (assoc-in db [:nodes const/session-ui-id :props :editing-block-id] "child-a")
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-down})
          result (tx/interpret db ops)]
      (is (empty? (:issues result))
          "Move down should not generate issues")
      ;; child-a should now be after child-b
      (is (= ["child-b" "child-a" "child-c"] (get-in result [:db :children-by-parent "parent"]))
          "child-a should move after child-b"))))

(deftest move-up-climb-multi-select
  (testing "Move up with multi-select climbs all selected nodes together"
    (let [db (build-nested-doc)
          ;; Select child-a and child-b (first two children)
          db (assoc-in db [:nodes const/session-selection-id :props :nodes] #{"child-a" "child-b"})
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-up})
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
          ;; Select child-b and child-c (last two children)
          db (assoc-in db [:nodes const/session-selection-id :props :nodes] #{"child-b" "child-c"})
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-down})
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
          ;; Set parent as editing block (first child of doc, which is a root)
          db (assoc-in db [:nodes const/session-ui-id :props :editing-block-id] "parent")
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-up})
          result (tx/interpret db ops)]
      (is (= [] ops)
          "Cannot climb out of root-level parent")
      (is (= db (:db result))
          "DB should remain unchanged"))))

(deftest move-down-no-uncle-noop
  (testing "Move down on last child with no uncle is no-op"
    (let [db (build-nested-doc)
          ;; Set uncle as editing block (last child of doc, no next sibling)
          db (assoc-in db [:nodes const/session-ui-id :props :editing-block-id] "uncle")
          ;; Add a child to uncle so we can test moving its last child
          {:keys [db]} (tx/interpret db
                                     [{:op :create-node :id "nephew" :type :block :props {:text "Nephew"}}
                                      {:op :place :id "nephew" :under "uncle" :at :last}])
          db (assoc-in db [:nodes const/session-ui-id :props :editing-block-id] "nephew")
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-down})
          result (tx/interpret db ops)]
      (is (= [] ops)
          "Cannot descend when parent has no next sibling")
      (is (= db (:db result))
          "DB should remain unchanged"))))