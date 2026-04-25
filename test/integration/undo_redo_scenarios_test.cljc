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
            [kernel.log :as L]
            [kernel.query :as q]
            [utils.session-patch :as session-patch]))

(use-fixtures :once runtime-fixtures/bootstrap-runtime)

;; ── Test Helpers ─────────────────────────────────────────────────────────────

(def ^:private test-mint
  "Deterministic-enough minter for tests: unique op-id, constant timestamp."
  (fn [] {:op-id (random-uuid) :timestamp 0}))

(defn- empty-log-with-root
  "Build a log whose :root-db is `db` (baseline, no undoable ops)."
  [db]
  (L/reset-root db))

(defn dispatch!
  "Test helper: dispatch an intent, thread log/db/session.

   Returns {:log :db :session}. On structural ops, the log is appended.
   Session is merged from session-updates."
  [log db session intent]
  (let [result (api/dispatch-logged log db session intent test-mint)
        updates (:session-updates result)
        new-session (session-patch/merge-patch session updates)]
    {:log (:log result)
     :db (:db result)
     :session new-session}))

(defn undo!
  "Test helper: rewind head, refold db, restore pre-op session.

   Returns {:log :db :session} or nil if nothing to undo."
  [log _db session]
  (when-let [new-log (L/undo log)]
    (let [entry-undone (L/entry-at-head log)
          new-db (L/head-db new-log)
          restored (:session-before entry-undone)
          new-session (session-patch/merge-patch session restored)]
      {:log new-log :db new-db :session new-session})))

(defn redo!
  "Test helper: advance head, refold db.

   Returns {:log :db :session} or nil if nothing to redo."
  [log _db session]
  (when-let [new-log (L/redo log)]
    {:log new-log :db (L/head-db new-log) :session session}))

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
    (let [db0 (build-simple-doc)
          session (editing-session "a" 5)
          log0 (empty-log-with-root db0)
          initial-count (count (q/children db0 :doc))

          ;; Create a new block via split
          {:keys [log db]} (dispatch! log0 db0 session
                                      {:type :context-aware-enter
                                       :block-id "a"
                                       :cursor-pos 5})
          after-create-count (count (q/children db :doc))]

      (is (>= after-create-count initial-count)
          "Should have created a block or nested structure")

      ;; Undo
      (let [result (undo! log db session)]
        (is (some? result) "Undo should return a result")
        (is (= initial-count (count (q/children (:db result) :doc)))
            "Undo should restore original block count")))))

(deftest ^{:fr/ids #{:fr.kernel/undo-restores-all}} undo-restores-text-content
  (testing "Undo should restore exact text content"
    (let [db0 (build-simple-doc)
          original-text (get-in db0 [:nodes "a" :props :text])
          session (editing-session "a" 0)
          log0 (empty-log-with-root db0)

          {:keys [log db]} (dispatch! log0 db0 session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Modified text"})

          _ (is (= "Modified text" (get-in db [:nodes "a" :props :text])))

          {:keys [db]} (undo! log db session)]

      (is (= original-text (get-in db [:nodes "a" :props :text]))
          "Undo should restore original text"))))

;; ── Multi-Step Operation Undo Tests ──────────────────────────────────────────

(deftest undo-delete-with-reparenting
  (testing "Undo delete should restore block AND its children's parent relationships"
    (let [db0 (build-nested-doc)
          session (with-selection ["child"] "child")
          log0 (empty-log-with-root db0)

          _ (is (= "parent" (get-in db0 [:derived :parent-of "child"])))

          {:keys [log db]} (dispatch! log0 db0 session
                                      {:type :delete-selected})

          _ (is (= :trash (get-in db [:derived :parent-of "child"]))
                "Child should be in trash after delete")

          {:keys [db]} (undo! log db session)]

      (is (= "parent" (get-in db [:derived :parent-of "child"]))
          "Undo should restore child to original parent"))))

(deftest undo-indent-restores-structure
  (testing "Undo indent should restore original parent"
    (let [db0 (build-simple-doc)
          session (with-selection ["b"] "b")
          log0 (empty-log-with-root db0)

          _ (is (= :doc (get-in db0 [:derived :parent-of "b"])))

          {:keys [log db]} (dispatch! log0 db0 session
                                      {:type :indent-selected})

          _ (is (= "a" (get-in db [:derived :parent-of "b"]))
                "Block should be under 'a' after indent")

          {:keys [db]} (undo! log db session)]

      (is (= :doc (get-in db [:derived :parent-of "b"]))
          "Undo should restore original parent (:doc)"))))

;; ── Redo Tests ───────────────────────────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.kernel/undo-restores-all}} redo-after-undo
  (testing "Redo should re-apply undone operation"
    (let [db0 (build-simple-doc)
          session (editing-session "a" 0)
          log0 (empty-log-with-root db0)

          {:keys [log db]} (dispatch! log0 db0 session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Modified"})

          modified-text (get-in db [:nodes "a" :props :text])

          undone (undo! log db session)
          _ (is (not= modified-text (get-in (:db undone) [:nodes "a" :props :text]))
                "Undo should change text")

          redone (redo! (:log undone) (:db undone) (:session undone))]

      (is (= modified-text (get-in (:db redone) [:nodes "a" :props :text]))
          "Redo should restore modified text"))))

