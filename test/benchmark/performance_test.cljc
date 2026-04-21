(ns benchmark.performance-test
  "Performance benchmark tests for common operations.

   These tests verify that operations complete within acceptable time bounds.
   They serve as regression tests for performance - if a change makes an
   operation significantly slower, these tests will fail.

   BENCHMARKED OPERATIONS:
   - Large document navigation (1000+ blocks)
   - Paste 100-line document
   - Batch operations (indent multiple blocks)
   - Derived index recomputation
   - Undo stack with many operations"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.log :as L]
            [kernel.query :as q]))

;; ── Timing Utilities ─────────────────────────────────────────────────────────

(defn now-ms []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defmacro time-ms
  "Execute body and return [result elapsed-ms]."
  [& body]
  `(let [start# (now-ms)
         result# (do ~@body)
         elapsed# (- (now-ms) start#)]
     [result# elapsed#]))

(defn timed
  "Execute function and return [result elapsed-ms]."
  [f]
  (let [start (now-ms)
        result (f)
        elapsed (- (now-ms) start)]
    [result elapsed]))

;; ── Large Document Generators ────────────────────────────────────────────────

(defn generate-flat-doc
  "Generate a flat document with n blocks."
  [n]
  (let [ops (mapcat
             (fn [i]
               (let [id (str "block-" i)]
                 [{:op :create-node
                   :id id
                   :type :block
                   :props {:text (str "Block content " i " - some more text to make it realistic")}}
                  {:op :place :id id :under :doc :at :last}]))
             (range n))]
    (:db (tx/interpret (db/empty-db) ops))))

(defn generate-nested-doc
  "Generate a nested document with depth levels and blocks-per-level.
   Uses unique IDs by including parent path to avoid collisions."
  [depth blocks-per-level]
  (letfn [(gen-level [parent-id parent-path level idx]
            (let [path (str parent-path "-" idx)
                  id (str "block" path)]
              (concat
               [{:op :create-node
                 :id id
                 :type :block
                 :props {:text (str "Level " level " Block " idx)}}
                {:op :place :id id :under parent-id :at :last}]
               (when (< level depth)
                 (mapcat #(gen-level id path (inc level) %)
                         (range blocks-per-level))))))]
    (let [ops (mapcat #(gen-level :doc "" 0 %) (range blocks-per-level))]
      (:db (tx/interpret (db/empty-db) ops)))))

;; ── Performance Thresholds (ms) ──────────────────────────────────────────────

(def ^:const threshold-fast 50)      ;; Under 50ms for small ops
(def ^:const threshold-medium 200)   ;; Under 200ms for medium ops
(def ^:const threshold-slow 1000)    ;; Under 1s for large ops
(def ^:const threshold-batch 5000)   ;; Under 5s for batch ops

;; ── Document Creation Benchmarks ─────────────────────────────────────────────

(deftest benchmark-create-100-blocks
  (testing "Creating 100 blocks should be fast"
    (let [[db elapsed] (timed #(generate-flat-doc 100))]
      (is (< elapsed threshold-medium)
          (str "Creating 100 blocks took " elapsed "ms, expected <" threshold-medium "ms"))
      (is (= 100 (count (q/children db :doc)))
          "Should have created 100 blocks"))))

(deftest benchmark-create-1000-blocks
  (testing "Creating 1000 blocks should complete in reasonable time"
    (let [[db elapsed] (timed #(generate-flat-doc 1000))]
      (is (< elapsed threshold-slow)
          (str "Creating 1000 blocks took " elapsed "ms, expected <" threshold-slow "ms"))
      (is (= 1000 (count (q/children db :doc)))
          "Should have created 1000 blocks"))))

(deftest benchmark-create-nested-doc
  (testing "Creating nested document should be fast"
    ;; depth=2 means levels 0, 1, 2: 5 + 25 + 125 = 155 blocks
    (let [[db elapsed] (timed #(generate-nested-doc 2 5))]
      (is (< elapsed threshold-medium)
          (str "Creating nested doc took " elapsed "ms, expected <" threshold-medium "ms"))
      (is (> (count (:nodes db)) 100)
          "Should have created many blocks"))))

;; ── Navigation Benchmarks ────────────────────────────────────────────────────

(deftest benchmark-query-children-large-doc
  (testing "Querying children in large doc should be fast"
    (let [db (generate-flat-doc 1000)
          [children elapsed] (timed #(q/children db :doc))]
      (is (< elapsed threshold-fast)
          (str "Children query took " elapsed "ms, expected <" threshold-fast "ms"))
      (is (= 1000 (count children))))))

(deftest benchmark-query-parent-of-large-doc
  (testing "Querying parent-of in large doc should be fast"
    (let [db (generate-flat-doc 1000)
          [parent elapsed] (timed #(get-in db [:derived :parent-of "block-500"]))]
      (is (< elapsed threshold-fast)
          (str "Parent-of query took " elapsed "ms, expected <" threshold-fast "ms"))
      (is (= :doc parent)))))

(deftest benchmark-next-block-query
  (testing "Next-block query should be fast"
    (let [db (generate-flat-doc 1000)
          session {:ui {:folded #{}}
                   :selection {:nodes #{} :focus nil :anchor nil}}
          [next-id elapsed] (timed #(q/visible-next-block db session "block-500"))]
      (is (< elapsed threshold-fast)
          (str "Next-block query took " elapsed "ms, expected <" threshold-fast "ms"))
      (is (= "block-501" next-id)))))

;; ── Derived Index Benchmarks ─────────────────────────────────────────────────

(deftest benchmark-derive-indexes-large-doc
  (testing "Deriving indexes for large doc should be fast"
    (let [db-no-derived (dissoc (generate-flat-doc 500) :derived)
          [db-with-derived elapsed] (timed #(db/derive-indexes db-no-derived))]
      (is (< elapsed threshold-medium)
          (str "Derive indexes took " elapsed "ms, expected <" threshold-medium "ms"))
      (is (some? (:derived db-with-derived))))))

;; ── Operation Benchmarks ─────────────────────────────────────────────────────

(deftest benchmark-single-update-large-doc
  (testing "Single update in large doc should be fast"
    (let [db (generate-flat-doc 1000)
          [result elapsed] (timed
                            #(tx/interpret db
                               [{:op :update-node
                                 :id "block-500"
                                 :props {:text "Updated text"}}]))]
      (is (< elapsed threshold-fast)
          (str "Single update took " elapsed "ms, expected <" threshold-fast "ms"))
      (is (empty? (:issues result))))))

(deftest benchmark-batch-updates
  (testing "Batch of 100 updates should complete quickly"
    (let [db (generate-flat-doc 500)
          ops (map (fn [i]
                     {:op :update-node
                      :id (str "block-" i)
                      :props {:text (str "Updated " i)}})
                   (range 100))
          [result elapsed] (timed #(tx/interpret db ops))]
      (is (< elapsed threshold-medium)
          (str "Batch updates took " elapsed "ms, expected <" threshold-medium "ms"))
      (is (empty? (:issues result))))))

(deftest benchmark-reparent-operation
  (testing "Reparenting block should be fast"
    (let [db (generate-flat-doc 100)
          [result elapsed] (timed
                            #(tx/interpret db
                               [{:op :place
                                 :id "block-50"
                                 :under "block-10"
                                 :at :last}]))]
      (is (< elapsed threshold-fast)
          (str "Reparent took " elapsed "ms, expected <" threshold-fast "ms"))
      (is (empty? (:issues result))))))

;; ── Undo/Redo Benchmarks ─────────────────────────────────────────────────────

(deftest benchmark-undo-after-many-ops
  (testing "Undo after many ops should be fast"
    (let [db0 (generate-flat-doc 100)
          ;; Append 50 ops to a log rooted at db0
          log (reduce
               (fn [log i]
                 (L/append log
                           (L/make-entry
                            {:op-id (random-uuid)
                             :prev-op-id nil
                             :timestamp 0
                             :intent {:type :bench-update}
                             :ops [{:op :update-node
                                    :id (str "block-" i)
                                    :props {:text (str "Modified " i)}}]
                             :session nil})))
               (L/reset-root db0)
               (range 50))
          [result elapsed] (timed #(L/undo log))]
      (is (< elapsed threshold-fast)
          (str "Undo took " elapsed "ms, expected <" threshold-fast "ms"))
      (is (some? result) "Undo should return a new log"))))

;; ── Intent Processing Benchmarks ─────────────────────────────────────────────

(deftest benchmark-selection-intent
  (testing "Selection intent should be fast"
    (let [db (generate-flat-doc 100)
          session {:selection {:nodes #{} :focus nil :anchor nil}
                   :ui {:folded #{}}}
          [result elapsed] (timed
                            #(intent/apply-intent db session
                               {:type :selection
                                :mode :replace
                                :ids "block-50"}))]
      (is (< elapsed threshold-fast)
          (str "Selection intent took " elapsed "ms, expected <" threshold-fast "ms"))
      (is (some? (:session-updates result))))))

(deftest benchmark-navigation-intent
  (testing "Navigation intent should be fast"
    (let [db (generate-flat-doc 1000)
          session {:ui {:editing-block-id "block-500"
                        :cursor-position 5
                        :folded #{}}
                   :selection {:nodes #{} :focus nil :anchor nil}}
          [_result elapsed] (timed
                             #(intent/apply-intent db session
                                {:type :navigate-with-cursor-memory
                                 :current-block-id "block-500"
                                 :current-text "hello world"
                                 :current-cursor-pos 5
                                 :direction :down}))]
      (is (< elapsed threshold-fast)
          (str "Navigation intent took " elapsed "ms, expected <" threshold-fast "ms")))))

;; ── Summary Report ───────────────────────────────────────────────────────────

(deftest performance-summary
  (testing "Generate performance summary"
    ;; This test always passes - it's for reporting
    (let [benchmarks
          [["Create 100 blocks" #(generate-flat-doc 100)]
           ["Create 500 blocks" #(generate-flat-doc 500)]
           ["Query children (1000 blocks)" #(q/children (generate-flat-doc 1000) :doc)]
           ["Derive indexes (500 blocks)"
            #(db/derive-indexes (dissoc (generate-flat-doc 500) :derived))]]]

      (doseq [[bench-name f] benchmarks]
        (let [[_ elapsed] (timed f)]
          #?(:cljs (js/console.log (str "  " bench-name ": " elapsed "ms"))
             :clj  (println (str "  " bench-name ": " elapsed "ms")))))

      (is true "Summary generated"))))
