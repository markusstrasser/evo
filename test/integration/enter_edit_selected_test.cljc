(ns integration.enter-edit-selected-test
  "Integration tests for Enter key in selection mode (enter-edit-selected intent).

   CRITICAL BEHAVIOR: When a block is selected (not editing), pressing Enter should:
   1. Enter edit mode on the focused block
   2. Clear the selection
   3. Position cursor at end of text (default) or start if cursor-at: :start

   This is Logseq parity: Enter = edit selected block, NOT create new block."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing use-fixtures]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer [deftest is testing use-fixtures]])
            [harness.runtime-fixtures :as runtime-fixtures]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            ;; REQUIRED: Load plugin to register :enter-edit-selected intent
            [plugins.editing]))

(use-fixtures :once runtime-fixtures/bootstrap-runtime)

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

(defn with-selection
  "Create session with block selected (non-editing state)."
  [nodes focus & [anchor direction]]
  (-> (empty-session)
      (assoc-in [:selection :nodes] (set nodes))
      (assoc-in [:selection :focus] focus)
      (assoc-in [:selection :anchor] (or anchor focus))
      (assoc-in [:selection :direction] direction)))

(defn apply-session-updates [session updates]
  (if updates
    ;; Deep merge for nested maps, replace for scalars
    (reduce-kv
     (fn [s k v]
       (if (and (map? (get s k)) (map? v))
         (update s k merge v)
         (assoc s k v)))
     session
     updates)
    session))

(defn run-intent [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (if (seq ops) (:db (tx/interpret db ops)) db)
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session :ops ops :session-updates session-updates}))

;; ── Test Fixtures ────────────────────────────────────────────────────────────

(defn build-simple-doc []
  "Create db with three blocks: [a, b, c]"
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "a" :type :block :props {:text "First block"}}
           {:op :place :id "a" :under :doc :at :last}
           {:op :create-node :id "b" :type :block :props {:text "Second block"}}
           {:op :place :id "b" :under :doc :at :last}
           {:op :create-node :id "c" :type :block :props {:text "Third block"}}
           {:op :place :id "c" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture failed: " (pr-str issues)))
    db))

(defn build-empty-block-doc []
  "Create db with an empty block."
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "empty" :type :block :props {:text ""}}
           {:op :place :id "empty" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture failed: " (pr-str issues)))
    db))

;; ── Core Enter-Edit-Selected Tests ──────────────────────────────────────────

