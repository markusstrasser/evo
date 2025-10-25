(ns plugins.selection.core-test
  "Tests for selection plugin (ADR-015 pattern: :selection at DB root)."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [core.db :as D]
            [core.interpret :as I]
            [plugins.selection.core :as S]))

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn build-doc
  "Creates a test DB with structure: doc1 -> [a, b, c]"
  []
  (let [DB0 (D/empty-db)
        {:keys [db issues]} (I/interpret DB0
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
          db' (S/select db "a")]
      (is (= #{"a"} (S/get-selection db'))
          "Selection should contain single node")
      (is (S/selected? db' "a")
          "Node 'a' should be selected")
      (is (not (S/selected? db' "b"))
          "Node 'b' should not be selected"))))

(deftest select-multiple-nodes
  (testing "Select multiple nodes at once"
    (let [db (build-doc)
          db' (S/select db ["a" "c"])]
      (is (= #{"a" "c"} (S/get-selection db'))
          "Selection should contain both nodes")
      (is (S/selected? db' "a"))
      (is (not (S/selected? db' "b")))
      (is (S/selected? db' "c")))))

(deftest extend-selection-test
  (testing "Extend selection selects range between anchor and focus"
    (let [db (build-doc)
          db' (-> db
                  (S/select "a")
                  (S/extend-selection "c"))]
      (is (= #{"a" "b" "c"} (S/get-selection db'))
          "Range selection should include intermediate nodes"))))

(deftest deselect-test
  (testing "Deselect removes from selection"
    (let [db (build-doc)
          db' (-> db
                  (S/select ["a" "b" "c"])
                  (S/deselect "b"))]
      (is (= #{"a" "c"} (S/get-selection db'))
          "Node 'b' should be removed from selection"))))

(deftest clear-selection-test
  (testing "Clear empties selection"
    (let [db (build-doc)
          db' (-> db
                  (S/select ["a" "b"])
                  (S/clear))]
      (is (= #{} (S/get-selection db'))
          "Selection should be empty")
      (is (not (S/has-selection? db'))
          "has-selection? should return false"))))

(deftest toggle-selection-test
  (testing "Toggle adds if not selected, removes if selected"
    (let [db (build-doc)
          db' (-> db
                  (S/toggle "a")    ;; Add a
                  (S/toggle "b")    ;; Add b
                  (S/toggle "a"))]  ;; Remove a
      (is (= #{"b"} (S/get-selection db'))
          "Only 'b' should remain selected"))))

;; ── Selection navigation tests ────────────────────────────────────────────────

(deftest select-next-sibling-test
  (testing "Select next sibling replaces selection"
    (let [db (build-doc)
          db' (-> db
                  (S/select "a")
                  (S/select-next-sibling))]
      (is (= #{"b"} (S/get-selection db'))
          "Selection should move to next sibling 'b'")))

  (testing "No-op when no next sibling"
    (let [db (build-doc)
          db' (-> db
                  (S/select "c")
                  (S/select-next-sibling))]
      (is (= #{"c"} (S/get-selection db'))
          "Selection should stay on 'c' (last child)"))))

(deftest select-prev-sibling-test
  (testing "Select previous sibling replaces selection"
    (let [db (build-doc)
          db' (-> db
                  (S/select "b")
                  (S/select-prev-sibling))]
      (is (= #{"a"} (S/get-selection db'))
          "Selection should move to previous sibling 'a'")))

  (testing "No-op when no previous sibling"
    (let [db (build-doc)
          db' (-> db
                  (S/select "a")
                  (S/select-prev-sibling))]
      (is (= #{"a"} (S/get-selection db'))
          "Selection should stay on 'a' (first child)"))))

(deftest select-parent-test
  (testing "Select parent of selected node"
    (let [db (build-doc)
          db' (-> db
                  (S/select "b")
                  (S/select-parent))]
      (is (= #{"doc1"} (S/get-selection db'))
          "Selection should move to parent 'doc1'")))

  (testing "No-op when multiple parents (invalid state)"
    (let [db (build-doc)
          ;; Create second doc with different parent
          {:keys [db]} (I/interpret db
                         [{:op :create-node :id "doc2" :type :doc :props {}}
                          {:op :place :id "doc2" :under :doc :at :last}
                          {:op :create-node :id "d" :type :p :props {}}
                          {:op :place :id "d" :under "doc2" :at :last}])
          db' (-> db
                  (S/select ["a" "d"])  ;; Different parents
                  (S/select-parent))]
      (is (= #{"a" "d"} (S/get-selection db'))
          "Selection should remain unchanged when parents differ"))))

(deftest select-all-siblings-test
  (testing "Select all siblings of current selection"
    (let [db (build-doc)
          db' (-> db
                  (S/select "b")
                  (S/select-all-siblings))]
      (is (= #{"a" "b" "c"} (S/get-selection db'))
          "All siblings should be selected"))))

;; ── Selection state queries ───────────────────────────────────────────────────

(deftest selection-count-test
  (testing "Selection count"
    (let [db (build-doc)]
      (is (= 0 (S/selection-count db))
          "Empty DB has no selection")
      (is (= 2 (S/selection-count (S/select db ["a" "c"])))
          "Selection count should be 2"))))

(deftest has-selection-test
  (testing "Has selection predicate"
    (let [db (build-doc)]
      (is (not (S/has-selection? db))
          "Empty DB has no selection")
      (is (S/has-selection? (S/select db "a"))
          "DB with selection returns true"))))
