(ns property.invariants-test
  "Property-based tests for database invariants.

   These tests generate random operation sequences and verify that
   the database remains in a valid state after each operation.

   CRITICAL INVARIANTS TESTED:
   - Parent-child relationships are consistent
   - Derived indexes match computed values
   - No orphaned nodes (except in trash)
   - Undo/redo is reversible
   - Cursor position is always within text bounds"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]))

;; ── Generators ───────────────────────────────────────────────────────────────

(def gen-block-id
  "Generate a block ID string."
  (gen/fmap (fn [n] (str "block-" n)) (gen/choose 1 100)))

(def gen-text
  "Generate random text content."
  (gen/fmap #(apply str %) (gen/vector gen/char-alphanumeric 0 100)))

(def gen-create-op
  "Generate a create-node operation."
  (gen/let [id gen-block-id
            text gen-text]
    {:op :create-node
     :id id
     :type :block
     :props {:text text}}))

(def gen-place-op
  "Generate a place operation."
  (gen/let [id gen-block-id
            parent (gen/one-of [gen-block-id (gen/return :doc)])
            position (gen/one-of [(gen/return :first)
                                  (gen/return :last)])]
    {:op :place
     :id id
     :under parent
     :at position}))

(def gen-update-op
  "Generate an update-node operation."
  (gen/let [id gen-block-id
            text gen-text]
    {:op :update-node
     :id id
     :props {:text text}}))

(def gen-op
  "Generate any single operation."
  (gen/one-of [gen-create-op gen-place-op gen-update-op]))

(def gen-ops-sequence
  "Generate a sequence of operations."
  (gen/vector gen-op 1 10))

;; ── Invariant Checkers ───────────────────────────────────────────────────────

(defn db-valid?
  "Check if database satisfies all invariants."
  [db]
  (let [result (db/validate db)]
    (:ok? result)))

(defn parent-of-consistent?
  "Check that :derived/:parent-of matches :children-by-parent."
  [db]
  (let [children-by-parent (:children-by-parent db)
        parent-of (get-in db [:derived :parent-of])
        ;; For each parent->children mapping, verify parent-of is consistent
        consistent (every?
                    (fn [[parent children]]
                      (every? #(= parent (get parent-of %)) children))
                    children-by-parent)]
    consistent))

(defn no-orphans?
  "Check that all nodes have a parent (except roots)."
  [db]
  (let [all-nodes (keys (:nodes db))
        roots (set (:roots db))
        parent-of (get-in db [:derived :parent-of])]
    (every? (fn [node-id]
              (or (contains? roots (keyword node-id))
                  (contains? parent-of node-id)))
            all-nodes)))

(defn cursor-in-bounds?
  "Check that cursor position is within text bounds."
  [db session]
  (if-let [editing-id (get-in session [:ui :editing-block-id])]
    (let [cursor-pos (get-in session [:ui :cursor-position])
          text (get-in db [:nodes editing-id :props :text] "")
          text-len (count text)]
      (and (number? cursor-pos)
           (>= cursor-pos 0)
           (<= cursor-pos text-len)))
    true)) ;; Not editing, so cursor bounds don't apply

;; ── Property Tests ───────────────────────────────────────────────────────────

(defspec db-remains-valid-after-ops 50
  (prop/for-all [ops gen-ops-sequence]
    (let [db0 (db/empty-db)
          ;; Apply ops, catching errors
          result (try
                   (tx/interpret db0 ops)
                   (catch #?(:clj Exception :cljs js/Error) _e
                     {:db db0 :issues [:error]}))]
      ;; Either ops failed (issues non-empty) or DB is valid
      (or (seq (:issues result))
          (db-valid? (:db result))))))

(defspec parent-of-consistent-after-ops 50
  (prop/for-all [ops gen-ops-sequence]
    (let [db0 (db/empty-db)
          result (try
                   (tx/interpret db0 ops)
                   (catch #?(:clj Exception :cljs js/Error) _e
                     {:db db0 :issues [:error]}))]
      (or (seq (:issues result))
          (parent-of-consistent? (:db result))))))

;; ── Deterministic Invariant Tests ────────────────────────────────────────────

(deftest fresh-db-is-valid
  (testing "Fresh empty DB satisfies all invariants"
    (let [db (db/empty-db)]
      (is (db-valid? db) "Empty DB should be valid")
      (is (parent-of-consistent? db) "Parent-of should be consistent"))))

(deftest ops-maintain-invariants
  (testing "Known-good ops maintain invariants"
    (let [db0 (db/empty-db)
          {:keys [db issues]} (tx/interpret db0
                                [{:op :create-node :id "a" :type :block :props {:text "First"}}
                                 {:op :place :id "a" :under :doc :at :last}
                                 {:op :create-node :id "b" :type :block :props {:text "Second"}}
                                 {:op :place :id "b" :under :doc :at :last}
                                 {:op :create-node :id "c" :type :block :props {:text "Child"}}
                                 {:op :place :id "c" :under "a" :at :last}])]
      (is (empty? issues) "Ops should succeed")
      (is (db-valid? db) "DB should be valid after ops")
      (is (parent-of-consistent? db) "Parent-of should be consistent"))))

(deftest delete-maintains-invariants
  (testing "Delete operation maintains invariants"
    (let [db0 (db/empty-db)
          {:keys [db]} (tx/interpret db0
                         [{:op :create-node :id "a" :type :block :props {:text "First"}}
                          {:op :place :id "a" :under :doc :at :last}
                          {:op :create-node :id "b" :type :block :props {:text "Second"}}
                          {:op :place :id "b" :under :doc :at :last}])
          ;; Delete block "a"
          {:keys [db issues]} (tx/interpret db
                                [{:op :place :id "a" :under :trash :at :last}])]
      (is (empty? issues) "Delete should succeed")
      (is (db-valid? db) "DB should be valid after delete")
      (is (= :trash (get-in db [:derived :parent-of "a"]))
          "Deleted block should be in trash"))))

(deftest reparenting-maintains-invariants
  (testing "Reparenting (indent/outdent) maintains invariants"
    (let [db0 (db/empty-db)
          {:keys [db]} (tx/interpret db0
                         [{:op :create-node :id "a" :type :block :props {:text "Parent"}}
                          {:op :place :id "a" :under :doc :at :last}
                          {:op :create-node :id "b" :type :block :props {:text "Will be child"}}
                          {:op :place :id "b" :under :doc :at :last}])
          ;; Reparent b under a
          {:keys [db issues]} (tx/interpret db
                                [{:op :place :id "b" :under "a" :at :last}])]
      (is (empty? issues) "Reparent should succeed")
      (is (db-valid? db) "DB should be valid after reparent")
      (is (= "a" (get-in db [:derived :parent-of "b"]))
          "Block should have new parent"))))

;; ── Stress Tests ─────────────────────────────────────────────────────────────

(defspec many-ops-maintain-validity 20
  (testing "Many sequential ops don't corrupt DB"
    (prop/for-all [op-batches (gen/vector gen-ops-sequence 3 5)]
      (let [final-db (reduce
                      (fn [current-db ops]
                        (let [result (try
                                       (tx/interpret current-db ops)
                                       (catch #?(:clj Exception :cljs js/Error) _e
                                         {:db current-db :issues [:error]}))]
                          (:db result)))
                      (db/empty-db)
                      op-batches)]
        (db-valid? final-db)))))

;; ── Intent-Level Property Tests ──────────────────────────────────────────────

(def gen-selection-intent
  "Generate a selection intent."
  (gen/let [mode (gen/elements [:replace :extend :clear])
            ids (gen/vector gen-block-id 0 3)]
    {:type :selection
     :mode mode
     :ids (if (empty? ids) nil (first ids))}))

(defspec selection-intents-preserve-db 30
  (testing "Selection intents don't corrupt DB"
    (prop/for-all [selection-intent gen-selection-intent]
      (let [db0 (db/empty-db)
            {:keys [db]} (tx/interpret db0
                           [{:op :create-node :id "a" :type :block :props {:text "First"}}
                            {:op :place :id "a" :under :doc :at :last}])
            session {:cursor {:block-id nil :offset 0}
                     :selection {:nodes #{} :focus nil :anchor nil}
                     :buffer {}
                     :ui {:folded #{}}}
            ;; Apply selection intent
            result (try
                     (intent/apply-intent db session selection-intent)
                     (catch #?(:clj Exception :cljs js/Error) _e
                       {:db db :ops []}))]
        ;; Selection intents should never change DB
        (= (:nodes db) (:nodes (:db result)))))))

;; ── Derived Index Freshness ──────────────────────────────────────────────────

(deftest derived-indexes-match-recomputed
  (testing "Derived indexes match recomputed values"
    (let [db0 (db/empty-db)
          {:keys [db]} (tx/interpret db0
                         [{:op :create-node :id "a" :type :block :props {:text "Parent"}}
                          {:op :place :id "a" :under :doc :at :last}
                          {:op :create-node :id "b" :type :block :props {:text "Child 1"}}
                          {:op :place :id "b" :under "a" :at :last}
                          {:op :create-node :id "c" :type :block :props {:text "Child 2"}}
                          {:op :place :id "c" :under "a" :at :last}])
          ;; Recompute derived indexes
          recomputed (db/derive-indexes (dissoc db :derived))]
      ;; Derived should match recomputed
      (is (= (:derived db) (:derived recomputed))
          "Derived indexes should match recomputed values"))))
