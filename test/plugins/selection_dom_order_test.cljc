(ns plugins.selection-dom-order-test
  "Tests for DOM order selection navigation (Logseq parity).

   CRITICAL: Selection must use DOM/visual order (pre-order traversal),
   NOT sibling order. This ensures Shift+Down from parent selects first child."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as D]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]))

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn interpret-ops
  "Helper to interpret ops and return resulting db."
  [db ops]
  (:db (tx/interpret db ops)))

(defn select [db ids]
  "Helper: select using intent->ops"
  (interpret-ops db (intent/intent->ops db {:type :selection :mode :replace :ids ids})))

(defn extend-next [db]
  "Helper: extend selection down (Shift+Down in DOM order)"
  (interpret-ops db (intent/intent->ops db {:type :selection :mode :extend-next})))

(defn extend-prev [db]
  "Helper: extend selection up (Shift+Up in DOM order)"
  (interpret-ops db (intent/intent->ops db {:type :selection :mode :extend-prev})))

(defn select-next [db]
  "Helper: select next in DOM order (plain Down arrow)"
  (interpret-ops db (intent/intent->ops db {:type :selection :mode :next})))

(defn select-prev [db]
  "Helper: select prev in DOM order (plain Up arrow)"
  (interpret-ops db (intent/intent->ops db {:type :selection :mode :prev})))

(defn build-nested-doc
  "Creates a test DB with nested structure for DOM order tests:
   doc1 -> [a -> [a1, a2], b -> [b1], c]

   DOM order (pre-order traversal): a, a1, a2, b, b1, c
   Sibling order (WRONG): a, b, c"
  []
  (let [DB0 (D/empty-db)
        {:keys [db issues]} (tx/interpret DB0
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
          ;; Select 'a', then Shift+Down should select 'a1' (first child), NOT 'b' (next sibling)
          db' (-> db
                  (select "a")
                  (extend-next))]
      (is (= #{"a" "a1"} (q/selection db'))
          "Shift+Down from 'a' should select child 'a1', not sibling 'b' (DOM order)")
      (is (= "a1" (q/focus db'))
          "Focus should be on 'a1'"))))

(deftest plain-down-from-last-child-crosses-boundary
  (testing "LOGSEQ PARITY: Plain Down from last child navigates to parent's next sibling in DOM order"
    (let [db (build-nested-doc)
          ;; Select 'a2' (last child of 'a'), then Down should select 'b' (parent's next sibling)
          db' (-> db
                  (select "a2")
                  (select-next))]
      (is (= #{"b"} (q/selection db'))
          "Down from 'a2' (last child) should select 'b' (parent's next sibling in DOM order)")
      (is (= "b" (q/focus db'))
          "Focus should be on 'b'"))))

(deftest incremental-selection-contraction
  (testing "LOGSEQ PARITY: Selection contracts incrementally when reversing direction"
    (let [db (build-nested-doc)
          ;; Start at 'a', extend down to include a1, a2, b
          db-extended (-> db
                          (select "a")
                          (extend-next)   ; Add a1
                          (extend-next)   ; Add a2
                          (extend-next))  ; Add b
          _ (is (= #{"a" "a1" "a2" "b"} (q/selection db-extended))
                "Extended selection should include a, a1, a2, b")

          ;; Now contract by going up 2x
          db-contract-1 (extend-prev db-extended)
          _ (is (= #{"a" "a1" "a2"} (q/selection db-contract-1))
                "First Shift+Up should remove 'b' (trailing block)")

          db-contract-2 (extend-prev db-contract-1)
          _ (is (= #{"a" "a1"} (q/selection db-contract-2))
                "Second Shift+Up should remove 'a2' (trailing block)")]

      ;; Verify final state
      (is (= #{"a" "a1"} (q/selection db-contract-2))
          "After extend down 3x then contract up 2x, selection should be {a, a1}"))))

(deftest multi-level-dom-traversal
  (testing "LOGSEQ PARITY: Navigate through multiple tree levels in DOM order"
    (let [db (build-nested-doc)]
      ;; DOM order: a, a1, a2, b, b1, c
      ;; Navigate from 'a' through entire tree
      (is (= "a1" (q/focus (-> db (select "a") (select-next))))
          "Next from 'a' should be 'a1' (child)")
      (is (= "a2" (q/focus (-> db (select "a1") (select-next))))
          "Next from 'a1' should be 'a2' (sibling)")
      (is (= "b" (q/focus (-> db (select "a2") (select-next))))
          "Next from 'a2' should be 'b' (parent's next sibling)")
      (is (= "b1" (q/focus (-> db (select "b") (select-next))))
          "Next from 'b' should be 'b1' (child)")
      (is (= "c" (q/focus (-> db (select "b1") (select-next))))
          "Next from 'b1' should be 'c' (parent's next sibling)"))))

(deftest reverse-dom-traversal
  (testing "LOGSEQ PARITY: Navigate backward through tree levels in DOM order"
    (let [db (build-nested-doc)]
      ;; DOM order: a, a1, a2, b, b1, c
      ;; Navigate backward from 'c'
      (is (= "b1" (q/focus (-> db (select "c") (select-prev))))
          "Prev from 'c' should be 'b1' (parent's last child)")
      (is (= "b" (q/focus (-> db (select "b1") (select-prev))))
          "Prev from 'b1' should be 'b' (parent)")
      (is (= "a2" (q/focus (-> db (select "b") (select-prev))))
          "Prev from 'b' should be 'a2' (prev sibling's last child)")
      (is (= "a1" (q/focus (-> db (select "a2") (select-prev))))
          "Prev from 'a2' should be 'a1' (sibling)")
      (is (= "a" (q/focus (-> db (select "a1") (select-prev))))
          "Prev from 'a1' should be 'a' (parent)"))))

(deftest user-scenario-grandchild-up-3x-down-2x
  (testing "USER SCENARIO: Grandchild → Shift+Up 3x → Shift+Down 2x"
    (let [db (build-nested-doc)
          ;; Start at grandchild a2
          ;; Shift+Up 3x should extend to: {a2, a1, a}
          db-up-1 (-> db (select "a2") (extend-prev))   ; Add a1
          _ (is (= #{"a2" "a1"} (q/selection db-up-1)))

          db-up-2 (extend-prev db-up-1)                  ; Add a
          _ (is (= #{"a2" "a1" "a"} (q/selection db-up-2)))

          db-up-3 (extend-prev db-up-2)                  ; Add doc1 (parent of a)
          _ (is (= #{"a2" "a1" "a" "doc1"} (q/selection db-up-3)))

          ;; Now Shift+Down 2x should contract: remove a2, then a1
          db-down-1 (extend-next db-up-3)               ; Remove a2 (trailing)
          _ (is (= #{"a1" "a" "doc1"} (q/selection db-down-1)))

          db-down-2 (extend-next db-down-1)]            ; Remove a1 (trailing)

      ;; Final selection should be {a, doc1}
      (is (= #{"a" "doc1"} (q/selection db-down-2))
          "After up 3x down 2x, selection should contract correctly"))))
