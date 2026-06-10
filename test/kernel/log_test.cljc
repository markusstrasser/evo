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

;; ── Refold equivalence (skip-derived fold == full-derive fold) ──────────────

(defn- head-db-full-derive
  "Oracle: the pre-optimization refold — full interpret (with per-entry
   index derivation) for every applied entry."
  [log]
  (let [{:keys [root-db ops head]} log
        applied (take (inc head) ops)]
    (reduce (fn [d log-entry]
              (:db (tx/interpret d (:ops log-entry))))
            root-db
            applied)))

(defn- move-entry
  "Entry that re-places an existing block (structural move)."
  [n id under at]
  (L/make-entry
   (merge (mint n)
          {:intent {:type :test-move :n n}
           :ops [{:op :place :id id :under under :at at}]
           :session nil})))

(defn- update-entry
  "Entry that rewrites a block's text (carries a materialized timestamp so
   replay is deterministic)."
  [n id text]
  (L/make-entry
   (merge (mint n)
          {:intent {:type :test-update :n n}
           :ops [{:op :update-node :id id :props {:text text :updated-at n}}]
           :session nil})))

(deftest head-db-skip-derived-fold-equals-full-derive-fold
  (testing "head-db (derive once at the end) equals the per-entry-derive oracle
   on a log mixing creates, moves, nesting, and updates"
    (let [log (-> L/empty-log
                  (L/append (entry 1 "a" "A"))
                  (L/append (entry 2 "b" "B"))
                  (L/append (entry 3 "c" "C"))
                  (L/append (move-entry 4 "c" "a" :first))   ; nest c under a
                  (L/append (move-entry 5 "b" :doc :first))  ; move b to front
                  (L/append (update-entry 6 "a" "A!"))
                  (L/append (move-entry 7 "c" :doc :last)))] ; un-nest c
      (is (= (head-db-full-derive log) (L/head-db log))
          "skip-derived fold + one final derive == full-derive fold")
      (is (:ok? (db/validate (L/head-db log)))
          "refolded db validates, incl. derived freshness")))
  (testing "equality also holds at every rewound head position"
    (let [log (-> L/empty-log
                  (L/append (entry 1 "a" "A"))
                  (L/append (entry 2 "b" "B"))
                  (L/append (move-entry 3 "b" :doc :first))
                  (L/append (move-entry 4 "b" :doc :last)))]
      (doseq [h (range -1 (count (:ops log)))]
        (let [rewound (assoc log :head h)]
          (is (= (head-db-full-derive rewound) (L/head-db rewound))
              (str "refold equivalence at head=" h)))))))

;; ── Root ingress derives (at-rest invariant) ────────────────────────────────

(deftest reset-root-derives-stale-root
  (testing "reset-root re-derives its input, so a stale caller db cannot leak
   stale indexes through head-db at head=-1 (the verbatim-root path)"
    (let [fresh (:db (tx/interpret (db/empty-db)
                                   [{:op :create-node :id "a" :type :block :props {:text "A"}}
                                    {:op :place :id "a" :under :doc :at :last}]
                                   {:tx/now-ms 1}))
          stale (assoc fresh :derived {})          ; hand-corrupted at-rest db
          log (L/reset-root stale)
          root (L/head-db log)]
      (is (= :doc (get-in root [:derived :parent-of "a"]))
          "stored root has fresh indexes")
      (is (:ok? (db/validate root))
          "head-db at root validates incl. derived freshness"))))

;; ── Generative refold equivalence ────────────────────────────────────────────

(defn- make-rng
  "Deterministic LCG; returns (fn [n] -> int in [0, n))."
  [seed]
  (let [state (atom (max 1 seed))]
    (fn [n]
      (mod (swap! state (fn [s] (mod (* 48271 s) 2147483647))) n))))

(defn- random-log
  "Build a log of n-entries valid entries: creates (possibly nested),
   moves back to :doc (:first/:last — cycle-free by construction), and
   text updates. Exercises un-nesting and reorder against the stale
   :derived a skip-derived refold carries."
  [seed n-entries]
  (let [rng (make-rng seed)]
    (loop [log (L/reset-root (db/empty-db))
           ids []
           i 0]
      (if (= i n-entries)
        log
        (let [kind (cond
                     (empty? ids) :create
                     (zero? (rng 3)) :create
                     (= 1 (rng 2)) :move
                     :else :update)
              n (+ 100 i)
              target (when (seq ids) (nth ids (rng (count ids))))
              e (case kind
                  :create (let [id (str "n" i)
                                parent (if (or (empty? ids) (zero? (rng 2)))
                                         :doc
                                         (nth ids (rng (count ids))))]
                            (L/make-entry
                             (merge (mint n)
                                    {:intent {:type :gen-create}
                                     :ops [{:op :create-node :id id :type :block
                                            :props {:text (str "text " i)}}
                                           {:op :place :id id :under parent :at :last}]
                                     :session nil})))
                  :move (L/make-entry
                         (merge (mint n)
                                {:intent {:type :gen-move}
                                 :ops [{:op :place :id target :under :doc
                                        :at (if (zero? (rng 2)) :first :last)}]
                                 :session nil}))
                  :update (L/make-entry
                           (merge (mint n)
                                  {:intent {:type :gen-update}
                                   :ops [{:op :update-node :id target
                                          :props {:text (str "upd " i) :updated-at n}}]
                                   :session nil})))]
          (recur (L/append log e)
                 (if (= kind :create) (conj ids (str "n" i)) ids)
                 (inc i)))))))

(deftest head-db-refold-equivalence-generative
  (testing "skip-derived fold == full-derive fold over seeded random logs"
    (doseq [seed [7 23 42 99 1234 5678 31337 271828 314159 999983]]
      (let [log (random-log seed 8)]
        (is (= (head-db-full-derive log) (L/head-db log))
            (str "refold equivalence, seed=" seed))
        (is (:ok? (db/validate (L/head-db log)))
            (str "refolded db validates, seed=" seed))))))

;; ── Session snapshot on undo ────────────────────────────────────────────────

(deftest entry-at-head-exposes-session-snapshot
  (testing "entry-at-head returns entry so caller can restore session-before and session-after"
    (let [session {:selection {:nodes #{"a"} :focus "a" :anchor "a"}
                   :ui {:editing-block-id "a" :cursor-position 5}}
          session-after {:selection {:nodes #{} :focus nil :anchor nil}
                         :ui {:editing-block-id "x" :cursor-position 0}}
          entry-with-session
          (L/make-entry
           {:op-id "op-x" :prev-op-id nil :timestamp 0
            :intent {:type :test}
            :ops [{:op :create-node :id "x" :type :block :props {:text ""}}
                  {:op :place :id "x" :under :doc :at :last}]
            :session session
            :session-after session-after})
          log (L/append L/empty-log entry-with-session)
          at-head (L/entry-at-head log)]
      (is (= #{"a"} (get-in at-head [:session-before :selection :nodes]))
          "Entry captures the pre-op selection")
      (is (= 5 (get-in at-head [:session-before :ui :cursor-position]))
          "Entry captures the pre-op cursor position")
      (is (= "x" (get-in at-head [:session-after :ui :editing-block-id]))
          "Entry captures the post-op edit target for redo"))))
