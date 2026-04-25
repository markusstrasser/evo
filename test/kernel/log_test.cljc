(ns kernel.log-test
  "Tests for the op-log primitives (kernel.log).

   The log is a pure value; all tests thread it explicitly. These tests
   verify the event-sourcing invariants the rest of the kernel depends on:
   head-db refold equals the reduction of :root-db plus applied ops,
   prune-on-divergence on new append after undo, limit trimming folds
   absorbed entries into :root-db."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.log :as L]
            [kernel.transaction :as tx]))

;; ── Fixtures ─────────────────────────────────────────────────────────────────

(defn- mint [n]
  {:op-id (str "op-" n) :prev-op-id nil :timestamp n})

(defn- entry
  "Build an entry that creates block `id` under :doc and sets its text."
  [n id text]
  (L/make-entry
   (merge (mint n)
          {:intent {:type :test :n n}
           :ops [{:op :create-node :id id :type :block :props {:text text}}
                 {:op :place :id id :under :doc :at :last}]
           :session nil})))

;; ── Append + head-db ─────────────────────────────────────────────────────────

(deftest empty-log-head-is-root
  (testing "head-db on empty log returns :root-db unchanged"
    (let [log L/empty-log]
      (is (= (:root-db log) (L/head-db log)))
      (is (= -1 (:head log)))
      (is (not (L/can-undo? log)))
      (is (not (L/can-redo? log))))))

(deftest append-advances-head
  (testing "append bumps head and makes undo available"
    (let [log (-> L/empty-log (L/append (entry 1 "a" "A")))]
      (is (= 0 (:head log)))
      (is (L/can-undo? log))
      (is (not (L/can-redo? log)))
      (is (contains? (:nodes (L/head-db log)) "a")))))

(deftest head-db-folds-multiple-entries
  (testing "head-db reduces entries in order over :root-db"
    (let [log (-> L/empty-log
                  (L/append (entry 1 "a" "A"))
                  (L/append (entry 2 "b" "B"))
                  (L/append (entry 3 "c" "C")))
          d (L/head-db log)]
      (is (= ["a" "b" "c"] (get-in d [:children-by-parent :doc])))
      (is (= 2 (:head log))))))

(deftest head-db-replays-materialized-timestamps
  (testing "log entries store the materialized ops that produced the live db"
    (let [db0 (db/empty-db)
          raw-ops [{:op :create-node :id "a" :type :block :props {:text "A"}}
                   {:op :place :id "a" :under :doc :at :last}
                   {:op :update-node :id "a" :props {:text "A!"}}]
          first-apply (tx/interpret db0 raw-ops {:tx/now-ms 12345})
          log-entry (L/make-entry
                     {:op-id "op-materialized"
                      :prev-op-id nil
                      :timestamp 12345
                      :intent {:type :test}
                      :ops (:ops first-apply)
                      :session nil})
          log (L/append (L/reset-root db0) log-entry)]
      (is (= (:db first-apply) (L/head-db log)))
      (is (= 12345 (get-in (:db first-apply) [:nodes "a" :props :created-at])))
      (is (= 12345 (get-in (L/head-db log) [:nodes "a" :props :updated-at]))))))

;; ── Undo / Redo ──────────────────────────────────────────────────────────────

(deftest undo-moves-head-back
  (let [log (-> L/empty-log
                (L/append (entry 1 "a" "A"))
                (L/append (entry 2 "b" "B")))
        undone (L/undo log)]
    (is (= 0 (:head undone)))
    (is (L/can-redo? undone))
    (is (= ["a"] (get-in (L/head-db undone) [:children-by-parent :doc])))))

(deftest redo-replays-forward
  (let [log (-> L/empty-log
                (L/append (entry 1 "a" "A"))
                (L/append (entry 2 "b" "B"))
                L/undo
                L/redo)]
    (is (= 1 (:head log)))
    (is (= ["a" "b"] (get-in (L/head-db log) [:children-by-parent :doc])))))

(deftest undo-nil-when-empty
  (is (nil? (L/undo L/empty-log))))

(deftest redo-nil-when-at-head
  (let [log (L/append L/empty-log (entry 1 "a" "A"))]
    (is (nil? (L/redo log)))))

;; ── Prune on divergence ──────────────────────────────────────────────────────

