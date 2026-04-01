(ns integration.keybinding-test
  "Integration tests for keyboard shortcuts end-to-end.

   Tests verify: key sequence → intent dispatch → final state.
   Agent-friendly: minimal setup, clear assertions, red/green feedback."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.fixtures :as fixtures]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.api :as api]))

(use-fixtures :once fixtures/bootstrap-runtime)

;; ── Test Fixtures ─────────────────────────────────────────────────────────────

(defn interpret-ops
  "Interpret operations and return db with derived indexes."
  [db ops]
  (-> (tx/interpret db ops {})
      :db))

(defn make-tree
  "Create minimal tree for testing:
   :doc
     a
     b
       b1
       b2
     c"
  []
  (interpret-ops
   (db/empty-db)
   [{:op :create-node :id "a" :type :block :props {:text "Alpha"}}
    {:op :create-node :id "b" :type :block :props {:text "Beta"}}
    {:op :create-node :id "b1" :type :block :props {:text "Beta-1"}}
    {:op :create-node :id "b2" :type :block :props {:text "Beta-2"}}
    {:op :create-node :id "c" :type :block :props {:text "Gamma"}}
    {:op :place :id "a" :under :doc :at :first}
    {:op :place :id "b" :under :doc :at :last}
    {:op :place :id "c" :under :doc :at :last}
    {:op :place :id "b1" :under "b" :at :first}
    {:op :place :id "b2" :under "b" :at :last}]))

(defn children-of
  "Get children IDs of parent."
  [db parent]
  (get-in db [:children-by-parent parent] []))

;; ── Navigation Tests ──────────────────────────────────────────────────────────

(deftest test-arrow-down-navigation
  (testing "ArrowDown: Navigate to next sibling"
    (let [;; Select "a" - returns session-updates
          {:keys [session-updates]} (api/dispatch (make-tree) nil {:type :selection :mode :replace :ids "a"})
          session1 session-updates
          ;; Simulate ArrowDown keypress with current session state
          {:keys [session-updates]} (api/dispatch (make-tree) session1 {:type :selection :mode :next})
          focus (get-in session-updates [:selection :focus])]
      ;; Assert focus moved from "a" to "b"
      (is (= "b" focus)
          "Focus should move to next sibling"))))

(deftest test-arrow-up-navigation
  (testing "ArrowUp: Navigate to previous block in DOM order (depth-first)"
    (let [;; Select "c" - returns session-updates
          {:keys [session-updates]} (api/dispatch (make-tree) nil {:type :selection :mode :replace :ids "c"})
          session1 session-updates
          ;; Simulate ArrowUp keypress with current session state
          {:keys [session-updates]} (api/dispatch (make-tree) session1 {:type :selection :mode :prev})
          focus (get-in session-updates [:selection :focus])]
      ;; Assert focus moved from "c" to "b2" (DOM-order: last child of prev sibling)
      ;; Tree: a, b(b1, b2), c → prev from c in DOM order is b2
      (is (= "b2" focus)
          "Focus should move to previous block in DOM order (b2, last child of b)"))))

;; ── Selection Tests ───────────────────────────────────────────────────────────

(deftest test-shift-arrow-extend-selection
  (testing "Shift+ArrowDown: Extend selection to next sibling"
    (let [;; Select "a" - returns session-updates
          {:keys [session-updates]} (api/dispatch (make-tree) nil {:type :selection :mode :replace :ids "a"})
          session1 session-updates
          ;; Simulate Shift+ArrowDown with current session state
          {:keys [session-updates]} (api/dispatch (make-tree) session1 {:type :selection :mode :extend-next})
          selection (get-in session-updates [:selection :nodes])]
      ;; Assert both "a" and "b" are selected
      (is (contains? selection "a") "Original node should remain selected")
      (is (contains? selection "b") "Next sibling should be added to selection")
      (is (= 2 (count selection)) "Selection should contain exactly 2 nodes"))))

(deftest test-shift-arrow-up-extend-selection
  (testing "Shift+ArrowUp: Extend selection to previous block in DOM order"
    (let [;; Select "c" - returns session-updates
          {:keys [session-updates]} (api/dispatch (make-tree) nil {:type :selection :mode :replace :ids "c"})
          session1 session-updates
          ;; Simulate Shift+ArrowUp (extends to b2, prev in DOM order)
          {:keys [session-updates]} (api/dispatch (make-tree) session1 {:type :selection :mode :extend-prev})
          selection (get-in session-updates [:selection :nodes])]
      ;; Tree: a, b(b1, b2), c → prev from c in DOM order is b2
      ;; Extend-prev adds b2 to selection
      (is (contains? selection "c") "Original node should remain selected")
      (is (contains? selection "b2") "Previous block in DOM order (b2) should be added")
      (is (= 2 (count selection)) "Selection should contain exactly 2 nodes"))))

;; ── Structural Edit Tests ─────────────────────────────────────────────────────

(deftest test-tab-indent
  (testing "Tab: Indent selected block under previous sibling"
    (let [db (make-tree)
          ;; Selection now returns session-updates. For structural ops,
          ;; we need to set up a session with selection first
          session {:selection {:nodes #{"b"} :focus "b" :anchor "b"}}
          ;; Simulate Tab keypress
          result (api/dispatch db session {:type :indent-selected})
          final-db (:db result)]
      ;; Assert "b" moved under "a", "c" remains at :doc level
      (is (= ["a" "c"] (children-of final-db :doc))
          ":doc should have 'a' and 'c' as direct children")
      (is (= "a" (get-in final-db [:derived :parent-of "b"]))
          "'b' should be child of 'a'"))))

(deftest test-shift-tab-outdent
  (testing "Shift+Tab: Outdent nested block to become sibling of parent"
    (let [db (make-tree)
          ;; Set up session with selection of "b1" (child of "b")
          session {:selection {:nodes #{"b1"} :focus "b1" :anchor "b1"}}
          ;; Simulate Shift+Tab keypress
          result (api/dispatch db session {:type :outdent-selected})
          final-db (:db result)]
      ;; b1 was under "b", outdenting makes it sibling of "b" under :doc
      (is (= :doc (get-in final-db [:derived :parent-of "b1"]))
          "'b1' should become child of :doc (sibling of 'b')")
      (is (= ["b2"] (children-of final-db "b"))
          "'b' should only have 'b2' as child after b1 was outdented"))))

(deftest test-shift-tab-outdent-at-root
  (testing "Shift+Tab: Outdent prevented when already at root level"
    (let [db (make-tree)
          ;; Set up session with selection of "a" (direct child of :doc)
          session {:selection {:nodes #{"a"} :focus "a" :anchor "a"}}
          ;; Simulate Shift+Tab keypress
          result (api/dispatch db session {:type :outdent-selected})
          final-db (:db result)]
      ;; "a" is already a direct child of :doc, can't outdent further
      (is (= :doc (get-in final-db [:derived :parent-of "a"]))
          "'a' should remain under :doc (can't outdent from root level)")
      (is (= ["a" "b" "c"] (children-of final-db :doc))
          ":doc should still have same children"))))

;; ── Move Tests ────────────────────────────────────────────────────────────────

(deftest test-alt-shift-arrow-up-move
  (testing "Alt+Shift+ArrowUp: Move selected block up"
    (let [db (make-tree)
          ;; Set up session with selection of "b"
          session {:selection {:nodes #{"b"} :focus "b" :anchor "b"}}
          ;; Simulate Alt+Shift+ArrowUp
          result (api/dispatch db session {:type :move-selected-up})
          final-db (:db result)]
      ;; Assert "b" moved before "a"
      (is (= ["b" "a" "c"] (children-of final-db :doc))
          "'b' should move before 'a'"))))

(deftest test-alt-shift-arrow-down-move
  (testing "Alt+Shift+ArrowDown: Move selected block down"
    (let [db (make-tree)
          ;; Set up session with selection of "a"
          session {:selection {:nodes #{"a"} :focus "a" :anchor "a"}}
          ;; Simulate Alt+Shift+ArrowDown
          result (api/dispatch db session {:type :move-selected-down})
          final-db (:db result)]
      ;; Assert "a" moved after "b"
      (is (= ["b" "a" "c"] (children-of final-db :doc))
          "'a' should move after 'b'"))))

;; ── Edit Mode Tests ───────────────────────────────────────────────────────────

(deftest test-enter-creates-new-block
  (testing "Enter: Create new block after focused block"
    (let [db (make-tree)
          parent (get-in db [:derived :parent-of "a"])
          new-id "new-block"
          ;; Simulate Enter keypress (creates new block after "a")
          result (api/dispatch db nil {:type :create-and-place
                                       :id new-id
                                       :parent parent
                                       :after "a"})
          final-db (:db result)]
      ;; Assert new block created after "a"
      (is (= ["a" new-id "b" "c"] (children-of final-db :doc))
          "New block should appear after 'a'")
      (is (some? (get-in final-db [:nodes new-id]))
          "New block should exist"))))
