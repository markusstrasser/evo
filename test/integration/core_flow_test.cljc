(ns integration.core-flow-test
  "Integration tests for core flows: selection, navigation, structural edits, history.

   Scenario-level tests that verify refactor-proof behaviors across the system."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.api :as api]
            [kernel.query :as q]
            [kernel.constants :as const]
            [kernel.history :as H]))

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

(defn demo-db
  "Create a demo database with simple page + blocks structure."
  []
  (-> (db/empty-db)
      (tx/interpret [{:op :create-node :id "page" :type :page :props {:title "P"}}
                     {:op :place :id "page" :under :doc :at :last}
                     {:op :create-node :id "a" :type :block :props {:text "A"}}
                     {:op :place :id "a" :under "page" :at :last}
                     {:op :create-node :id "b" :type :block :props {:text "B"}}
                     {:op :place :id "b" :under "page" :at :last}
                     {:op :create-node :id "c" :type :block :props {:text "C"}}
                     {:op :place :id "c" :under "page" :at :last}])
      :db
      (H/record)))

(deftest selection-and-navigation
  (testing "Selection changes work through session-updates"
    (let [db0 (demo-db)
          ;; First selection returns session-updates (not ops)
          {:keys [session-updates]} (api/dispatch db0 nil {:type :selection :mode :replace :ids "a"})
          session1 session-updates
          ;; Navigate to next using the current session state
          {:keys [session-updates]} (api/dispatch db0 session1 {:type :selection :mode :next})
          focus (get-in session-updates [:selection :focus])]
      (is (= "b" focus)
          "Navigate to next sibling should move focus"))))

(deftest ephemeral-does-not-hit-history
  (testing "Ephemeral UI updates don't trigger history"
    (let [db0 (demo-db)
          undo0 (count (:past (:history db0)))
          {:keys [db]} (api/dispatch db0 nil {:type :enter-edit :block-id "a"})
          undo1 (count (:past (:history db)))]
      (is (= undo0 undo1)
          "Ephemeral ops (edit mode) should not add history entries"))))

(deftest delete-moves-to-trash
  (testing "Delete moves nodes to trash (archive by design)"
    (let [db0 (demo-db)
          ;; :delete intent requires editing state
          session (-> (empty-session)
                      (assoc-in [:ui :editing-block-id] "b"))
          {:keys [db]} (api/dispatch db0 session {:type :delete :id "b"})]
      (is (some #{"b"} (get-in db [:children-by-parent const/root-trash]))
          "Deleted node should be in :trash")
      (is (not (some #{"b"} (q/children db "page")))
          "Deleted node should not be in original parent"))))

(deftest move-anchor-canon-in-interpreter
  (testing "Anchor normalization happens in interpreter, not intent layer"
    (let [db0 (demo-db)
          ;; Use :at-start anchor (should be canonicalized to :first)
          {:keys [db]} (api/dispatch db0 nil {:type :move
                                              :selection ["c"]
                                              :parent "page"
                                              :anchor :at-start})]
      (is (= ["c" "a" "b"] (q/children db "page"))
          ":at-start should be canonicalized to :first by interpreter"))))

(deftest undo-redo-roundtrip
  (testing "Undo/redo preserves state correctly with structural changes"
    ;; Selection operations are ephemeral (don't enter history).
    ;; Test undo/redo with actual structural changes (text updates, moves, etc.)
    (let [db0 (demo-db)
          ;; Structural change 1: update text of block "a"
          {:keys [db]} (api/dispatch db0 nil {:type :update-content :block-id "a" :text "Updated A"})
          ;; Structural change 2: update text of block "b"
          {:keys [db]} (api/dispatch db nil {:type :update-content :block-id "b" :text "Updated B"})
          ;; Undo/redo now return {:db ... :session ...}
          db-undone (:db (H/undo db))
          db-redone (:db (H/redo db-undone))]
      (is (= "Updated A" (get-in db-undone [:nodes "a" :props :text]))
          "Undo should restore state before second update (first update still applied)")
      (is (= "B" (get-in db-undone [:nodes "b" :props :text]))
          "Undo should restore original text of block b")
      (is (= "Updated B" (get-in db-redone [:nodes "b" :props :text]))
          "Redo should restore forward state")
      (is (= (get-in db [:nodes "b" :props :text]) (get-in db-redone [:nodes "b" :props :text]))
          "Redo should match original state"))))

(deftest multi-select-move
  (testing "Multi-select move with :move intent"
    (let [db0 (demo-db)
          ;; Move both a and c after b (reorder)
          {:keys [db]} (api/dispatch db0 nil {:type :move
                                              :selection ["a" "c"]
                                              :parent "page"
                                              :anchor {:after "b"}})]
      (is (= ["b" "a" "c"] (q/children db "page"))
          "Selection should move to anchor position preserving order"))))

(deftest structural-edit-with-selection
  (testing "Indent works on selected node"
    (let [db0 (demo-db)
          ;; Selection returns session-updates, but indent operates on db directly
          {:keys [db]} (api/dispatch db0 nil {:type :indent :id "b"})]
      (is (= "a" (q/parent-of db "b"))
          "Indent should move b under its previous sibling a")
      (is (= ["b"] (q/children db "a"))
          "b should be child of a"))))