(deftest append-after-undo-prunes-future
  (testing "New append after undo discards the orphaned tail"
    (let [log (-> L/empty-log
                  (L/append (entry 1 "a" "A"))
                  (L/append (entry 2 "b" "B"))
                  L/undo
                  (L/append (entry 3 "c" "C")))]
      (is (= 2 (count (:ops log))) "Orphaned tail is pruned")
      (is (= ["a" "c"] (get-in (L/head-db log) [:children-by-parent :doc])))
      (is (not (L/can-redo? log))))))

;; ── Limit / trim ─────────────────────────────────────────────────────────────

(deftest limit-absorbs-oldest-into-root
  (testing "Appending past :limit folds absorbed entries into :root-db"
    (let [log0 (L/set-limit L/empty-log 2)
          log (-> log0
                  (L/append (entry 1 "a" "A"))
                  (L/append (entry 2 "b" "B"))
                  (L/append (entry 3 "c" "C")))]
      (is (= 2 (count (:ops log))) "Only :limit entries retained")
      ;; Block "a" is no longer in log ops but must still appear in head-db
      ;; because it was absorbed into :root-db.
      (is (contains? (:nodes (L/head-db log)) "a")
          "Absorbed entry's effect survives in :root-db")
      (is (contains? (:nodes (:root-db log)) "a")
          ":root-db reflects the absorbed op directly"))))

;; ── Reset + clear ────────────────────────────────────────────────────────────

(deftest reset-root-starts-fresh
  (let [db0 (db/empty-db)
        log (-> (L/reset-root db0)
                (L/append (entry 1 "a" "A"))
                (L/append (entry 2 "b" "B")))]
    (is (= 2 (count (:ops log))))
    (is (= db0 (:root-db log)))))

(deftest clear-drops-ops-keeps-root
  (let [log (-> L/empty-log
                (L/append (entry 1 "a" "A"))
                (L/append (entry 2 "b" "B"))
                L/clear)]
    (is (= 0 (count (:ops log))))
    (is (= -1 (:head log)))
    (is (not (L/can-undo? log)))))

;; ── Trim safety when rewound ────────────────────────────────────────────────

(deftest trim-preserves-head-db-when-head-trails
  (testing "trim-to-limit must not advance :root-db past the user's current head.

   Regression for cross-model critique (2026-04-20): with 4 ops, head=0,
   limit=2 — old impl set head=-2 and absorbed ops 0..1 into :root-db,
   causing head-db to return root+e1+e2 instead of root+e1. Fixed by
   capping absorbed prefix at (inc head) so we never drag :root past the
   user's logical position."
    (let [db0 (db/empty-db)
          log (-> (L/reset-root db0)
                  (L/append (entry 1 "a" "A"))
                  (L/append (entry 2 "b" "B"))
                  (L/append (entry 3 "c" "C"))
                  (L/append (entry 4 "d" "D")))
          ;; User rewound to head=0 (after first op)
          rewound (assoc log :head 0)
          head-db-before (L/head-db rewound)
          ;; Now trigger a trim via set-limit
          trimmed (L/set-limit rewound 2)
          head-db-after (L/head-db trimmed)]
      ;; Core invariant: head-db visible to the user must not change
      (is (= (get-in head-db-before [:children-by-parent :doc])
             (get-in head-db-after [:children-by-parent :doc]))
          "head-db must be identical before and after trim")
      (is (contains? (:nodes head-db-after) "a")
          "First op's effect survives (via :root-db)")
      (is (not (contains? (:nodes head-db-after) "b"))
          "Ops beyond user's head must NOT be absorbed into :root-db"))))

;; ── Session snapshot on undo ────────────────────────────────────────────────

(deftest entry-at-head-exposes-session-snapshot
  (testing "entry-at-head returns entry so caller can restore session-before"
    (let [session {:selection {:nodes #{"a"} :focus "a" :anchor "a"}
                   :ui {:editing-block-id "a" :cursor-position 5}}
          entry-with-session
          (L/make-entry
           {:op-id "op-x" :prev-op-id nil :timestamp 0
            :intent {:type :test}
            :ops [{:op :create-node :id "x" :type :block :props {:text ""}}
                  {:op :place :id "x" :under :doc :at :last}]
            :session session})
          log (L/append L/empty-log entry-with-session)
          at-head (L/entry-at-head log)]
      (is (= #{"a"} (get-in at-head [:session-before :selection :nodes]))
          "Entry captures the pre-op selection")
      (is (= 5 (get-in at-head [:session-before :ui :cursor-position]))
          "Entry captures the pre-op cursor position"))))
