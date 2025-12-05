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

(deftest outdent-selected-multi-select-order
  (testing "Multi-select outdent should preserve relative order (Logseq parity)"
    ;; When multi-selecting children and outdenting, they should:
    ;; 1. All become siblings of their parent
    ;; 2. Maintain their relative order
    ;;
    ;; Example:
    ;; Before:              After:
    ;; :doc                 :doc
    ;;   parent               parent
    ;;     a1 ← selected      a1 (now sibling of parent)
    ;;     a2 ← selected      a2 (after a1, sibling of parent)
    (let [db (-> (db/empty-db)
                 (tx/interpret [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                                {:op :create-node :id "a1" :type :block :props {:text "A1"}}
                                {:op :create-node :id "a2" :type :block :props {:text "A2"}}
                                {:op :place :id "parent" :under :doc :at :first}
                                {:op :place :id "a1" :under "parent" :at :last}
                                {:op :place :id "a2" :under "parent" :at :last}])
                 :db)
          session {:selection {:nodes #{"a1" "a2"} :focus "a2" :anchor "a1"}
                   :ui {:editing-block-id nil}}
          {:keys [ops]} (intent/apply-intent db session {:type :outdent-selected})
          result (tx/interpret db ops)]
      (is (seq ops) "Should generate outdent operations")
      ;; Both should become children of :doc
      (is (= :doc (get-in result [:db :derived :parent-of "a1"])))
      (is (= :doc (get-in result [:db :derived :parent-of "a2"])))
      ;; a1 should come before a2 (relative order preserved)
      (is (= "a2" (get-in result [:db :derived :next-id-of "a1"]))
          "a1 should be followed by a2 (relative order preserved)"))))

(deftest indent-selected-multi-select-logseq-parity
  (testing "Multi-select indent should group under first's prev-sibling (Logseq parity)"
    ;; Logseq behavior: When selecting multiple consecutive siblings and pressing Tab,
    ;; ALL selected blocks move under the previous sibling of the FIRST selected block.
    ;; They remain siblings to each other (same level).
    ;;
    ;; Example:
    ;; Before:              After:
    ;; :doc                 :doc
    ;;   a                    a
    ;;   b ← selected           b (child of a)
    ;;   c ← selected           c (sibling of b, also child of a)
    (let [db (make-nested-tree)
          ;; Select b and c (consecutive siblings)
          session {:selection {:nodes #{"b" "c"} :focus "c" :anchor "b"}
                   :ui {:editing-block-id nil}}
          {:keys [ops]} (intent/apply-intent db session {:type :indent-selected})
          result (tx/interpret db ops)]
      (is (seq ops) "Should generate indent operations")
      (is (= "a" (get-in result [:db :derived :parent-of "b"]))
          "b should become child of a (prev-sibling of first selected)")
      (is (= "a" (get-in result [:db :derived :parent-of "c"]))
          "c should ALSO become child of a (same level as b, Logseq parity)"))))
