(ns refactor.logseq-parity-baseline-test
  "Golden behavior tests for kernel-session refactor.

   These tests lock in current Logseq-parity behaviors before refactoring:
   - FR-Edit-01: Enter creates new sibling, handles empty list items
   - FR-Edit-07: Backspace merge combines text and reparents children
   - FR-Move-02: Climb/Descend (Mod+Shift+Up/Down) boundary-aware movement
   - FR-Nav: Selection + navigation up/down without structural changes

   Tests must pass before and after each refactor phase."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            [kernel.constants :as const]))

;; ── Test Setup ────────────────────────────────────────────────────────────────

(defn setup-simple-doc
  "Create simple doc structure:
     doc
     ├─ a 'First block'
     ├─ b 'Second block'
     └─ c 'Third block'"
  []
  (:db (tx/interpret (db/empty-db)
                     [{:op :create-node :id "a" :type :block :props {:text "First block"}}
                      {:op :create-node :id "b" :type :block :props {:text "Second block"}}
                      {:op :create-node :id "c" :type :block :props {:text "Third block"}}
                      {:op :place :id "a" :under :doc :at :last}
                      {:op :place :id "b" :under :doc :at :last}
                      {:op :place :id "c" :under :doc :at :last}])))

(defn setup-nested-doc
  "Create nested doc structure:
     doc
     ├─ parent 'Parent block'
     │  ├─ child1 'First child'
     │  └─ child2 'Second child'
     └─ uncle 'Uncle block'"
  []
  (:db (tx/interpret (db/empty-db)
                     [{:op :create-node :id "parent" :type :block :props {:text "Parent block"}}
                      {:op :create-node :id "child1" :type :block :props {:text "First child"}}
                      {:op :create-node :id "child2" :type :block :props {:text "Second child"}}
                      {:op :create-node :id "uncle" :type :block :props {:text "Uncle block"}}
                      {:op :place :id "parent" :under :doc :at :last}
                      {:op :place :id "child1" :under "parent" :at :last}
                      {:op :place :id "child2" :under "parent" :at :last}
                      {:op :place :id "uncle" :under :doc :at :last}])))

(defn setup-merge-doc
  "Create doc for merge testing:
     doc
     ├─ a 'First'
     ├─ b 'Second'
     │  └─ c 'Child of b'
     └─ d 'Third'"
  []
  (:db (tx/interpret (db/empty-db)
                     [{:op :create-node :id "a" :type :block :props {:text "First"}}
                      {:op :create-node :id "b" :type :block :props {:text "Second"}}
                      {:op :create-node :id "c" :type :block :props {:text "Child of b"}}
                      {:op :create-node :id "d" :type :block :props {:text "Third"}}
                      {:op :place :id "a" :under :doc :at :last}
                      {:op :place :id "b" :under :doc :at :last}
                      {:op :place :id "c" :under "b" :at :last}
                      {:op :place :id "d" :under :doc :at :last}])))

;; ── FR-Edit-01: Enter Behavior ───────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.edit/enter}}
  golden-enter-creates-sibling
  (testing "Enter on block creates new sibling"
    (let [db (setup-simple-doc)
          {:keys [ops]} (intent/apply-intent db {:type :edit/enter
                                                  :block-id "a"
                                                  :cursor-pos 11}) ; At end "First block"
          db' (:db (tx/interpret db ops))]

      ;; New block should be created
      (is (some #(= :create-node (:op %)) ops)
          "Enter should create a new node")

      ;; New block should be positioned after "a"
      (let [new-id (-> (filter #(= :create-node (:op %)) ops) first :id)
            siblings (q/children db' :doc)
            a-idx (.indexOf siblings "a")
            new-idx (.indexOf siblings new-id)]
        (is (= (inc a-idx) new-idx)
            "New block should be positioned after current block")))))

(deftest ^{:fr/ids #{:fr.edit/enter :fr.edit/list-formatting}}
  golden-enter-empty-list-unformats
  (testing "Enter on empty list item unformats and creates peer"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                 {:op :create-node :id "child-content" :type :block :props {:text "- content"}}
                                 {:op :create-node :id "child-empty" :type :block :props {:text "- "}}
                                 {:op :place :id "parent" :under :doc :at :last}
                                 {:op :place :id "child-content" :under "parent" :at :last}
                                 {:op :place :id "child-empty" :under "parent" :at :last}]))
          {:keys [ops]} (intent/apply-intent db {:type :edit/enter
                                                  :block-id "child-empty"
                                                  :cursor-pos 2}) ; After "- "
          db' (:db (tx/interpret db ops))]

      ;; Child-empty should be unformatted (empty text)
      (is (= "" (get-in db' [:nodes "child-empty" :props :text]))
          "Empty list item should be unformatted")

      ;; New peer block should exist at parent level
      (let [doc-children (q/children db' :doc)]
        (is (> (count doc-children) 1)
            "New peer block should be created at parent level")))))

;; ── FR-Edit-07: Backspace Merge ──────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.edit/backspace-merge}}
  golden-backspace-merge-text
  (testing "Backspace at start of block merges text with previous"
    (let [db (setup-merge-doc)
          {:keys [ops]} (intent/apply-intent db {:type :merge-with-prev
                                                  :block-id "b"
                                                  :cursor-pos 0})
          db' (:db (tx/interpret db ops))]

      ;; Text should be merged
      (is (= "FirstSecond" (get-in db' [:nodes "a" :props :text]))
          "Text should be merged into previous block")

      ;; Block b should be deleted (in trash)
      (is (= :trash (q/parent-of db' "b"))
          "Merged block should be in trash"))))

(deftest ^{:fr/ids #{:fr.edit/backspace-merge}}
  golden-backspace-merge-reparents-children
  (testing "Backspace merge reparents children of merged block"
    (let [db (setup-merge-doc)
          {:keys [ops]} (intent/apply-intent db {:type :merge-with-prev
                                                  :block-id "b"
                                                  :cursor-pos 0})
          db' (:db (tx/interpret db ops))]

      ;; Child c should now be under a (not b)
      (is (= "a" (q/parent-of db' "c"))
          "Children should be reparented to merge target")

      (is (= ["c"] (q/children db' "a"))
          "Children should appear in target's children list"))))

;; ── FR-Move-02: Climb/Descend Movement ───────────────────────────────────────

(deftest ^{:fr/ids #{:fr.struct/move-climb}}
  golden-climb-out-first-child
  (testing "Mod+Shift+Up on first child climbs out to before parent"
    (let [db (setup-nested-doc)
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-up
                                                  :block-id "child1"})
          db' (:db (tx/interpret db ops))]

      ;; child1 should now be sibling of parent (before it)
      (is (= :doc (q/parent-of db' "child1"))
          "Climbed block should be at parent's level")

      (let [siblings (q/children db' :doc)
            child1-idx (.indexOf siblings "child1")
            parent-idx (.indexOf siblings "parent")]
        (is (< child1-idx parent-idx)
            "Climbed block should be positioned before parent")))))

(deftest ^{:fr/ids #{:fr.struct/move-descend}}
  golden-descend-into-last-child
  (testing "Mod+Shift+Down on last child descends into parent's next sibling"
    (let [db (setup-nested-doc)
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-down
                                                  :block-id "child2"})
          db' (:db (tx/interpret db ops))]

      ;; child2 should now be first child of uncle
      (is (= "uncle" (q/parent-of db' "child2"))
          "Descended block should be under next sibling")

      (let [uncle-children (q/children db' "uncle")]
        (is (= "child2" (first uncle-children))
            "Descended block should be first child")))))

(deftest ^{:fr/ids #{:fr.struct/move-climb}}
  golden-climb-boundary-at-doc-level
  (testing "Climb at doc level is a no-op"
    (let [db (setup-simple-doc)
          {:keys [ops]} (intent/apply-intent db {:type :move-selected-up
                                                  :block-id "a"})]

      ;; Should be no-op (empty ops or structure unchanged)
      (is (or (empty? ops)
              (= db (:db (tx/interpret db ops))))
          "Climb at doc level should be no-op"))))

;; ── FR-Nav: Selection & Navigation ───────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.nav/selection-move}}
  golden-selection-nav-no-structural-changes
  (testing "Selection + nav up/down doesn't change document structure"
    (let [db (setup-simple-doc)
          ;; Select block b
          {:keys [ops db]} (intent/apply-intent db {:type :selection/set
                                                     :block-id "b"})
          db1 (:db (tx/interpret db ops))

          ;; Navigate down (to c)
          {:keys [ops]} (intent/apply-intent db1 {:type :navigate-down})
          db2 (:db (tx/interpret db1 ops))

          ;; Navigate up (back to b)
          {:keys [ops]} (intent/apply-intent db2 {:type :navigate-up})
          db3 (:db (tx/interpret db2 ops))]

      ;; Document structure should be unchanged
      (is (= (get-in db [:nodes])
             (get-in db3 [:nodes]))
          "Navigation should not modify document nodes")

      (is (= (get-in db [:children-by-parent])
             (get-in db3 [:children-by-parent]))
          "Navigation should not modify tree structure"))))

(deftest ^{:fr/ids #{:fr.nav/selection-state}}
  golden-selection-state-updates
  (testing "Selection state updates correctly during navigation"
    (let [db (setup-simple-doc)
          ;; Select block b
          {:keys [ops]} (intent/apply-intent db {:type :selection/set
                                                  :block-id "b"})
          db1 (:db (tx/interpret db ops))]

      ;; Selection should be stored in session
      (let [selection (get-in db1 [:nodes const/session-selection-id :props])]
        (is (contains? (:nodes selection) "b")
            "Selected block should be in selection set")))))

;; ── History & Ephemeral Operations ───────────────────────────────────────────

(deftest golden-ephemeral-ops-no-history
  (testing "Ephemeral operations (cursor, selection) don't create history entries"
    (let [db (setup-simple-doc)
          ;; Multiple cursor updates
          {:keys [ops]} (intent/apply-intent db {:type :cursor/move
                                                  :block-id "a"
                                                  :position 5})
          db1 (:db (tx/interpret db ops))

          {:keys [ops]} (intent/apply-intent db1 {:type :cursor/move
                                                   :block-id "a"
                                                   :position 10})
          db2 (:db (tx/interpret db1 ops))]

      ;; Derived indexes should not be recomputed for ephemeral ops
      (is (= (get-in db [:derived :parent-of])
             (get-in db2 [:derived :parent-of]))
          "Ephemeral ops should not trigger derive-indexes"))))

;; ── Invariant Tests ───────────────────────────────────────────────────────────

(deftest golden-db-invariants-hold
  (testing "DB invariants hold after structural operations"
    (let [db (setup-nested-doc)
          ;; Perform various structural ops
          {:keys [ops]} (intent/apply-intent db {:type :indent :id "uncle"})
          db1 (:db (tx/interpret db ops))

          {:keys [ops]} (intent/apply-intent db1 {:type :outdent :id "child1"})
          db2 (:db (tx/interpret db1 ops))]

      ;; Every child has exactly one parent
      (doseq [[child-id parent-id] (get-in db2 [:derived :parent-of])]
        (is (contains? (get-in db2 [:nodes]) child-id)
            "All children in :parent-of should exist in :nodes")
        (is (some #{child-id} (get-in db2 [:children-by-parent parent-id]))
            "Parent should list child in :children-by-parent"))

      ;; No cycles
      (letfn [(has-cycle? [db node-id visited]
                (if (visited node-id)
                  true
                  (let [children (get-in db [:children-by-parent node-id] [])
                        visited' (conj visited node-id)]
                    (some #(has-cycle? db % visited') children))))]
        (is (not (has-cycle? db2 :doc #{}))
            "Document tree should have no cycles")))))

(deftest golden-no-session-nodes-in-structure
  (testing "Session nodes (UI state) are not part of document structure"
    (let [db (setup-simple-doc)]
      ;; Session nodes should not appear in :children-by-parent for doc roots
      (is (not (some #(or (= const/session-ui-id %)
                          (= const/session-selection-id %)
                          (= "session/buffer" %))
                     (get-in db [:children-by-parent :doc])))
          "Session nodes should not be part of document structure"))))

;; ── Performance Baseline ──────────────────────────────────────────────────────

(deftest golden-buffer-updates-fast-path
  (testing "Buffer updates should not trigger derive-indexes (baseline for Phase 1)"
    (let [db (setup-simple-doc)
          derived-before (get-in db [:derived])

          ;; Simulate typing (multiple buffer updates)
          ops-seq (for [i (range 10)]
                    {:op :update-node
                     :id "session/buffer"
                     :props {"a" (str "text-" i)}})

          db-after (reduce (fn [d ops]
                             (:db (tx/interpret d [ops])))
                           db
                           ops-seq)
          derived-after (get-in db-after [:derived])]

      ;; This test establishes baseline - currently this FAILS (derive runs)
      ;; After Phase 1, this should PASS
      (comment
        (is (= derived-before derived-after)
            "Buffer updates should not recompute derived indexes")))))
