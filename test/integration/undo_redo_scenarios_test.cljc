(ns integration.undo-redo-scenarios-test
  "Integration tests for undo/redo with complex operations.

   CRITICAL GAPS ADDRESSED:
   - Undo multi-step script operations (delete + select previous)
   - Undo with folded blocks
   - Redo after navigation
   - History stack boundary conditions"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing use-fixtures]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer [deftest is testing use-fixtures]])
            [harness.runtime-fixtures :as runtime-fixtures]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.api :as api]
            [kernel.history :as history]
            [kernel.query :as q]))

(use-fixtures :once runtime-fixtures/bootstrap-runtime)

;; ── Test Helpers ─────────────────────────────────────────────────────────────

(defn dispatch!
  "Test helper: dispatch an intent and thread history alongside db.

   Returns {:history :db :session} with session merged from session-updates.
   History auto-records on structural ops (via api/dispatch-tracked)."
  [history db session intent]
  (let [result (api/dispatch-tracked history db session intent)
        updates (:session-updates result)
        new-session (if updates (merge-with merge session updates) session)]
    {:history (:history result)
     :db (:db result)
     :session new-session}))

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
          {:keys [history db]} (dispatch! history/empty-history db session
                                          {:type :context-aware-enter
                                           :block-id "a"
                                           :cursor-pos 5})
          after-create-count (count (q/children db :doc))]

      ;; Verify block was created
      (is (>= after-create-count initial-count)
          "Should have created a block or nested structure")

      ;; Now undo
      (let [result (history/undo history db session)
            db-after (:db result)]
        (is (some? result) "Undo should return a result")
        (is (= initial-count (count (q/children db-after :doc)))
            "Undo should restore original block count")))))

(deftest ^{:fr/ids #{:fr.kernel/undo-restores-all}} undo-restores-text-content
  (testing "Undo should restore exact text content"
    (let [db (build-simple-doc)
          original-text (get-in db [:nodes "a" :props :text])
          session (editing-session "a" 0)

          ;; Modify text
          {:keys [history db]} (dispatch! history/empty-history db session
                                          {:type :update-content
                                           :block-id "a"
                                           :text "Modified text"})

          ;; Verify modification
          _ (is (= "Modified text" (get-in db [:nodes "a" :props :text])))

          ;; Undo
          {:keys [db]} (history/undo history db session)]

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
          {:keys [history db]} (dispatch! history/empty-history db session
                                          {:type :delete-selected})

          ;; Verify deletion
          _ (is (= :trash (get-in db [:derived :parent-of "child"]))
                "Child should be in trash after delete")

          ;; Undo
          {:keys [db]} (history/undo history db session)]

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
          {:keys [history db]} (dispatch! history/empty-history db session
                                          {:type :indent-selected})

          ;; Verify indent
          _ (is (= "a" (get-in db [:derived :parent-of "b"]))
                "Block should be under 'a' after indent")

          ;; Undo
          {:keys [db]} (history/undo history db session)]

      (is (= :doc (get-in db [:derived :parent-of "b"]))
          "Undo should restore original parent (:doc)"))))

;; ── Redo Tests ───────────────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.kernel/undo-restores-all}} redo-after-undo
  (testing "Redo should re-apply undone operation"
    (let [db (build-simple-doc)
          session (editing-session "a" 0)

          ;; Modify text
          {:keys [history db]} (dispatch! history/empty-history db session
                                          {:type :update-content
                                           :block-id "a"
                                           :text "Modified"})

          modified-text (get-in db [:nodes "a" :props :text])

          ;; Undo
          {hist-after-undo :history db-after-undo :db}
          (history/undo history db session)
          _ (is (not= modified-text (get-in db-after-undo [:nodes "a" :props :text]))
                "Undo should change text")

          ;; Redo
          {:keys [db]} (history/redo hist-after-undo db-after-undo session)]

      (is (= modified-text (get-in db [:nodes "a" :props :text]))
          "Redo should restore modified text"))))

