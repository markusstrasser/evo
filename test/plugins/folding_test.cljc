(ns plugins.folding-test
  "Tests for folding plugin: expand/collapse operations."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.query :as q]
            [kernel.intent :as intent]
            [plugins.folding]
            [utils.session-patch :as session-patch]))  ;; Load intent registrations

;; ── Test Setup ────────────────────────────────────────────────────────────────

(defn setup-tree
  "Create a test tree structure:
     doc
     ├─ a 'Root'
     │  ├─ b 'Child 1'
     │  │  └─ c 'Grandchild'
     │  └─ d 'Child 2'
     └─ e 'Root 2'"
  []
  (:db
   (tx/interpret (db/empty-db)
                 [{:op :create-node :id "a" :type :block :props {:text "Root"}}
                  {:op :create-node :id "b" :type :block :props {:text "Child 1"}}
                  {:op :create-node :id "c" :type :block :props {:text "Grandchild"}}
                  {:op :create-node :id "d" :type :block :props {:text "Child 2"}}
                  {:op :create-node :id "e" :type :block :props {:text "Root 2"}}
                  {:op :place :id "a" :under :doc :at :last}
                  {:op :place :id "b" :under "a" :at :last}
                  {:op :place :id "c" :under "b" :at :last}
                  {:op :place :id "d" :under "a" :at :last}
                  {:op :place :id "e" :under :doc :at :last}])))

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

(defn session-with-folded
  "Create a session with given blocks folded."
  [folded-set]
  (assoc-in (empty-session) [:ui :folded] folded-set))

(defn apply-session-updates
  "Apply session-updates returned by a handler to a session."
  [session session-updates]
  (session-patch/merge-patch session session-updates))

;; ── Query Tests ───────────────────────────────────────────────────────────────

(deftest folded-queries
  (testing "initially nothing is folded"
    (let [session (empty-session)]
      (is (= #{} (q/folded-set session)))
      (is (false? (q/folded? session "a")))
      (is (false? (q/folded? session "b")))))

  (testing "after folding a block"
    (let [session (session-with-folded #{"a"})]
      (is (= #{"a"} (q/folded-set session)))
      (is (true? (q/folded? session "a")))
      (is (false? (q/folded? session "b"))))))

;; ── Toggle Fold Tests ─────────────────────────────────────────────────────────

(deftest toggle-fold-test
  (let [db (setup-tree)]
    (testing "toggle fold on block with children collapses it"
      (let [session (empty-session)
            result (intent/apply-intent db session {:type :toggle-fold :block-id "a"})
            session' (apply-session-updates session (:session-updates result))]
        (is (= #{"a"} (q/folded-set session')))
        (is (true? (q/folded? session' "a")))))

    (testing "toggle fold again expands it"
      (let [session (session-with-folded #{"a"})
            result (intent/apply-intent db session {:type :toggle-fold :block-id "a"})
            session' (apply-session-updates session (:session-updates result))]
        (is (= #{} (q/folded-set session')))
        (is (false? (q/folded? session' "a")))))

    (testing "toggle fold on leaf node does nothing"
      (let [session (empty-session)
            result (intent/apply-intent db session {:type :toggle-fold :block-id "e"})]
        (is (nil? (:session-updates result)))))))

;; ── Expand All Tests ──────────────────────────────────────────────────────────

(deftest expand-all-test
  (let [db (setup-tree)]
    (testing "expand-all removes block and all descendants from folded set"
      (let [session (session-with-folded #{"a" "b" "d"})
            result (intent/apply-intent db session {:type :expand-all :block-id "a"})
            session' (apply-session-updates session (:session-updates result))]
        (is (= #{} (q/folded-set session')))
        (is (false? (q/folded? session' "a")))
        (is (false? (q/folded? session' "b")))
        (is (false? (q/folded? session' "d")))))

    (testing "expand-all on leaf does nothing"
      (let [session (session-with-folded #{"a"})
            result (intent/apply-intent db session {:type :expand-all :block-id "c"})]
        (is (nil? (:session-updates result)))))))

;; ── Collapse Tests ────────────────────────────────────────────────────────────

(deftest collapse-test
  (let [db (setup-tree)]
    (testing "collapse adds block to folded set"
      (let [session (empty-session)
            result (intent/apply-intent db session {:type :collapse :block-id "a"})
            session' (apply-session-updates session (:session-updates result))]
        (is (= #{"a"} (q/folded-set session')))
        (is (true? (q/folded? session' "a")))))

    (testing "collapse on already collapsed block is idempotent"
      (let [session (session-with-folded #{"a"})
            result (intent/apply-intent db session {:type :collapse :block-id "a"})
            session' (apply-session-updates session (:session-updates result))]
        (is (= #{"a"} (q/folded-set session')))))

    (testing "collapse on leaf does nothing"
      (let [session (empty-session)
            result (intent/apply-intent db session {:type :collapse :block-id "c"})]
        (is (nil? (:session-updates result)))))))

;; ── Toggle All Folds Tests ────────────────────────────────────────────────────

(deftest toggle-all-folds-test
  (let [db (setup-tree)]
    (testing "toggle-all when nothing folded collapses top-level"
      (let [session (empty-session)
            result (intent/apply-intent db session {:type :toggle-all-folds :root-id :doc})
            session' (apply-session-updates session (:session-updates result))]
        (is (= #{"a" "e"} (q/folded-set session')))
        (is (true? (q/folded? session' "a")))
        (is (true? (q/folded? session' "e")))
        (is (false? (q/folded? session' "b")))))

    (testing "toggle-all when some folded expands all"
      (let [session (session-with-folded #{"a"})
            result (intent/apply-intent db session {:type :toggle-all-folds :root-id :doc})
            session' (apply-session-updates session (:session-updates result))]
        (is (= #{} (q/folded-set session')))))))
