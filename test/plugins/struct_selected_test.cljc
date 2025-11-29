(ns plugins.struct-selected-test
  "Tests for selection-based structural operations.

   CRITICAL: These test the ACTUAL keyboard code paths (:indent-selected, :outdent-selected)
   which differ from single-block operations (:indent, :outdent).

   The keyboard shortcuts use the -selected variants which operate on:
   1. Selected blocks (if any)
   2. Currently editing block (if editing)

   Previous testing gap: We only tested :outdent/:indent (single block with explicit ID)
   but not :outdent-selected/:indent-selected (keyboard flow)."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [plugins.struct]))

;; ── Test Fixtures ────────────────────────────────────────────────────────────

(defn make-nested-tree
  "Create tree for testing indent/outdent:
   :doc
     a
       a1 (child of a)
     b
     c"
  []
  (-> (db/empty-db)
      (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "A"}}
                     {:op :create-node :id "a1" :type :block :props {:text "A1"}}
                     {:op :create-node :id "b" :type :block :props {:text "B"}}
                     {:op :create-node :id "c" :type :block :props {:text "C"}}
                     {:op :place :id "a" :under :doc :at :first}
                     {:op :place :id "a1" :under "a" :at :last}
                     {:op :place :id "b" :under :doc :at :last}
                     {:op :place :id "c" :under :doc :at :last}])
      :db))

(defn session-with-selection
  "Create session with block selected (blue background mode)."
  [block-id]
  {:selection {:nodes #{block-id} :focus block-id :anchor block-id}
   :ui {:editing-block-id nil}})

(defn session-with-editing
  "Create session with block in editing mode."
  [block-id]
  {:selection {:nodes #{} :focus nil :anchor nil}
   :ui {:editing-block-id block-id}})

;; ── Outdent Selected Tests ───────────────────────────────────────────────────

(deftest outdent-selected-with-selection
  (testing "Shift+Tab with block selected should outdent"
    (let [db (make-nested-tree)
          ;; a1 is child of a, selecting a1 and pressing Shift+Tab
          session (session-with-selection "a1")
          {:keys [ops]} (intent/apply-intent db session {:type :outdent-selected})
          result (tx/interpret db ops)]
      (is (seq ops)
          "Should generate outdent operations")
      (is (= :doc (get-in result [:db :derived :parent-of "a1"]))
          "a1 should become child of :doc (sibling of a)"))))

(deftest outdent-selected-with-editing
  (testing "Shift+Tab while editing should outdent and preserve editing"
    (let [db (make-nested-tree)
          ;; a1 is child of a, editing a1 and pressing Shift+Tab
          session (session-with-editing "a1")
          {:keys [ops session-updates]} (intent/apply-intent db session {:type :outdent-selected})
          result (tx/interpret db ops)]
      (is (seq ops)
          "Should generate outdent operations")
      (is (= :doc (get-in result [:db :derived :parent-of "a1"]))
          "a1 should become child of :doc")
      (is (= "a1" (get-in session-updates [:ui :editing-block-id]))
          "Should preserve editing state on a1"))))

(deftest outdent-selected-at-root-level
  (testing "Shift+Tab at root level should be no-op"
    (let [db (make-nested-tree)
          ;; a is already at root level (child of :doc)
          session (session-with-selection "a")
          {:keys [ops]} (intent/apply-intent db session {:type :outdent-selected})]
      (is (empty? ops)
          "Should not generate ops when already at root level"))))

;; ── Indent Selected Tests ────────────────────────────────────────────────────

(deftest indent-selected-with-selection
  (testing "Tab with block selected should indent under previous sibling"
    (let [db (make-nested-tree)
          ;; b is after a, selecting b and pressing Tab should make b child of a
          session (session-with-selection "b")
          {:keys [ops]} (intent/apply-intent db session {:type :indent-selected})
          result (tx/interpret db ops)]
      (is (seq ops)
          "Should generate indent operations")
      (is (= "a" (get-in result [:db :derived :parent-of "b"]))
          "b should become child of a (previous sibling)"))))

(deftest indent-selected-with-editing
  (testing "Tab while editing should indent and preserve editing"
    (let [db (make-nested-tree)
          session (session-with-editing "b")
          {:keys [ops session-updates]} (intent/apply-intent db session {:type :indent-selected})
          result (tx/interpret db ops)]
      (is (seq ops)
          "Should generate indent operations")
      (is (= "a" (get-in result [:db :derived :parent-of "b"]))
          "b should become child of a")
      (is (= "b" (get-in session-updates [:ui :editing-block-id]))
          "Should preserve editing state on b"))))

(deftest indent-selected-no-previous-sibling
  (testing "Tab with no previous sibling should be no-op"
    (let [db (make-nested-tree)
          ;; a is first child of :doc, no previous sibling
          session (session-with-selection "a")
          {:keys [ops]} (intent/apply-intent db session {:type :indent-selected})]
      (is (empty? ops)
          "Should not generate ops when no previous sibling exists"))))
