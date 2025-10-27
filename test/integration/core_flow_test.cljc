(ns integration.core-flow-test
  "Integration tests for core flows: selection, navigation, structural edits, history.

   Scenario-level tests that verify refactor-proof behaviors across the system."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as DB]
            [kernel.transaction :as tx]
            [kernel.api :as api]
            [kernel.query :as q]
            [kernel.constants :as const]
            [kernel.history :as H]))

(defn demo-db
  "Create a demo database with simple page + blocks structure."
  []
  (-> (DB/empty-db)
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
  (testing "Selection changes are recorded in history"
    (let [db0 (demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "a"})
          {:keys [db]} (api/dispatch db {:type :navigate-down})]
      (is (= "b" (q/focus db))
          "Navigate-down should move focus to next sibling")
      (is (H/can-undo? db)
          "Selection changes should be recorded in history"))))

(deftest ephemeral-does-not-hit-history
  (testing "Ephemeral UI updates don't trigger history"
    (let [db0 (demo-db)
          undo0 (count (:past (:history db0)))
          {:keys [db]} (api/dispatch db0 {:type :enter-edit :block-id "a"})
          undo1 (count (:past (:history db)))]
      (is (= undo0 undo1)
          "Ephemeral ops (edit mode) should not add history entries"))))

(deftest delete-moves-to-trash
  (testing "Delete moves nodes to trash (archive by design)"
    (let [db0 (demo-db)
          {:keys [db]} (api/dispatch db0 {:type :delete :id "b"})]
      (is (some #{"b"} (get-in db [:children-by-parent const/root-trash]))
          "Deleted node should be in :trash")
      (is (not (some #{"b"} (q/children db "page")))
          "Deleted node should not be in original parent"))))

(deftest move-anchor-canon-in-interpreter
  (testing "Anchor normalization happens in interpreter, not intent layer"
    (let [db0 (demo-db)
          ;; Use :at-start anchor (should be canonicalized to :first)
          {:keys [db]} (api/dispatch db0 {:type :move
                                          :selection ["c"]
                                          :parent "page"
                                          :anchor :at-start})]
      (is (= ["c" "a" "b"] (q/children db "page"))
          ":at-start should be canonicalized to :first by interpreter"))))

(deftest undo-redo-roundtrip
  (testing "Undo/redo preserves state correctly"
    (let [db0 (demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "a"})
          {:keys [db]} (api/dispatch db {:type :selection :mode :replace :ids "b"})
          db-undone (H/undo db)
          db-redone (H/redo db-undone)]
      (is (= "a" (q/focus db-undone))
          "Undo should restore previous selection")
      (is (= "b" (q/focus db-redone))
          "Redo should restore forward state")
      (is (= (q/focus db) (q/focus db-redone))
          "Redo should match original state"))))

(deftest multi-select-move
  (testing "Multi-select move with :move intent"
    (let [db0 (demo-db)
          ;; Move both a and c after b (reorder)
          {:keys [db]} (api/dispatch db0 {:type :move
                                          :selection ["a" "c"]
                                          :parent "page"
                                          :anchor {:after "b"}})]
      (is (= ["b" "a" "c"] (q/children db "page"))
          "Selection should move to anchor position preserving order"))))

(deftest structural-edit-with-selection
  (testing "Indent works on selected node"
    (let [db0 (demo-db)
          {:keys [db]} (api/dispatch db0 {:type :selection :mode :replace :ids "b"})
          {:keys [db]} (api/dispatch db {:type :indent :id "b"})]
      (is (= "a" (q/parent-of db "b"))
          "Indent should move b under its previous sibling a")
      (is (= ["b"] (q/children db "a"))
          "b should be child of a"))))
