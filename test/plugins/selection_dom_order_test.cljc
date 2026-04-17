(ns plugins.selection-dom-order-test
  "Tests for DOM order selection navigation (Logseq parity).

   CRITICAL: Selection must use DOM/visual order (pre-order traversal),
   NOT sibling order. This ensures Shift+Down from parent selects first child."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]))

;; NOTE: visible-order plugin removed - navigation now uses (db, session) directly
;; No fixture needed - tests exercise selection which calls kernel/navigation internally

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

(defn apply-session-updates
  "Apply session-updates returned by a handler to a session."
  [session session-updates]
  (if session-updates
    (merge-with merge session session-updates)
    session))

(defn apply-selection-intent
  "Apply a selection intent and return updated session."
  [db session intent]
  (let [{:keys [session-updates]} (intent/apply-intent db session intent)]
    (apply-session-updates session session-updates)))

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn build-nested-doc
  "Creates a test DB with nested structure for DOM order tests:
   doc1 -> [a -> [a1, a2], b -> [b1], c]

   DOM order (pre-order traversal): a, a1, a2, b, b1, c
   Sibling order (WRONG): a, b, c"
  []
  (let [db0 (db/empty-db)
        {:keys [db issues]} (tx/interpret db0
                                          [{:op :create-node :id "doc1" :type :doc :props {}}
                                           {:op :place :id "doc1" :under :doc :at :last}
                               ;; Create parent blocks
                                           {:op :create-node :id "a" :type :block :props {:text "A"}}
                                           {:op :place :id "a" :under "doc1" :at :last}
                                           {:op :create-node :id "b" :type :block :props {:text "B"}}
                                           {:op :place :id "b" :under "doc1" :at :last}
                                           {:op :create-node :id "c" :type :block :props {:text "C"}}
                                           {:op :place :id "c" :under "doc1" :at :last}
                               ;; Create children of a
                                           {:op :create-node :id "a1" :type :block :props {:text "A1"}}
                                           {:op :place :id "a1" :under "a" :at :last}
                                           {:op :create-node :id "a2" :type :block :props {:text "A2"}}
                                           {:op :place :id "a2" :under "a" :at :last}
                               ;; Create child of b
                                           {:op :create-node :id "b1" :type :block :props {:text "B1"}}
                                           {:op :place :id "b1" :under "b" :at :last}])]
    (assert (empty? issues) (str "Nested fixture setup failed: " (pr-str issues)))
    db))

;; ── DOM Order Selection Tests ─────────────────────────────────────────────────

