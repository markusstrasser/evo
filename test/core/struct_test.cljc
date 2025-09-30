(ns core.struct-test
  "Integration tests for structural editing intent compiler.
   Tests verify end-to-end behavior: intent → ops → final DB state."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [core.db :as D]
            [core.interpret :as I]
            [core.struct :as S]))

;; ── Test fixtures ─────────────────────────────────────────────────────────────

(defn build-doc
  "Creates a test DB with structure: doc1 -> [a, b]"
  []
  (let [DB0 (D/empty-db)
        {:keys [db issues]} (I/interpret DB0
                              [{:op :create-node :id "doc1" :type :doc :props {}}
                               {:op :place :id "doc1" :under :doc :at :last}
                               {:op :create-node :id "a" :type :p :props {}}
                               {:op :place :id "a" :under "doc1" :at :last}
                               {:op :create-node :id "b" :type :p :props {}}
                               {:op :place :id "b" :under "doc1" :at :last}])]
    (assert (empty? issues) (str "Fixture setup failed: " (pr-str issues)))
    db))

;; ── Integration tests ─────────────────────────────────────────────────────────

(deftest delete-archives-to-trash
  (testing "Delete intent compiles to :place under :trash"
    (let [db (build-doc)
          ops (S/compile-intents db [{:type :delete :id "a"}])
          res (I/interpret db ops)]
      (is (empty? (:issues res))
          "Delete operation should not generate issues")
      (is (= ["b"] (get-in res [:db :children-by-parent "doc1"]))
          "Node 'a' should be removed from doc1")
      (is (= ["a"] (get-in res [:db :children-by-parent :trash]))
          "Node 'a' should appear in trash"))))

(deftest indent-then-outdent-is-alpha-equivalent
  (testing "Indent followed by outdent restores original structure"
    (let [db (build-doc)
          ;; Indent b under a: doc1 -> [a -> [b]]
          ops1 (S/compile-intents db [{:type :indent :id "b"}])
          r1   (I/interpret db ops1)
          db1  (:db r1)
          ;; Outdent b back to doc1: doc1 -> [a, b]
          ops2 (S/compile-intents db1 [{:type :outdent :id "b"}])
          r2   (I/interpret db1 ops2)]
      (is (empty? (:issues r1))
          "Indent operation should not generate issues")
      (is (empty? (:issues r2))
          "Outdent operation should not generate issues")
      (is (= ["b"] (get-in r1 [:db :children-by-parent "a"]))
          "After indent, b should be under a")
      (is (= ["a" "b"] (get-in r2 [:db :children-by-parent "doc1"]))
          "After outdent, structure should be restored"))))

(deftest safety-noop-when-not-applicable
  (testing "Indent on first child returns empty ops (no-op safety)"
    (let [db (build-doc)
          first-id (first (get-in db [:children-by-parent "doc1"]))
          ops (S/compile-intents db [{:type :indent :id first-id}])]
      (is (= [] ops)
          "Cannot indent first child (no previous sibling)")))

  (testing "Outdent on direct child of root returns empty ops"
    (let [db (build-doc)
          ops (S/compile-intents db [{:type :outdent :id "doc1"}])]
      (is (= [] ops)
          "Cannot outdent top-level document (no grandparent)"))))

(deftest compile-multiple-intents
  (testing "Multiple intents compile into sequential ops"
    (let [db (build-doc)
          intents [{:type :indent :id "b"}
                   {:type :delete :id "a"}]
          ops (S/compile-intents db intents)]
      (is (= 2 (count ops))
          "Two intents should produce two ops")
      (is (every? #(contains? % :op) ops)
          "All compiled outputs should be valid ops"))))

(deftest unknown-intent-type-is-noop
  (testing "Unknown intent type returns empty ops"
    (let [db (build-doc)
          ops (S/compile-intents db [{:type :unknown-intent :id "a"}])]
      (is (= [] ops)
          "Unknown intent type should compile to no-op"))))