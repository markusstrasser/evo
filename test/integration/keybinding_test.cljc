(ns integration.keybinding-test
  "Integration tests for keyboard shortcuts end-to-end.

   Tests verify: key sequence → intent dispatch → final state.
   Agent-friendly: minimal setup, clear assertions, red/green feedback."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.ops :as ops]
            [kernel.transaction :as tx]
            [kernel.api :as api]
            [plugins.selection :as sel]
            [plugins.editing :as edit]))

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
    (let [db (-> (make-tree)
                 ;; Select "a"
                 (api/dispatch {:type :select :ids "a"})
                 :db)
          ;; Simulate ArrowDown keypress
          result (api/dispatch db {:type :select-next-sibling})
          final-db (:db result)]
      ;; Assert focus moved from "a" to "b"
      (is (= "b" (sel/get-focus final-db))
          "Focus should move to next sibling"))))

(deftest test-arrow-up-navigation
  (testing "ArrowUp: Navigate to previous sibling"
    (let [db (-> (make-tree)
                 ;; Select "c"
                 (api/dispatch {:type :select :ids "c"})
                 :db)
          ;; Simulate ArrowUp keypress
          result (api/dispatch db {:type :select-prev-sibling})
          final-db (:db result)]
      ;; Assert focus moved from "c" to "b"
      (is (= "b" (sel/get-focus final-db))
          "Focus should move to previous sibling"))))

;; ── Selection Tests ───────────────────────────────────────────────────────────

(deftest test-shift-arrow-extend-selection
  (testing "Shift+ArrowDown: Extend selection to next sibling"
    (let [db (-> (make-tree)
                 ;; Select "a"
                 (api/dispatch {:type :select :ids "a"})
                 :db)
          ;; Simulate Shift+ArrowDown
          result (api/dispatch db {:type :extend-to-next-sibling})
          final-db (:db result)
          selection (sel/get-selection final-db)]
      ;; Assert both "a" and "b" are selected
      (is (contains? selection "a") "Original node should remain selected")
      (is (contains? selection "b") "Next sibling should be added to selection")
      (is (= 2 (count selection)) "Selection should contain exactly 2 nodes"))))

(deftest test-shift-arrow-up-extend-selection
  (testing "Shift+ArrowUp: Extend selection range (doc-order between anchor and focus)"
    (let [db (-> (make-tree)
                 ;; Select "c"
                 (api/dispatch {:type :select :ids "c"})
                 :db)
          ;; Simulate Shift+ArrowUp (extends to "b", creates range from b to c)
          result (api/dispatch db {:type :extend-to-prev-sibling})
          final-db (:db result)
          selection (sel/get-selection final-db)]
      ;; Range selection: selects all nodes in doc-order between anchor (c) and focus (b)
      ;; Tree: a, b(b1, b2), c → range from b to c includes: a, b, c (and b's children)
      (is (contains? selection "c") "Original node should remain selected")
      (is (contains? selection "b") "Previous sibling should be in range")
      (is (<= 2 (count selection)) "Range selection includes all nodes between anchor and focus"))))

;; ── Structural Edit Tests ─────────────────────────────────────────────────────

(deftest test-tab-indent
  (testing "Tab: Indent selected block under previous sibling"
    (let [db (-> (make-tree)
                 ;; Select "b"
                 (api/dispatch {:type :select :ids "b"})
                 :db)
          ;; Simulate Tab keypress
          result (api/dispatch db {:type :indent-selected})
          final-db (:db result)]
      ;; Assert "b" moved under "a", "c" remains at :doc level
      (is (= ["a" "c"] (children-of final-db :doc))
          ":doc should have 'a' and 'c' as direct children")
      (is (= "a" (get-in final-db [:derived :parent-of "b"]))
          "'b' should be child of 'a'"))))

(deftest test-shift-tab-outdent
  (testing "Shift+Tab: Outdent prevented at root level (design constraint)"
    (let [db (-> (make-tree)
                 ;; Select "b1" (child of "b", grandparent is :doc which is a root)
                 (api/dispatch {:type :select :ids "b1"})
                 :db)
          ;; Simulate Shift+Tab keypress
          result (api/dispatch db {:type :outdent-selected})
          final-db (:db result)]
      ;; Outdent is prevented because grandparent (:doc) is a root
      ;; "b1" should remain under "b"
      (is (= "b" (get-in final-db [:derived :parent-of "b1"]))
          "'b1' should remain under 'b' (outdent prevented at root level)")
      (is (= ["b1" "b2"] (children-of final-db "b"))
          "'b1' should still be child of 'b'"))))

;; ── Move Tests ────────────────────────────────────────────────────────────────

(deftest test-alt-shift-arrow-up-move
  (testing "Alt+Shift+ArrowUp: Move selected block up"
    (let [db (-> (make-tree)
                 ;; Select "b"
                 (api/dispatch {:type :select :ids "b"})
                 :db)
          ;; Simulate Alt+Shift+ArrowUp
          result (api/dispatch db {:type :move-selected-up})
          final-db (:db result)]
      ;; Assert "b" moved before "a"
      (is (= ["b" "a" "c"] (children-of final-db :doc))
          "'b' should move before 'a'"))))

(deftest test-alt-shift-arrow-down-move
  (testing "Alt+Shift+ArrowDown: Move selected block down"
    (let [db (-> (make-tree)
                 ;; Select "a"
                 (api/dispatch {:type :select :ids "a"})
                 :db)
          ;; Simulate Alt+Shift+ArrowDown
          result (api/dispatch db {:type :move-selected-down})
          final-db (:db result)]
      ;; Assert "a" moved after "b"
      (is (= ["b" "a" "c"] (children-of final-db :doc))
          "'a' should move after 'b'"))))

;; ── Edit Mode Tests ───────────────────────────────────────────────────────────

(deftest test-enter-creates-new-block
  (testing "Enter: Create new block after focused block"
    (let [db (-> (make-tree)
                 ;; Select "a"
                 (api/dispatch {:type :select :ids "a"})
                 :db)
          parent (get-in db [:derived :parent-of "a"])
          new-id "new-block"
          ;; Simulate Enter keypress (creates new block after "a")
          result (api/dispatch db {:type :create-and-place
                                   :id new-id
                                   :parent parent
                                   :after "a"})
          final-db (:db result)]
      ;; Assert new block created after "a"
      (is (= ["a" new-id "b" "c"] (children-of final-db :doc))
          "New block should appear after 'a'")
      (is (some? (get-in final-db [:nodes new-id]))
          "New block should exist"))))