(deftest shift-down-selects-child-not-sibling
  (testing "LOGSEQ PARITY: Shift+Down from parent selects first child in DOM order, not next sibling"
    (let [db (build-nested-doc)
          session (empty-session)
          ;; Select 'a', then Shift+Down should select 'a1' (first child), NOT 'b' (next sibling)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "a"})
          session' (apply-selection-intent db session1 {:type :selection :mode :extend-next})]
      (is (= #{"a" "a1"} (q/selection session'))
          "Shift+Down from 'a' should select child 'a1', not sibling 'b' (DOM order)")
      (is (= "a1" (q/focus session'))
          "Focus should be on 'a1'"))))

(deftest plain-down-from-last-child-crosses-boundary
  (testing "LOGSEQ PARITY: Plain Down from last child navigates to parent's next sibling in DOM order"
    (let [db (build-nested-doc)
          session (empty-session)
          ;; Select 'a2' (last child of 'a'), then Down should select 'b' (parent's next sibling)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "a2"})
          session' (apply-selection-intent db session1 {:type :selection :mode :next})]
      (is (= #{"b"} (q/selection session'))
          "Down from 'a2' (last child) should select 'b' (parent's next sibling in DOM order)")
      (is (= "b" (q/focus session'))
          "Focus should be on 'b'"))))

(deftest incremental-selection-contraction
  (testing "LOGSEQ PARITY: Selection contracts incrementally when reversing direction"
    (let [db (build-nested-doc)
          session (empty-session)
          ;; Start at 'a', extend down to include a1, a2, b
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "a"})
          session2 (apply-selection-intent db session1 {:type :selection :mode :extend-next}) ; Add a1
          session3 (apply-selection-intent db session2 {:type :selection :mode :extend-next}) ; Add a2
          session-extended (apply-selection-intent db session3 {:type :selection :mode :extend-next}) ; Add b
          _ (is (= #{"a" "a1" "a2" "b"} (q/selection session-extended))
                "Extended selection should include a, a1, a2, b")

          ;; Now contract by going up 2x
          session-contract-1 (apply-selection-intent db session-extended {:type :selection :mode :extend-prev})
          _ (is (= #{"a" "a1" "a2"} (q/selection session-contract-1))
                "First Shift+Up should remove 'b' (trailing block)")

          session-contract-2 (apply-selection-intent db session-contract-1 {:type :selection :mode :extend-prev})
          _ (is (= #{"a" "a1"} (q/selection session-contract-2))
                "Second Shift+Up should remove 'a2' (trailing block)")]

      ;; Verify final state
      (is (= #{"a" "a1"} (q/selection session-contract-2))
          "After extend down 3x then contract up 2x, selection should be {a, a1}"))))

(deftest multi-level-dom-traversal
  (testing "LOGSEQ PARITY: Navigate through multiple tree levels in DOM order"
    (let [db (build-nested-doc)
          session (empty-session)]
      ;; DOM order: a, a1, a2, b, b1, c
      ;; Navigate from 'a' through entire tree
      (is (= "a1" (q/focus (apply-selection-intent db
                                                   (apply-selection-intent db session {:type :selection :mode :replace :ids "a"})
                                                   {:type :selection :mode :next})))
          "Next from 'a' should be 'a1' (child)")
      (is (= "a2" (q/focus (apply-selection-intent db
                                                   (apply-selection-intent db session {:type :selection :mode :replace :ids "a1"})
                                                   {:type :selection :mode :next})))
          "Next from 'a1' should be 'a2' (sibling)")
      (is (= "b" (q/focus (apply-selection-intent db
                                                  (apply-selection-intent db session {:type :selection :mode :replace :ids "a2"})
                                                  {:type :selection :mode :next})))
          "Next from 'a2' should be 'b' (parent's next sibling)")
      (is (= "b1" (q/focus (apply-selection-intent db
                                                   (apply-selection-intent db session {:type :selection :mode :replace :ids "b"})
                                                   {:type :selection :mode :next})))
          "Next from 'b' should be 'b1' (child)")
      (is (= "c" (q/focus (apply-selection-intent db
                                                  (apply-selection-intent db session {:type :selection :mode :replace :ids "b1"})
                                                  {:type :selection :mode :next})))
          "Next from 'b1' should be 'c' (parent's next sibling)"))))

(deftest reverse-dom-traversal
  (testing "LOGSEQ PARITY: Navigate backward through tree levels in DOM order"
    (let [db (build-nested-doc)
          session (empty-session)]
      ;; DOM order: a, a1, a2, b, b1, c
      ;; Navigate backward from 'c'
      (is (= "b1" (q/focus (apply-selection-intent db
                                                   (apply-selection-intent db session {:type :selection :mode :replace :ids "c"})
                                                   {:type :selection :mode :prev})))
          "Prev from 'c' should be 'b1' (parent's last child)")
      (is (= "b" (q/focus (apply-selection-intent db
                                                  (apply-selection-intent db session {:type :selection :mode :replace :ids "b1"})
                                                  {:type :selection :mode :prev})))
          "Prev from 'b1' should be 'b' (parent)")
      (is (= "a2" (q/focus (apply-selection-intent db
                                                   (apply-selection-intent db session {:type :selection :mode :replace :ids "b"})
                                                   {:type :selection :mode :prev})))
          "Prev from 'b' should be 'a2' (prev sibling's last child)")
      (is (= "a1" (q/focus (apply-selection-intent db
                                                   (apply-selection-intent db session {:type :selection :mode :replace :ids "a2"})
                                                   {:type :selection :mode :prev})))
          "Prev from 'a2' should be 'a1' (sibling)")
      (is (= "a" (q/focus (apply-selection-intent db
                                                  (apply-selection-intent db session {:type :selection :mode :replace :ids "a1"})
                                                  {:type :selection :mode :prev})))
          "Prev from 'a1' should be 'a' (parent)"))))

(deftest user-scenario-grandchild-up-3x-down-2x
  (testing "USER SCENARIO: Grandchild → Shift+Up 3x → Shift+Down 2x"
    (let [db (build-nested-doc)
          session (empty-session)
          ;; Start at grandchild a2
          ;; Shift+Up 3x extends selection in DOM order
          session-start (apply-selection-intent db session {:type :selection :mode :replace :ids "a2"})
          session-up-1 (apply-selection-intent db session-start {:type :selection :mode :extend-prev}) ; Add a1
          _ (is (= #{"a2" "a1"} (q/selection session-up-1)))

          session-up-2 (apply-selection-intent db session-up-1 {:type :selection :mode :extend-prev}) ; Add a
          _ (is (= #{"a2" "a1" "a"} (q/selection session-up-2)))

          ;; Third extend-prev: a is first child of doc1. In DOM order, prev from a is doc1.
          ;; But doc1 is a :doc container node, not selected by block navigation.
          ;; Selection stays at {a2, a1, a} (no more nodes to extend to)
          session-up-3 (apply-selection-intent db session-up-2 {:type :selection :mode :extend-prev})
          _ (is (= #{"a2" "a1" "a"} (q/selection session-up-3))
                "No more blocks to extend to (doc1 is container, not block)")

          ;; Now Shift+Down 2x should contract: remove a (trailing focus), then a1
          session-down-1 (apply-selection-intent db session-up-3 {:type :selection :mode :extend-next})
          _ (is (= #{"a2" "a1"} (q/selection session-down-1))
                "First contract removes trailing block")

          session-down-2 (apply-selection-intent db session-down-1 {:type :selection :mode :extend-next})]

      ;; Final selection after contraction
      (is (= #{"a2"} (q/selection session-down-2))
          "After up 3x down 2x, should end with anchor only"))))