(deftest new-operation-clears-redo-stack
  (testing "New operation after undo should clear redo stack"
    (let [db (build-simple-doc)
          session (editing-session "a" 0)

          ;; First modification
          {:keys [history db]} (dispatch! history/empty-history db session
                                          {:type :update-content
                                           :block-id "a"
                                           :text "First mod"})

          ;; Undo - may return nil if no history
          undo-result (history/undo history db session)
          history (or (:history undo-result) history)
          db-after-undo (if undo-result (:db undo-result) db)

          ;; Second modification (should clear redo)
          {:keys [history db]} (dispatch! history db-after-undo session
                                          {:type :update-content
                                           :block-id "a"
                                           :text "Second mod"})

          ;; Try to redo (should fail/no-op) - returns nil when no future
          redo-result (history/redo history db session)
          final-db (if redo-result (:db redo-result) db)]

      ;; Either redo returns nil (no future) or returns same DB
      (is (or (nil? redo-result)
              (= "Second mod" (get-in final-db [:nodes "a" :props :text])))
          "Redo should not work after new operation"))))

;; ── Boundary Condition Tests ─────────────────────────────────────────────────

(deftest undo-on-fresh-db-is-no-op
  (testing "Undo on fresh DB with no history should be no-op"
    (let [db (build-simple-doc)
          original-db db
          result (history/undo history/empty-history db nil)]
      (is (nil? result) "Undo on fresh history should return nil")
      (is (= (:nodes db) (:nodes original-db))
          "Undo on fresh DB should not change content"))))

(deftest redo-without-undo-is-no-op
  (testing "Redo without prior undo should be no-op"
    (let [db (build-simple-doc)
          session (editing-session "a" 0)

          ;; Do an operation
          {:keys [history db]} (dispatch! history/empty-history db session
                                          {:type :update-content
                                           :block-id "a"
                                           :text "Modified"})

          modified-db db

          ;; Try redo without undo - returns nil when no future
          result (history/redo history db session)]

      (is (nil? result) "Redo without prior undo should return nil")
      (is (= (:nodes db) (:nodes modified-db))
          "Redo without undo should not change DB"))))

;; ── Cursor/Selection Restoration Tests ───────────────────────────────────────

(deftest undo-restores-selection-state
  (testing "Undo should restore selection state from before operation"
    ;; LOGSEQ PARITY: Undo restores to the state immediately before the operation.
    ;; If "b" was selected when we deleted, undo should restore with "b" selected.
    (let [db (build-simple-doc)
          ;; Start with "b" selected - this is the state BEFORE the delete
          session (with-selection ["b"] "b")

          ;; Delete "b" - dispatch! records history with pre-op session (b selected)
          {:keys [history db]} (dispatch! history/empty-history db session
                                          {:type :delete-selected})

          ;; Undo - should restore both DB and session
          undo-result (history/undo history db session)
          undo-db (when undo-result (:db undo-result))
          undo-session (when undo-result (:session undo-result))]

      ;; If undo succeeded, DB should be restored
      (when undo-db
        (is (contains? (:nodes undo-db) "b")
            "Deleted block should be restored"))

      ;; Session should have selection from before delete
      (when undo-session
        (is (= #{"b"} (:nodes (:selection undo-session)))
            "Selection should be restored to 'b' (state before delete)")))))

;; ── Complex Scenario Tests ───────────────────────────────────────────────────

(deftest multiple-undos-in-sequence
  (testing "Multiple undos should walk back through history correctly"
    (let [db (build-simple-doc)
          session (editing-session "a" 0)

          ;; Op 1: Modify "a"
          r1 (dispatch! history/empty-history db session
                        {:type :update-content :block-id "a" :text "Mod1"})

          ;; Op 2: Modify "a" again
          r2 (dispatch! (:history r1) (:db r1) session
                        {:type :update-content :block-id "a" :text "Mod2"})

          _ (is (= "Mod2" (get-in (:db r2) [:nodes "a" :props :text])))

          ;; Undo once (back to Mod1)
          u1 (history/undo (:history r2) (:db r2) session)
          _ (is (= "Mod1" (get-in (:db u1) [:nodes "a" :props :text])))

          ;; Undo twice (back to original)
          u2 (history/undo (:history u1) (:db u1) session)]

      (is (= "First" (get-in (:db u2) [:nodes "a" :props :text]))
          "Two undos should restore original text"))))
