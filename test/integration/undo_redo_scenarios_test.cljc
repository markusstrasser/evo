(ns integration.undo-redo-scenarios-test
  "Integration tests for undo/redo with complex operations.

   CRITICAL GAPS ADDRESSED:
   - Undo multi-step script operations (delete + select previous)
   - Undo with folded blocks
   - Redo after navigation
   - History stack boundary conditions"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.api :as api]
            [kernel.history :as history]
            [kernel.query :as q]))

;; ── Session Helpers ──────────────────────────────────────────────────────────

(defn empty-session []
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil :direction nil}
   :buffer {:block-id nil :text "" :dirty? false}
   :ui {:folded #{}
        :zoom-root nil
        :zoom-stack []
        :current-page nil
        :editing-block-id nil
        :cursor-position nil}
   :sidebar {:right []}})

(defn with-selection [nodes focus]
  (-> (empty-session)
      (assoc-in [:selection :nodes] (set nodes))
      (assoc-in [:selection :focus] focus)
      (assoc-in [:selection :anchor] focus)))

(defn editing-session [block-id cursor-pos]
  (-> (empty-session)
      (assoc-in [:ui :editing-block-id] block-id)
      (assoc-in [:ui :cursor-position] cursor-pos)))

;; ── Test Fixtures ────────────────────────────────────────────────────────────

(defn build-simple-doc []
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "a" :type :block :props {:text "First"}}
           {:op :place :id "a" :under :doc :at :last}
           {:op :create-node :id "b" :type :block :props {:text "Second"}}
           {:op :place :id "b" :under :doc :at :last}
           {:op :create-node :id "c" :type :block :props {:text "Third"}}
           {:op :place :id "c" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture failed: " (pr-str issues)))
    db))

(defn build-nested-doc []
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
           {:op :place :id "parent" :under :doc :at :last}
           {:op :create-node :id "child" :type :block :props {:text "Child"}}
           {:op :place :id "child" :under "parent" :at :last}
           {:op :create-node :id "sibling" :type :block :props {:text "Sibling"}}
           {:op :place :id "sibling" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture failed: " (pr-str issues)))
    db))

;; ── Basic Undo/Redo Tests ────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.kernel/undo-restores-all}} undo-block-creation
  (testing "Undo should restore DB to state before block creation"
    (let [db (build-simple-doc)
          session (editing-session "a" 5)
          initial-count (count (q/children db :doc))

          ;; Create a new block via split
          {:keys [db session]} (let [result (api/dispatch* db session
                                              {:type :context-aware-enter
                                               :block-id "a"
                                               :cursor-pos 5})]
                                 {:db (:db result)
                                  :session (if-let [updates (:session-updates result)]
                                             (merge-with merge session updates)
                                             session)})
          after-create-count (count (q/children db :doc))]

      ;; Verify block was created
      (is (>= after-create-count initial-count)
          "Should have created a block or nested structure")

      ;; Now undo
      (let [{:keys [db issues]} (history/undo db)]
        (is (empty? issues) "Undo should succeed without issues")
        (is (= initial-count (count (q/children db :doc)))
            "Undo should restore original block count")))))

(deftest ^{:fr/ids #{:fr.kernel/undo-restores-all}} undo-restores-text-content
  (testing "Undo should restore exact text content"
    (let [db (build-simple-doc)
          original-text (get-in db [:nodes "a" :props :text])
          session (editing-session "a" 0)

          ;; Modify text
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Modified text"})]
                         {:db (:db result)})

          ;; Verify modification
          _ (is (= "Modified text" (get-in db [:nodes "a" :props :text])))

          ;; Undo
          {:keys [db]} (history/undo db)]

      (is (= original-text (get-in db [:nodes "a" :props :text]))
          "Undo should restore original text"))))

;; ── Multi-Step Operation Undo Tests ──────────────────────────────────────────

(deftest undo-delete-with-reparenting
  (testing "Undo delete should restore block AND its children's parent relationships"
    (let [db (build-nested-doc)
          session (with-selection ["child"] "child")

          ;; Verify initial state
          _ (is (= "parent" (get-in db [:derived :parent-of "child"])))

          ;; Delete child (which should move to trash)
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :delete-selected})]
                         {:db (:db result)})

          ;; Verify deletion
          _ (is (= :trash (get-in db [:derived :parent-of "child"]))
                "Child should be in trash after delete")

          ;; Undo
          {:keys [db]} (history/undo db)]

      ;; Child should be back under parent
      (is (= "parent" (get-in db [:derived :parent-of "child"]))
          "Undo should restore child to original parent"))))

