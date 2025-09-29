(ns golden-replay-test
  "Golden trace replay integration tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [core.db :as db]
            [core.interpret :as I]))

(defn load-golden-trace [filename]
  "Loads a golden trace from ir/examples/"
  (-> (str "src/ir/examples/" filename)
      slurp
      edn/read-string))

(deftest test-basic-golden-trace
  (testing "Basic golden trace produces expected final state"
    (let [txs (load-golden-trace "basic.edn")
          db0 (db/empty-db)
          result (I/interpret db0 txs)]

      (is (empty? (:issues result)) "No validation issues")

      ;; Children of root should be ["b" "a"] after reordering
      (is (= (get-in result [:db :children-by-parent "root"]) ["b" "a"]))

      ;; Node a should have bold style
      (is (= (get-in result [:db :nodes "a" :props :style :bold]) true))

      ;; Validate final database state
      (let [validation (I/validate (:db result))]
        (is (:ok? validation) "Final database passes validation")))))

(deftest test-reparent-golden-trace
  (testing "Reparent golden trace moves subtree correctly"
    (let [txs (load-golden-trace "reparent.edn")
          db0 (db/empty-db)
          result (I/interpret db0 txs)]

      (is (empty? (:issues result)) "No validation issues")

      ;; Subtree should now be under h2
      (is (= (get-in result [:db :children-by-parent "h2"]) ["subtree"]))

      ;; h1 should no longer have subtree
      (is (= (get-in result [:db :children-by-parent "h1"]) []))

      ;; Subtree children should be reordered: ["child2" "child1"]
      (is (= (get-in result [:db :children-by-parent "subtree"]) ["child2" "child1"]))

      ;; Derived indexes should be consistent
      (is (= (get-in result [:db :derived :parent-of "subtree"]) "h2"))
      (is (= (get-in result [:db :derived :parent-of "child1"]) "subtree"))
      (is (= (get-in result [:db :derived :parent-of "child2"]) "subtree"))

      ;; child2 should be at index 0, child1 at index 1
      (is (= (get-in result [:db :derived :index-of "child2"]) 0))
      (is (= (get-in result [:db :derived :index-of "child1"]) 1))

      ;; Validate final database state
      (let [validation (I/validate (:db result))]
        (is (:ok? validation) "Final database passes validation")))))

(deftest test-golden-traces-are-deterministic
  (testing "Golden traces produce same result when run multiple times"
    (let [txs (load-golden-trace "basic.edn")
          db0 (db/empty-db)
          result1 (I/interpret db0 txs)
          result2 (I/interpret db0 txs)]

      (is (= (:db result1) (:db result2)) "Results are deterministic"))))

(deftest test-golden-traces-validate-final-object-only
  (testing "Golden traces focus on final object equality, not internal sequences"
    (let [txs (load-golden-trace "basic.edn")
          db0 (db/empty-db)
          result (I/interpret db0 txs)]

      ;; We only assert final state properties, not intermediate steps
      (is (= (get-in result [:db :children-by-parent "root"]) ["b" "a"]))
      (is (= (get-in result [:db :nodes "a" :props :style :bold]) true))
      (is (= (get-in result [:db :nodes "b" :props :t]) "B"))

      ;; Trace should have expected number of operations
      (is (= (count (:trace result)) 5)))))