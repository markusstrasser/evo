(ns outliner-user-stories-test
  "Tests covering the user stories from docs/outliner-user-stories.md"
  (:require [cljs.test :refer [deftest testing is are run-tests]]
            [evolver.kernel :as k]
            [evolver.intents :as intents]
            [evolver.dispatcher :as dispatcher]))

;; === TEST FIXTURES ===

(def outliner-test-db
  "A hierarchical document structure for testing outliner operations"
  {:nodes {"root" {:type :div}
           "p1" {:type :p :props {:text "P1"}}
           "p1-c1" {:type :p :props {:text "P1.C1"}}
           "p1-c2" {:type :p :props {:text "P1.C2"}}
           "p2" {:type :p :props {:text "P2"}}
           "p2-c1" {:type :p :props {:text "P2.C1"}} 
           "p2-c2" {:type :p :props {:text "P2.C2"}}
           "p3" {:type :p :props {:text "P3"}}}
   :children-by-parent {"root" ["p1" "p2" "p3"]
                        "p1" ["p1-c1" "p1-c2"]
                        "p2" ["p2-c1" "p2-c2"]
                        "p3" []}
   :view {:selection []
          :highlighted #{}
          :collapsed #{}}
   :derived {:depth {"root" 0 "p1" 1 "p1-c1" 2 "p1-c2" 2 "p2" 1 "p2-c1" 2 "p2-c2" 2 "p3" 1}
             :paths {"root" [] "p1" ["root"] "p1-c1" ["root" "p1"] "p1-c2" ["root" "p1"]
                     "p2" ["root"] "p2-c1" ["root" "p2"] "p2-c2" ["root" "p2"] "p3" ["root"]}
             :parent-of {"p1" "root" "p1-c1" "p1" "p1-c2" "p1" "p2" "root" "p2-c1" "p2" "p2-c2" "p2" "p3" "root"}}})

(def outliner-atom-store
  {:past []
   :present outliner-test-db
   :future []
   :view (:view outliner-test-db)})

;; === NAVIGATION TESTS (Sequential Movement) ===

(deftest block-navigation-down-test
  (testing "Moving Down (Visual Next) - User Story Implementation"
    (let [store (atom outliner-atom-store)]
      
      ;; Test: From P1 (expanded with children) -> should go to first child P1.C1
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [nav-down-fn (get intents/intents :navigate-down)]
        (dispatcher/dispatch-intent! store :navigate-down {:cursor "p1"})
        (let [new-selection (get-in @store [:present :view :selection])]
          (is (= ["p1-c1"] new-selection) "Should move from parent to first child")))

      ;; Test: From P1.C1 -> should go to sibling P1.C2
      (swap! store assoc-in [:present :view :selection] ["p1-c1"]) 
      (when-let [nav-down-fn (get intents/intents :navigate-down)]
        (dispatcher/dispatch-intent! store :navigate-down {:cursor "p1-c1"})
        (let [new-selection (get-in @store [:present :view :selection])]
          (is (= ["p1-c2"] new-selection) "Should move to right sibling")))

      ;; Test: From P1.C2 (no children, no right sibling) -> should go to uncle P2
      (swap! store assoc-in [:present :view :selection] ["p1-c2"])
      (when-let [nav-down-fn (get intents/intents :navigate-down)]
        (dispatcher/dispatch-intent! store :navigate-down {:cursor "p1-c2"})
        (let [new-selection (get-in @store [:present :view :selection])]
          (is (= ["p2"] new-selection) "Should traverse up to find uncle"))))))

(deftest block-navigation-up-test
  (testing "Moving Up (Visual Previous) - User Story Implementation"
    (let [store (atom outliner-atom-store)]

      ;; Test: From P2 -> should go to P1.C2 (last descendant of previous sibling)
      (swap! store assoc-in [:present :view :selection] ["p2"])
      (when-let [nav-up-fn (get intents/intents :navigate-up)]
        (dispatcher/dispatch-intent! store :navigate-up {})
        (let [new-selection (get-in @store [:present :view :selection])]
          (is (= ["p1-c2"] new-selection) "Should descend into previous sibling's last child")))

      ;; Test: From P1.C1 -> should go to parent P1
      (swap! store assoc-in [:present :view :selection] ["p1-c1"])
      (when-let [nav-up-fn (get intents/intents :navigate-up)]
        (dispatcher/dispatch-intent! store :navigate-up {})
        (let [new-selection (get-in @store [:present :view :selection])]
          (is (= ["p1"] new-selection) "Should move to parent when no left sibling"))))))

