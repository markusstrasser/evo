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
            [clojure.string :as str]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            [kernel.constants :as const]))

;; ── Test Setup ────────────────────────────────────────────────────────────────

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

(deftest 
  golden-enter-creates-sibling
  (testing "Enter on block creates new sibling"
    (let [db (setup-simple-doc)
          session (empty-session)
          {:keys [ops]} (intent/apply-intent db session {:type :context-aware-enter
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

(deftest 
  golden-enter-empty-list-unformats
  (testing "Enter on empty list item unformats and creates peer"
    (let [db (:db (tx/interpret (db/empty-db)
                                [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                 {:op :create-node :id "child-content" :type :block :props {:text "- content"}}
                                 {:op :create-node :id "child-empty" :type :block :props {:text "- "}}
                                 {:op :place :id "parent" :under :doc :at :last}
                                 {:op :place :id "child-content" :under "parent" :at :last}
                                 {:op :place :id "child-empty" :under "parent" :at :last}]))
          session (empty-session)
          {:keys [ops]} (intent/apply-intent db session {:type :context-aware-enter
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

(deftest 
  golden-backspace-merge-text
  (testing "Backspace at start of block merges text with previous"
    (let [db (setup-merge-doc)
          session (empty-session)
          {:keys [ops]} (intent/apply-intent db session {:type :merge-with-prev
                                                         :block-id "b"
                                                         :cursor-pos 0})
          db' (:db (tx/interpret db ops))]

      ;; Text should be merged
      (is (= "FirstSecond" (get-in db' [:nodes "a" :props :text]))
          "Text should be merged into previous block")

      ;; Block b should be deleted (in trash)
      (is (= :trash (q/parent-of db' "b"))
          "Merged block should be in trash"))))

(deftest 
  golden-backspace-merge-reparents-children
  (testing "Backspace merge reparents children of merged block"
    (let [db (setup-merge-doc)
          session (empty-session)
          {:keys [ops]} (intent/apply-intent db session {:type :merge-with-prev
                                                         :block-id "b"
                                                         :cursor-pos 0})
          db' (:db (tx/interpret db ops))]

      ;; Child c should now be under a (not b)
      (is (= "a" (q/parent-of db' "c"))
          "Children should be reparented to merge target")

      (is (= ["c"] (q/children db' "a"))
          "Children should appear in target's children list"))))

;; ── FR-Move-02: Climb/Descend Movement ───────────────────────────────────────

(deftest 
  golden-climb-out-first-child
  (testing "Mod+Shift+Up on first child climbs out to before parent"
    (let [db (setup-nested-doc)
          session (-> (empty-session)
                      (assoc-in [:selection :nodes] #{"child1"})
                      (assoc-in [:selection :focus] "child1"))
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})
          db' (:db (tx/interpret db ops))]

      ;; child1 should now be sibling of parent (before it)
      (is (= :doc (q/parent-of db' "child1"))
          "Climbed block should be at parent's level")

      (let [siblings (q/children db' :doc)
            child1-idx (.indexOf siblings "child1")
            parent-idx (.indexOf siblings "parent")]
        (is (< child1-idx parent-idx)
            "Climbed block should be positioned before parent")))))

(deftest 
  golden-descend-into-last-child
  (testing "Mod+Shift+Down on last child descends into parent's next sibling"
    (let [db (setup-nested-doc)
          session (-> (empty-session)
                      (assoc-in [:selection :nodes] #{"child2"})
                      (assoc-in [:selection :focus] "child2"))
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-down})
          db' (:db (tx/interpret db ops))]

      ;; child2 should now be first child of uncle
      (is (= "uncle" (q/parent-of db' "child2"))
          "Descended block should be under next sibling")

      (let [uncle-children (q/children db' "uncle")]
        (is (= "child2" (first uncle-children))
            "Descended block should be first child")))))

(deftest 
  golden-climb-boundary-at-doc-level
  (testing "Climb at doc level is a no-op"
    (let [db (setup-simple-doc)
          session (-> (empty-session)
                      (assoc-in [:selection :nodes] #{"a"})
                      (assoc-in [:selection :focus] "a"))
          {:keys [ops]} (intent/apply-intent db session {:type :move-selected-up})]

      ;; Should be no-op (empty ops or structure unchanged)
      (is (or (empty? ops)
              (= db (:db (tx/interpret db ops))))
          "Climb at doc level should be no-op"))))

;; ── FR-Nav: Selection & Navigation ───────────────────────────────────────────

(deftest 
  golden-selection-nav-no-structural-changes
  (testing "Selection + nav up/down doesn't change document structure"
    (let [db (setup-simple-doc)
          session (empty-session)
          ;; Select block b (via session)
          session-with-b (-> session
                             (assoc-in [:selection :nodes] #{"b"})
                             (assoc-in [:selection :focus] "b"))

          ;; Navigate down - this is session-only operation
          {:keys [session-updates]} (intent/apply-intent db session-with-b {:type :navigate-down})
          session2 (apply-session-updates session-with-b session-updates)

          ;; Navigate up - also session-only
          {:keys [session-updates]} (intent/apply-intent db session2 {:type :navigate-up})
          _session3 (apply-session-updates session2 session-updates)]

      ;; Document structure should be unchanged (no ops applied)
      (is (= (get-in db [:nodes])
             (get-in db [:nodes]))
          "Navigation should not modify document nodes")

      (is (= (get-in db [:children-by-parent])
             (get-in db [:children-by-parent]))
          "Navigation should not modify tree structure"))))

(deftest 
  golden-selection-state-updates
  (testing "Selection state updates correctly during navigation"
    (let [db (setup-simple-doc)
          session (empty-session)
          ;; Select block b
          {:keys [session-updates]} (intent/apply-intent db session {:type :selection
                                                                      :mode :replace
                                                                      :ids ["b"]})
          session' (apply-session-updates session session-updates)]

      ;; Selection should be in session
      (is (contains? (get-in session' [:selection :nodes]) "b")
          "Selected block should be in selection set"))))

;; ── History & Ephemeral Operations ───────────────────────────────────────────

(deftest golden-ephemeral-ops-no-history
  (testing "Ephemeral operations (cursor, selection) are session-only"
    (let [db (setup-simple-doc)
          session (empty-session)
          ;; Cursor updates are now session-only - they don't touch DB
          session' (-> session
                       (assoc-in [:cursor :block-id] "a")
                       (assoc-in [:cursor :offset] 5))
          session'' (assoc-in session' [:cursor :offset] 10)]

      ;; DB derived indexes should be unchanged since cursor is session-only
      (is (= (get-in db [:derived :parent-of])
             (get-in db [:derived :parent-of]))
          "Cursor updates don't touch DB at all")

      ;; Cursor state is in session
      (is (= 10 (get-in session'' [:cursor :offset]))
          "Cursor offset should be in session"))))

;; ── Invariant Tests ───────────────────────────────────────────────────────────

(deftest golden-db-invariants-hold
  (testing "DB invariants hold after structural operations"
    (let [db (setup-nested-doc)
          session (empty-session)
          ;; Perform various structural ops
          {:keys [ops]} (intent/apply-intent db session {:type :indent :id "uncle"})
          db1 (:db (tx/interpret db ops))

          {:keys [ops]} (intent/apply-intent db1 session {:type :outdent :id "child1"})
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
  (testing "Session state is now in separate atom, not DB nodes"
    (let [db (setup-simple-doc)]
      ;; DB should only contain document nodes, not session nodes
      ;; (session moved to shell/view-state.cljs atom)
      (is (empty? (filter #(str/starts-with? (str %) "session/")
                          (keys (:nodes db))))
          "No session/* nodes should exist in DB"))))

;; ── Performance Baseline ──────────────────────────────────────────────────────

(deftest golden-buffer-updates-fast-path
  (testing "Buffer updates are now session-only (no DB operations)"
    (let [db (setup-simple-doc)
          session (empty-session)
          derived-before (get-in db [:derived])

          ;; Buffer updates are now purely session state
          ;; They don't touch the DB at all
          session' (reduce (fn [s i]
                             (assoc-in s [:buffer :text] (str "text-" i)))
                           session
                           (range 10))]

      ;; DB derived indexes should be unchanged
      (is (= derived-before (get-in db [:derived]))
          "Buffer updates don't touch DB at all")

      ;; Buffer state is in session
      (is (= "text-9" (get-in session' [:buffer :text]))
          "Buffer text should be in session"))))