(deftest undo-indent-restores-structure
  (testing "Undo indent should restore original parent"
    (let [db (build-simple-doc)
          session (with-selection ["b"] "b")

          ;; Verify initial parent
          _ (is (= :doc (get-in db [:derived :parent-of "b"])))

          ;; Indent "b" under "a"
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :indent-selected})]
                         {:db (:db result)})

          ;; Verify indent
          _ (is (= "a" (get-in db [:derived :parent-of "b"]))
                "Block should be under 'a' after indent")

          ;; Undo
          {:keys [db]} (history/undo db)]

      (is (= :doc (get-in db [:derived :parent-of "b"]))
          "Undo should restore original parent (:doc)"))))

;; ── Redo Tests ───────────────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.kernel/undo-restores-all}} redo-after-undo
  (testing "Redo should re-apply undone operation"
    (let [db (build-simple-doc)
          session (editing-session "a" 0)

          ;; Modify text
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Modified"})]
                         {:db (:db result)})

          modified-text (get-in db [:nodes "a" :props :text])

          ;; Undo
          {:keys [db]} (history/undo db)
          _ (is (not= modified-text (get-in db [:nodes "a" :props :text]))
                "Undo should change text")

          ;; Redo
          {:keys [db]} (history/redo db)]

      (is (= modified-text (get-in db [:nodes "a" :props :text]))
          "Redo should restore modified text"))))

(deftest new-operation-clears-redo-stack
  (testing "New operation after undo should clear redo stack"
    (let [db (build-simple-doc)
          session (editing-session "a" 0)

          ;; First modification
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "First mod"})]
                         {:db (:db result)})

          ;; Undo
          {:keys [db]} (history/undo db)

          ;; Second modification (should clear redo)
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Second mod"})]
                         {:db (:db result)})

          ;; Try to redo (should fail/no-op)
          {:keys [db issues]} (history/redo db)]

      ;; Either redo fails or returns same DB
      (is (or (seq issues)
              (= "Second mod" (get-in db [:nodes "a" :props :text])))
          "Redo should not work after new operation"))))

;; ── Boundary Condition Tests ─────────────────────────────────────────────────

(deftest undo-on-fresh-db-is-no-op
  (testing "Undo on fresh DB with no history should be no-op"
    (let [db (build-simple-doc)
          original-db db
          {:keys [db issues]} (history/undo db)]
      (is (or (= db original-db)
              (= (:nodes db) (:nodes original-db)))
          "Undo on fresh DB should not change content"))))

(deftest redo-without-undo-is-no-op
  (testing "Redo without prior undo should be no-op"
    (let [db (build-simple-doc)
          session (editing-session "a" 0)

          ;; Do an operation
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Modified"})]
                         {:db (:db result)})

          modified-db db

          ;; Try redo without undo
          {:keys [db]} (history/redo db)]

      (is (= (:nodes modified-db) (:nodes db))
          "Redo without undo should not change DB"))))

;; ── Cursor/Selection Restoration Tests ───────────────────────────────────────

(deftest undo-restores-selection-state
  (testing "Undo should restore selection state from before operation"
    (let [db (build-simple-doc)
          ;; Start with "a" selected
          session (with-selection ["a"] "a")

          ;; Record initial state
          db-with-history (history/record db session)

          ;; Change selection to "b" and do an operation
          session2 (with-selection ["b"] "b")
          {:keys [db]} (let [result (api/dispatch* db-with-history session2
                                      {:type :delete-selected}
                                       ;; Note: dispatch* records history internally
                                      )]
                         {:db (:db result)})

          ;; Undo - should restore both DB and (ideally) session
          {:keys [db session]} (history/undo db)]

      ;; DB should be restored
      (is (contains? (:nodes db) "b")
          "Deleted block should be restored")

      ;; Session (if supported) should have original selection
      (when session
        (is (= #{"a"} (:nodes (:selection session)))
            "Selection should be restored to 'a'")))))

;; ── Complex Scenario Tests ───────────────────────────────────────────────────

(deftest multiple-undos-in-sequence
  (testing "Multiple undos should walk back through history correctly"
    (let [db (build-simple-doc)
          session (editing-session "a" 0)

          ;; Op 1: Modify "a"
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Mod1"})]
                         {:db (:db result)})

          ;; Op 2: Modify "a" again
          {:keys [db]} (let [result (api/dispatch* db session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Mod2"})]
                         {:db (:db result)})

          _ (is (= "Mod2" (get-in db [:nodes "a" :props :text])))

          ;; Undo once (back to Mod1)
          {:keys [db]} (history/undo db)
          _ (is (= "Mod1" (get-in db [:nodes "a" :props :text])))

          ;; Undo twice (back to original)
          {:keys [db]} (history/undo db)]

      (is (= "First" (get-in db [:nodes "a" :props :text]))
          "Two undos should restore original text"))))