;; === STRUCTURAL OPERATIONS (Hierarchical Changes) ===

(deftest indent-outdent-single-block-test
  (testing "Indent/Outdent Single Block - User Story Implementation"
    (let [store (atom outliner-atom-store)]

      ;; Test: Indent P2 -> should become child of P1
      (swap! store assoc-in [:present :view :selection] ["p2"])
      (when-let [indent-fn (get intents/intents :indent-block)]
        (dispatcher/dispatch-intent! store :indent-block {:cursor "p2"})
        (let [new-state (:present @store)
              p1-children (get-in new-state [:children-by-parent "p1"])
              root-children (get-in new-state [:children-by-parent "root"])]
          (is (some #{"p2"} p1-children) "P2 should be child of P1 after indent")
          (is (not (some #{"p2"} root-children)) "P2 should no longer be child of root")))

      ;; Reset state
      (reset! store outliner-atom-store)

      ;; Test: Outdent P1.C1 -> should become sibling of P1
      (swap! store assoc-in [:present :view :selection] ["p1-c1"])
      (when-let [outdent-fn (get intents/intents :outdent-block)]
        (dispatcher/dispatch-intent! store :outdent-block {:cursor "p1-c1"})
        (let [new-state (:present @store)
              root-children (get-in new-state [:children-by-parent "root"])
              p1-children (get-in new-state [:children-by-parent "p1"])]
          (is (some #{"p1-c1"} root-children) "P1.C1 should be child of root after outdent")
          (is (not (some #{"p1-c1"} p1-children)) "P1.C1 should no longer be child of P1"))))))

(deftest multi-select-operations-test
  (testing "Multi-Select Indent/Outdent - User Story Implementation"
    (let [store (atom outliner-atom-store)]

      ;; Test: Multi-select P2 and P2.C1, then indent
      ;; P2 is the root of selection, P2.C1 should move with it
      (swap! store assoc-in [:present :view :selection] ["p2" "p2-c1"])
      (when-let [indent-fn (get intents/intents :indent-selection)]
        (dispatcher/dispatch-intent! store :indent-selection {})
        (let [new-state (:present @store)]
          ;; P2 should be child of P1 (its previous sibling)
          (is (some #{"p2"} (get-in new-state [:children-by-parent "p1"]))
              "P2 should become child of P1")
          ;; P2.C1 should still be child of P2 (moves with parent)
          (is (some #{"p2-c1"} (get-in new-state [:children-by-parent "p2"]))
              "P2.C1 should move with its parent P2")))

      ;; Reset state
      (reset! store outliner-atom-store)

      ;; Test: Multi-select outdent operation
      (swap! store assoc-in [:present :view :selection] ["p1-c1" "p1-c2"])
      (when-let [outdent-fn (get intents/intents :outdent-selection)]
        (dispatcher/dispatch-intent! store :outdent-selection {})
        (let [new-state (:present @store)
              root-children (get-in new-state [:children-by-parent "root"])]
          (is (some #{"p1-c1"} root-children) "P1.C1 should be promoted to root level")
          (is (some #{"p1-c2"} root-children) "P1.C2 should be promoted to root level"))))))

;; === BLOCK CREATION AND SPLITTING TESTS ===

(deftest enter-new-block-test
  (testing "ENTER (New Block / Split Block) - User Story Implementation"
    (let [store (atom outliner-atom-store)]

      ;; Test: ENTER at end of block creates new sibling
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [enter-fn (get intents/intents :enter-new-block)]
        (dispatcher/dispatch-intent! store :enter-new-block {:cursor "p1" :cursor-position nil :block-content "P1"})
        (let [new-state (:present @store)
              root-children (get-in new-state [:children-by-parent "root"])
              node-count (count (:nodes new-state))]
          (is (> node-count (count (:nodes outliner-test-db))) "Should create new node")
          ;; New block should be inserted after P1
          (let [p1-index (.indexOf root-children "p1")
                next-node (nth root-children (inc p1-index) nil)]
            (is (some? next-node) "Should have new sibling after P1"))))

      ;; Reset state
      (reset! store outliner-atom-store)

      ;; Test: ENTER in middle of block splits content
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [enter-fn (get intents/intents :enter-new-block)]
        (dispatcher/dispatch-intent! store :enter-new-block {:cursor "p1" :cursor-position 1 :block-content "P1"})
        (let [new-state (:present @store)
              original-text (get-in outliner-test-db [:nodes "p1" :props :text])
              p1-text (get-in new-state [:nodes "p1" :props :text])]
          ;; Original P1 should have content before cursor
          (is (not= original-text p1-text) "P1 content should be modified after split")
          ;; A new node should exist with remaining content
          (is (> (count (:nodes new-state)) (count (:nodes outliner-test-db))) "Should create split node"))))))

(deftest shift-enter-line-break-test
  (testing "SHIFT+ENTER (Line Break) - User Story Implementation"
    (let [store (atom outliner-atom-store)
          original-node-count (count (get-in @store [:present :nodes]))]

      ;; Test: SHIFT+ENTER creates line break, no new blocks
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [line-break-fn (get intents/intents :insert-line-break)]
        (dispatcher/dispatch-intent! store :insert-line-break {:cursor-position 1})
        (let [new-state (:present @store)
              new-node-count (count (:nodes new-state))
              p1-text (get-in new-state [:nodes "p1" :props :text])]
          (is (= original-node-count new-node-count) "Should not create new blocks")
          (is (clojure.string/includes? p1-text "\n") "Should contain newline character"))))))

;; === BLOCK LIFECYCLE & REORDERING TESTS ===

(deftest block-deletion-test
  (testing "Deletion (Destructive Backspace/Delete) - User Story Implementation" 
    (let [store (atom outliner-atom-store)]

      ;; Test: Delete parent P1, children should be re-parented
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [delete-fn (get intents/intents :delete-block)]
        (dispatcher/dispatch-intent! store :delete-block {})
        (let [new-state (:present @store)
              root-children (get-in new-state [:children-by-parent "root"])]
          (is (not (contains? (:nodes new-state) "p1")) "P1 should be deleted")
          ;; P1's children should become children of root
          (is (some #{"p1-c1"} root-children) "P1.C1 should be re-parented to root")
          (is (some #{"p1-c2"} root-children) "P1.C2 should be re-parented to root"))))))

(deftest block-reordering-test
  (testing "Direct Reordering (Move Block Up/Down) - User Story Implementation"
    (let [store (atom outliner-atom-store)]

      ;; Test: Move P2 up (should swap with P1)
      (swap! store assoc-in [:present :view :selection] ["p2"])
      (when-let [move-up-fn (get intents/intents :move-block-up)]
        (dispatcher/dispatch-intent! store :move-block-up {})
        (let [new-state (:present @store)
              root-children (get-in new-state [:children-by-parent "root"])
              p1-index (.indexOf root-children "p1")
              p2-index (.indexOf root-children "p2")]
          (is (< p2-index p1-index) "P2 should now come before P1")))

      ;; Reset state
      (reset! store outliner-atom-store)

      ;; Test: Move P1 down (should swap with P2)
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [move-down-fn (get intents/intents :move-block-down)]
        (dispatcher/dispatch-intent! store :move-block-down {})
        (let [new-state (:present @store)
              root-children (get-in new-state [:children-by-parent "root"])
              p1-index (.indexOf root-children "p1")
              p2-index (.indexOf root-children "p2")]
          (is (> p1-index p2-index) "P1 should now come after P2"))))))

;; === BLOCK MERGING & CONTENT MANIPULATION TESTS ===

(deftest block-merging-test
  (testing "Block Merging (Backspace/Delete at boundaries) - User Story Implementation"
    (let [store (atom outliner-atom-store)]

      ;; Test: Backspace at start of P2 merges with P1
      (swap! store assoc-in [:present :view :selection] ["p2"])
      (when-let [merge-up-fn (get intents/intents :merge-block-up)]
        (dispatcher/dispatch-intent! store :merge-block-up {:cursor "p2" :cursor-position 0})
        (let [new-state (:present @store)
              p1-text (get-in new-state [:nodes "p1" :props :text])
              original-p1-text (get-in outliner-test-db [:nodes "p1" :props :text])
              original-p2-text (get-in outliner-test-db [:nodes "p2" :props :text])]
          (is (not (contains? (:nodes new-state) "p2")) "P2 should be deleted")
          (is (= p1-text (str original-p1-text original-p2-text)) "P1 should contain merged content")
          ;; P2's children should become P1's children
          (let [p1-children (get-in new-state [:children-by-parent "p1"])]
            (is (some #{"p2-c1"} p1-children) "P2.C1 should become child of P1")
            (is (some #{"p2-c2"} p1-children) "P2.C2 should become child of P1"))))

      ;; Reset state
      (reset! store outliner-atom-store)

      ;; Test: Delete at end of P1 merges with P2
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [merge-down-fn (get intents/intents :merge-block-down)]
        (dispatcher/dispatch-intent! store :merge-block-down {:cursor "p1" :cursor-position 2 :block-content "P1"})
        (let [new-state (:present @store)]
          (is (not (contains? (:nodes new-state) "p2")) "P2 should be deleted")
          ;; P1 should have merged content and P2's children
          (let [p1-children (get-in new-state [:children-by-parent "p1"])]
            (is (some #{"p2-c1"} p1-children) "P2.C1 should become child of P1")))))))

;; === BLOCK TYPE MANIPULATION TESTS ===

(deftest block-type-cycling-test
  (testing "Cycle Heading Level & Toggle Block Type - User Story Implementation"
    (let [store (atom outliner-atom-store)]

      ;; Test: Cycle heading level
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [cycle-heading-fn (get intents/intents :cycle-heading-level)]
        (dispatcher/dispatch-intent! store :cycle-heading-level {})
        (let [new-state (:present @store)
              p1-heading (get-in new-state [:nodes "p1" :props :heading-level])]
          (is (some? p1-heading) "P1 should have heading level after cycling")))

      ;; Test: Toggle block to TODO
      (swap! store assoc-in [:present :view :selection] ["p2"])
      (when-let [toggle-todo-fn (get intents/intents :toggle-todo)]
        (dispatcher/dispatch-intent! store :toggle-todo {})
        (let [new-state (:present @store)
              p2-text (get-in new-state [:nodes "p2" :props :text])]
          (is (clojure.string/includes? p2-text "TODO") "P2 should contain TODO marker"))))))

;; === INTEGRATION & EDGE CASE TESTS ===

(deftest outliner-integration-test
  (testing "Full outliner workflow integration"
    (let [store (atom outliner-atom-store)]

      ;; Complex workflow: Navigate, select, indent, create, delete
      ;; 1. Navigate to P1
      (swap! store assoc-in [:present :view :selection] ["p1"])

      ;; 2. Create new block after P1
      (when-let [enter-fn (get intents/intents :enter-new-block)]
        (dispatcher/dispatch-intent! store :enter-new-block {:cursor "p1" :cursor-position nil :block-content "P1"}))

      ;; 3. Get the new block ID and indent it
      (let [state-after-create (:present @store)
            root-children (get-in state-after-create [:children-by-parent "root"])
            p1-index (.indexOf root-children "p1")
            new-block-id (nth root-children (inc p1-index) nil)]

        (when new-block-id
          ;; Select and indent the new block
          (swap! store assoc-in [:present :view :selection] [new-block-id])
          (when-let [indent-fn (get intents/intents :indent-block)]
            (dispatcher/dispatch-intent! store :indent-block {:cursor new-block-id}))

          (let [final-state (:present @store)
                p1-children (get-in final-state [:children-by-parent "p1"])]
            (is (some #{new-block-id} p1-children) "New block should be indented under P1")))))))

(deftest outliner-edge-cases-test
  (testing "Outliner edge cases and error handling"
    (let [store (atom outliner-atom-store)]

      ;; Test: Try to indent first block (should be no-op)
      (swap! store assoc-in [:present :view :selection] ["p1"])
      (when-let [indent-fn (get intents/intents :indent-block)]
        (let [before-state (:present @store)]
          (dispatcher/dispatch-intent! store :indent-block {})
          (let [after-state (:present @store)
                root-children-before (get-in before-state [:children-by-parent "root"])
                root-children-after (get-in after-state [:children-by-parent "root"])]
            (is (= root-children-before root-children-after) "First block indent should be no-op"))))

      ;; Test: Try to outdent root-level block (should be no-op)
      (when-let [outdent-fn (get intents/intents :outdent-block)]
        (let [before-state (:present @store)]
          (dispatcher/dispatch-intent! store :outdent-block {})
          (let [after-state (:present @store)]
            (is (= before-state after-state) "Root-level outdent should be no-op"))))

      ;; Test: Operations on non-existent nodes
      (swap! store assoc-in [:present :view :selection] ["non-existent"])
      (when-let [delete-fn (get intents/intents :delete-block)]
        (let [before-count (count (get-in @store [:present :nodes]))]
          (dispatcher/dispatch-intent! store :delete-block {})
          (let [after-count (count (get-in @store [:present :nodes]))]
            (is (= before-count after-count) "Operations on non-existent nodes should be safe")))))))

;; Run all outliner user story tests
(defn run-all-outliner-tests []
  (println "🧪 Running Outliner User Story Tests...")
  (run-tests 'outliner-user-stories-test))

;; Auto-run tests when namespace loads
(run-tests)