(deftest ^{:fr/ids #{:fr.selection/edit-view-exclusive}}
  enter-edit-selected-sets-editing-block
  (testing "Enter in selection mode sets editing-block-id to focused block"
    (let [db (build-simple-doc)
          session (with-selection #{"b"} "b")
          {:keys [session session-updates]} (run-intent db session
                                              {:type :enter-edit-selected})]
      ;; Should have session-updates
      (is (some? session-updates)
          "Intent should return session-updates")

      ;; Editing block should be set
      (is (= "b" (get-in session [:ui :editing-block-id]))
          "editing-block-id should be set to focused block")

      ;; Selection should be cleared
      (is (empty? (get-in session [:selection :nodes]))
          "Selection nodes should be cleared")
      (is (nil? (get-in session [:selection :focus]))
          "Selection focus should be cleared")
      (is (nil? (get-in session [:selection :anchor]))
          "Selection anchor should be cleared"))))

(deftest ^{:fr/ids #{:fr.selection/edit-view-exclusive}}
  enter-edit-selected-cursor-at-end-by-default
  (testing "Enter positions cursor at end of text by default"
    (let [db (build-simple-doc)
          session (with-selection #{"b"} "b")
          {:keys [session]} (run-intent db session
                              {:type :enter-edit-selected})]
      ;; Cursor should be at end of "Second block" (12 chars)
      (is (= 12 (get-in session [:ui :cursor-position]))
          "Cursor should be at end of text"))))

(deftest ^{:fr/ids #{:fr.selection/edit-view-exclusive}}
  enter-edit-selected-cursor-at-start
  (testing "Left arrow variant positions cursor at start"
    (let [db (build-simple-doc)
          session (with-selection #{"b"} "b")
          {:keys [session]} (run-intent db session
                              {:type :enter-edit-selected :cursor-at :start})]
      ;; Cursor should be at start
      (is (= 0 (get-in session [:ui :cursor-position]))
          "Cursor should be at start of text"))))

(deftest enter-edit-selected-with-empty-block
  (testing "Enter on empty block positions cursor at 0"
    (let [db (build-empty-block-doc)
          session (with-selection #{"empty"} "empty")
          {:keys [session]} (run-intent db session
                              {:type :enter-edit-selected})]
      ;; Should enter edit mode
      (is (= "empty" (get-in session [:ui :editing-block-id]))
          "Should enter edit mode on empty block")

      ;; Cursor at 0 (text length is 0)
      (is (= 0 (get-in session [:ui :cursor-position]))
          "Cursor should be at 0 for empty block"))))

;; ── Edge Cases ──────────────────────────────────────────────────────────────

(deftest enter-edit-selected-no-focus-is-noop
  (testing "Enter with no focused block is no-op"
    (let [db (build-simple-doc)
          session (empty-session)  ;; No selection, no focus
          {:keys [session session-updates]} (run-intent db session
                                              {:type :enter-edit-selected})]
      ;; Should be no-op
      (is (or (nil? session-updates) (empty? session-updates))
          "Should be no-op when no focus")
      (is (nil? (get-in session [:ui :editing-block-id]))
          "editing-block-id should remain nil"))))

(deftest enter-edit-selected-from-multi-selection
  (testing "Enter from multi-selection edits focused block"
    (let [db (build-simple-doc)
          ;; Multiple blocks selected, focus on "c"
          session (with-selection #{"a" "b" "c"} "c" "a" :forward)
          {:keys [session]} (run-intent db session
                              {:type :enter-edit-selected})]
      ;; Should edit focused block (c), not anchor (a)
      (is (= "c" (get-in session [:ui :editing-block-id]))
          "Should edit the focused block, not anchor")

      ;; All selection should be cleared
      (is (empty? (get-in session [:selection :nodes]))
          "Selection should be completely cleared"))))

(deftest enter-edit-selected-clears-selection-completely
  (testing "Enter clears all selection state"
    (let [db (build-simple-doc)
          session (with-selection #{"b"} "b" "b" :forward)
          {:keys [session-updates]} (run-intent db session
                                      {:type :enter-edit-selected})]
      ;; Check raw session-updates structure
      (is (= #{} (get-in session-updates [:selection :nodes]))
          "session-updates should set nodes to #{}")
      (is (nil? (get-in session-updates [:selection :focus]))
          "session-updates should set focus to nil")
      (is (nil? (get-in session-updates [:selection :anchor]))
          "session-updates should set anchor to nil"))))

;; ── Integration: State Transition ───────────────────────────────────────────

(deftest state-machine-allows-enter-edit-selected
  (testing "State machine allows :enter-edit-selected from :selection state"
    (let [db (build-simple-doc)
          session (with-selection #{"b"} "b")
          ;; Verify precondition: we're in selection state
          _ (is (= :selection (cond
                                (get-in session [:ui :editing-block-id]) :editing
                                (seq (get-in session [:selection :nodes])) :selection
                                (get-in session [:selection :focus]) :selected
                                :else :idle))
                "Should be in selection state before intent")

          ;; Run intent and check it worked
          {:keys [session]} (run-intent db session {:type :enter-edit-selected})]

      ;; Verify we transitioned to editing state
      (is (some? (get-in session [:ui :editing-block-id]))
          "Should have transitioned to editing state"))))

;; ── Regression Tests ────────────────────────────────────────────────────────

(deftest regression-enter-edit-selected-returns-session-updates
  (testing "REGRESSION: enter-edit-selected must return session-updates"
    (let [db (build-simple-doc)
          session (with-selection #{"a"} "a")
          {:keys [session-updates]} (intent/apply-intent db session
                                      {:type :enter-edit-selected})]
      ;; This is the critical check - the handler MUST return session-updates
      (is (map? session-updates)
          "Handler must return session-updates map")
      (is (contains? session-updates :ui)
          "session-updates must contain :ui key")
      (is (contains? session-updates :selection)
          "session-updates must contain :selection key")
      (is (= "a" (get-in session-updates [:ui :editing-block-id]))
          "session-updates must set editing-block-id"))))

(deftest regression-editing-block-id-is-string
  (testing "REGRESSION: editing-block-id must be string, not symbol/keyword"
    (let [db (build-simple-doc)
          session (with-selection #{"b"} "b")
          {:keys [session]} (run-intent db session {:type :enter-edit-selected})]
      (is (string? (get-in session [:ui :editing-block-id]))
          "editing-block-id must be a string (block IDs are strings)"))))