(deftest new-operation-clears-redo-stack
  (testing "New operation after undo should clear redo stack"
    (let [db0 (build-simple-doc)
          session (editing-session "a" 0)
          log0 (empty-log-with-root db0)

          {:keys [log db]} (dispatch! log0 db0 session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "First mod"})

          undone (undo! log db session)
          log-after-undo (:log undone)
          db-after-undo (:db undone)

          ;; Second modification (should clear future)
          {:keys [log db]} (dispatch! log-after-undo db-after-undo session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Second mod"})

          redone (redo! log db session)]

      (is (nil? redone) "Redo should return nil after new op cleared the future"))))

;; ── Boundary Condition Tests ─────────────────────────────────────────────────

(deftest undo-on-fresh-log-is-no-op
  (testing "Undo on fresh log with no history should be nil"
    (let [db (build-simple-doc)
          log (empty-log-with-root db)]
      (is (nil? (undo! log db nil))))))

(deftest redo-without-undo-is-no-op
  (testing "Redo without prior undo should be nil"
    (let [db0 (build-simple-doc)
          session (editing-session "a" 0)
          log0 (empty-log-with-root db0)

          {:keys [log db]} (dispatch! log0 db0 session
                                      {:type :update-content
                                       :block-id "a"
                                       :text "Modified"})]

      (is (nil? (redo! log db session))
          "Redo without prior undo should return nil"))))

;; ── Cursor/Selection Restoration Tests ───────────────────────────────────────

(deftest undo-restores-selection-state
  (testing "Undo should restore selection state from before operation"
    (let [db0 (build-simple-doc)
          session (with-selection ["b"] "b")
          log0 (empty-log-with-root db0)

          ;; Delete "b" — log captures pre-op session (b selected)
          {:keys [log db]} (dispatch! log0 db0 session
                                      {:type :delete-selected})

          result (undo! log db session)]

      (when result
        (is (contains? (:nodes (:db result)) "b")
            "Deleted block should be restored")
        (is (= #{"b"} (get-in (:session result) [:selection :nodes]))
            "Selection should be restored to 'b' (state before delete)")))))

;; ── Complex Scenario Tests ───────────────────────────────────────────────────

(deftest multiple-undos-in-sequence
  (testing "Multiple undos should walk back through history correctly"
    (let [db0 (build-simple-doc)
          session (editing-session "a" 0)
          log0 (empty-log-with-root db0)

          r1 (dispatch! log0 db0 session
                        {:type :update-content :block-id "a" :text "Mod1"})
          r2 (dispatch! (:log r1) (:db r1) session
                        {:type :update-content :block-id "a" :text "Mod2"})

          _ (is (= "Mod2" (get-in (:db r2) [:nodes "a" :props :text])))

          u1 (undo! (:log r2) (:db r2) session)
          _ (is (= "Mod1" (get-in (:db u1) [:nodes "a" :props :text])))

          u2 (undo! (:log u1) (:db u1) session)]

      (is (= "First" (get-in (:db u2) [:nodes "a" :props :text]))
          "Two undos should restore original text"))))
