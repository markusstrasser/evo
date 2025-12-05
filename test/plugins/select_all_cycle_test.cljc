(ns plugins.select-all-cycle-test
  "Tests for Cmd+A cycle behavior (Logseq parity).
   
   LOGSEQ_SPEC §7.2: Cmd+A cycles through selection levels:
   1. First press (editing) → select all text (browser handles)
   2. Second press (all text selected) → exit edit, select block
   3. Third press (block selected) → select parent
   4. Fourth press (parent selected) → select all visible"
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            [plugins.selection]))

;; ── Test Fixtures ────────────────────────────────────────────────────────────

(defn make-nested-tree
  "Create a nested tree for testing Cmd+A cycle:
   :doc
     parent
       child-a
       child-b
     sibling"
  []
  (-> (db/empty-db)
      (tx/interpret [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
                     {:op :create-node :id "child-a" :type :block :props {:text "Child A"}}
                     {:op :create-node :id "child-b" :type :block :props {:text "Child B"}}
                     {:op :create-node :id "sibling" :type :block :props {:text "Sibling"}}
                     {:op :place :id "parent" :under :doc :at :first}
                     {:op :place :id "child-a" :under "parent" :at :last}
                     {:op :place :id "child-b" :under "parent" :at :last}
                     {:op :place :id "sibling" :under :doc :at :last}])
      :db))

(defn session-with-editing
  "Session in editing state."
  [block-id]
  {:ui {:editing-block-id block-id}
   :selection {:nodes #{} :focus nil :anchor nil}})

(defn session-with-selection
  "Session in selection state."
  [block-ids]
  (let [ids (if (coll? block-ids) (set block-ids) #{block-ids})
        focus-id (if (coll? block-ids) (last (vec block-ids)) block-ids)]
    {:ui {:editing-block-id nil}
     :selection {:nodes ids :focus focus-id :anchor focus-id}}))

(defn session-idle
  "Session in idle state."
  []
  {:ui {:editing-block-id nil}
   :selection {:nodes #{} :focus nil :anchor nil}})

;; ── Step 2: From Editing → Select Block ─────────────────────────────────────

(deftest ^{:fr/ids #{:fr.selection/cmd-a-cycle}}
  from-editing-selects-block
  (testing "Cmd+A with all text selected exits edit and selects block"
    (let [db (make-nested-tree)
          session (session-with-editing "child-a")
          intent {:type :select-all-cycle :from-editing? true :block-id "child-a"}
          {:keys [session-updates]} (intent/apply-intent db session intent)]
      (is (= #{"child-a"} (get-in session-updates [:selection :nodes]))
          "Should select the editing block")
      (is (nil? (get-in session-updates [:ui :editing-block-id]))
          "Should exit edit mode"))))

;; ── Step 3: Single Block Selected → Select Parent ──────────────────────────

(deftest ^{:fr/ids #{:fr.selection/cmd-a-cycle}}
  single-block-selects-parent
  (testing "Cmd+A on single block selects parent"
    (let [db (make-nested-tree)
          session (session-with-selection "child-a")
          intent {:type :select-all-cycle}
          {:keys [session-updates]} (intent/apply-intent db session intent)]
      (is (= #{"parent"} (get-in session-updates [:selection :nodes]))
          "Should select parent block"))))

(deftest ^{:fr/ids #{:fr.selection/cmd-a-cycle}}
  root-block-selects-all-visible
  (testing "Cmd+A on root-level block selects all visible"
    (let [db (make-nested-tree)
          ;; parent is already at :doc level - no selectable parent
          session (session-with-selection "parent")
          intent {:type :select-all-cycle}
          {:keys [session-updates]} (intent/apply-intent db session intent)]
      (is (= 4 (count (get-in session-updates [:selection :nodes])))
          "Should select all 4 blocks in document"))))

;; ── Step 4: Multiple/Parent Selected → Select All Visible ──────────────────

(deftest ^{:fr/ids #{:fr.selection/cmd-a-cycle}}
  multiple-blocks-selects-all-visible
  (testing "Cmd+A with multiple blocks selected selects all visible"
    (let [db (make-nested-tree)
          session (session-with-selection ["child-a" "child-b"])
          intent {:type :select-all-cycle}
          {:keys [session-updates]} (intent/apply-intent db session intent)]
      (is (= 4 (count (get-in session-updates [:selection :nodes])))
          "Should select all 4 blocks"))))

;; ── Idle State → Select First Block ─────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.selection/cmd-a-cycle}}
  idle-state-selects-first-block
  (testing "Cmd+A from idle state selects first visible block"
    (let [db (make-nested-tree)
          session (session-idle)
          intent {:type :select-all-cycle}
          {:keys [session-updates]} (intent/apply-intent db session intent)]
      (is (= #{"parent"} (get-in session-updates [:selection :nodes]))
          "Should select first visible block (parent)"))))

;; ── Full Cycle Test ─────────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.selection/cmd-a-cycle}}
  full-cmd-a-cycle
  (testing "Complete Cmd+A cycle: edit → block → parent → all"
    (let [db (make-nested-tree)]
      ;; Step 2: From editing (simulating all text selected)
      (let [session (session-with-editing "child-a")
            {:keys [session-updates]} (intent/apply-intent db session
                                                           {:type :select-all-cycle
                                                            :from-editing? true
                                                            :block-id "child-a"})]
        (is (= #{"child-a"} (get-in session-updates [:selection :nodes]))
            "Step 2: Should select child-a")

        ;; Step 3: Block selected → parent
        (let [session2 {:ui {:editing-block-id nil}
                        :selection (get session-updates :selection)}
              {:keys [session-updates]} (intent/apply-intent db session2
                                                             {:type :select-all-cycle})]
          (is (= #{"parent"} (get-in session-updates [:selection :nodes]))
              "Step 3: Should select parent")

          ;; Step 4: Parent selected → all visible
          (let [session3 {:ui {:editing-block-id nil}
                          :selection (get session-updates :selection)}
                {:keys [session-updates]} (intent/apply-intent db session3
                                                               {:type :select-all-cycle})]
            (is (= 4 (count (get-in session-updates [:selection :nodes])))
                "Step 4: Should select all 4 blocks")))))))
