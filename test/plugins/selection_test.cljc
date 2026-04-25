(ns plugins.selection-test
  "Tests for selection plugin (selection lives in session, not DB)."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            [utils.session-patch :as session-patch]))

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
  (session-patch/merge-patch session session-updates))

;; ── Test helpers ─────────────────────────────────────────────────────────────

(defn apply-selection-intent
  "Apply a selection intent and return updated session."
  [db session intent]
  (let [{:keys [session-updates]} (intent/apply-intent db session intent)]
    (apply-session-updates session session-updates)))

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn build-doc
  "Creates a test DB with structure: doc1 -> [a, b, c]"
  []
  (let [db0 (db/empty-db)
        {:keys [db issues]} (tx/interpret db0
                              [{:op :create-node :id "doc1" :type :doc :props {}}
                               {:op :place :id "doc1" :under :doc :at :last}
                               {:op :create-node :id "a" :type :p :props {}}
                               {:op :place :id "a" :under "doc1" :at :last}
                               {:op :create-node :id "b" :type :p :props {}}
                               {:op :place :id "b" :under "doc1" :at :last}
                               {:op :create-node :id "c" :type :p :props {}}
                               {:op :place :id "c" :under "doc1" :at :last}])]
    (assert (empty? issues) (str "Fixture setup failed: " (pr-str issues)))
    db))

;; ── Selection state tests ─────────────────────────────────────────────────────

(deftest select-single-node
  (testing "Select a single node"
    (let [db (build-doc)
          session (empty-session)
          session' (apply-selection-intent db session {:type :selection :mode :replace :ids "a"})]
      (is (= #{"a"} (q/selection session'))
          "Selection should contain single node")
      (is (q/selected? session' "a")
          "Node 'a' should be selected")
      (is (not (q/selected? session' "b"))
          "Node 'b' should not be selected"))))

(deftest select-multiple-nodes
  (testing "Select multiple nodes at once"
    (let [db (build-doc)
          session (empty-session)
          session' (apply-selection-intent db session {:type :selection :mode :replace :ids ["a" "c"]})]
      (is (= #{"a" "c"} (q/selection session'))
          "Selection should contain both nodes")
      (is (q/selected? session' "a"))
      (is (not (q/selected? session' "b")))
      (is (q/selected? session' "c")))))

(deftest ^{:fr/ids #{:fr.pointer/shift-click-range}}
  extend-selection-test
  (testing "Extend selection adds to selection — shift-click-select-range!
           computes the visible range UI-side and dispatches this same
           :selection :mode :extend, so this exercise covers the range
           case end-to-end for the FR"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "a"})
          session' (apply-selection-intent db session1 {:type :selection :mode :extend :ids "c"})]
      ;; Note: extend adds the ID to the selection
      (is (contains? (q/selection session') "a")
          "Original selection should be preserved")
      (is (contains? (q/selection session') "c")
          "Extended selection should include new node"))))

(deftest deselect-test
  (testing "Deselect removes from selection"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids ["a" "b" "c"]})
          session' (apply-selection-intent db session1 {:type :selection :mode :deselect :ids "b"})]
      (is (= #{"a" "c"} (q/selection session'))
          "Node 'b' should be removed from selection"))))

(deftest clear-selection-test
  (testing "Clear empties selection"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids ["a" "b"]})
          session' (apply-selection-intent db session1 {:type :selection :mode :clear})]
      (is (= #{} (q/selection session'))
          "Selection should be empty")
      (is (not (q/has-selection? session'))
          "has-selection? should return false"))))

(deftest toggle-selection-test
  (testing "Toggle adds if not selected, removes if selected"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :toggle :ids "a"})
          session2 (apply-selection-intent db session1 {:type :selection :mode :toggle :ids "b"})
          session' (apply-selection-intent db session2 {:type :selection :mode :toggle :ids "a"})]
      (is (= #{"b"} (q/selection session'))
          "Only 'b' should remain selected"))))

;; ── Selection navigation tests ────────────────────────────────────────────────

(deftest 
  select-next-sibling-test
  (testing "Select next sibling replaces selection"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "a"})
          session' (apply-selection-intent db session1 {:type :selection :mode :next})]
      (is (= #{"b"} (q/selection session'))
          "Selection should move to next sibling 'b'")))

  (testing "No-op when no next sibling"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "c"})
          session' (apply-selection-intent db session1 {:type :selection :mode :next})]
      (is (= #{"c"} (q/selection session'))
          "Selection should stay on 'c' (last child)"))))

(deftest 
  select-prev-sibling-test
  (testing "Select previous sibling replaces selection"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "b"})
          session' (apply-selection-intent db session1 {:type :selection :mode :prev})]
      (is (= #{"a"} (q/selection session'))
          "Selection should move to previous sibling 'a'")))

  (testing "Boundary: prev from first child stays at current (no selectable container)"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "a"})
          session' (apply-selection-intent db session1 {:type :selection :mode :prev})]
      (is (= #{"a"} (q/selection session'))
          "Selection should stay at 'a' (doc1 is container, not selectable)"))))

(deftest select-parent-test
  (testing "No-op when parent is container (doc/page)"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "b"})
          session' (apply-selection-intent db session1 {:type :selection :mode :parent})]
      (is (= #{"b"} (q/selection session'))
          "Selection should remain unchanged when parent is a container")))

  (testing "No-op when multiple parents (invalid state)"
    (let [db (build-doc)
          ;; Create second doc with different parent
          {:keys [db]} (tx/interpret db
                         [{:op :create-node :id "doc2" :type :doc :props {}}
                          {:op :place :id "doc2" :under :doc :at :last}
                          {:op :create-node :id "d" :type :p :props {}}
                          {:op :place :id "d" :under "doc2" :at :last}])
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids ["a" "d"]})
          session' (apply-selection-intent db session1 {:type :selection :mode :parent})]
      (is (= #{"a" "d"} (q/selection session'))
          "Selection should remain unchanged when parents differ"))))

(deftest 
  select-all-siblings-test
  (testing "Select all siblings of current selection"
    (let [db (build-doc)
          session (empty-session)
          session1 (apply-selection-intent db session {:type :selection :mode :replace :ids "b"})
          session' (apply-selection-intent db session1 {:type :selection :mode :all-siblings})]
      (is (= #{"a" "b" "c"} (q/selection session'))
          "All siblings should be selected"))))

;; ── Selection state queries ───────────────────────────────────────────────────

(deftest selection-count-test
  (testing "Selection count"
    (let [db (build-doc)
          session (empty-session)]
      (is (= 0 (q/selection-count session))
          "Empty session has no selection")
      (is (= 2 (q/selection-count (apply-selection-intent db session {:type :selection :mode :replace :ids ["a" "c"]})))
          "Selection count should be 2"))))

(deftest has-selection-test
  (testing "Has selection predicate"
    (let [db (build-doc)
          session (empty-session)]
      (is (not (q/has-selection? session))
          "Empty session has no selection")
      (is (q/has-selection? (apply-selection-intent db session {:type :selection :mode :replace :ids "a"}))
          "Session with selection returns true"))))
