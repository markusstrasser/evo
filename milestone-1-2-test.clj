;; Test script for Milestone 1 & 2: REPL-driven transactions + introspection tools

(require '[kernel.core :as k])
(require '[kernel.introspect :as ix])

(println "=== Milestone 1: REPL-driven transactions ===")

;; Test 1.1: run-tx happy path
(def base {:nodes {"root" {:type :root}} :child-ids/by-parent {}})
(def result1 (k/run-tx base [{:op :insert :id "a" :parent-id "root"}]))

(println "Happy path test:")
(println "  :ok?" (:ok? result1))
(println "  :db has 'a'?" (contains? (:nodes (:db result1)) "a"))
(println "  :effects count:" (count (:effects result1)))
(assert (:ok? result1) "Happy path should succeed")
(assert (contains? (:nodes (:db result1)) "a") "Node 'a' should exist")

;; Test 1.2: run-tx error path
(def result2 (k/run-tx base [{:op :insert :id "a" :parent-id "root"}
                             {:op :place :id "a" :parent-id "NOPE"}]))

(println "\nError path test:")
(println "  :ok?" (:ok? result2))
(println "  :error op-index:" (get-in result2 [:error :op-index]))
(println "  :error why:" (get-in result2 [:error :why]))
(println "  :db unchanged?" (= (:db result2) base))
(assert (not (:ok? result2)) "Error path should fail")
(assert (= (get-in result2 [:error :op-index]) 1) "Should fail on second op")
(assert (= (:db result2) base) "DB should be unchanged on error")

;; Test 1.3: trace functionality
(def result3 (k/apply-tx+effects* base [{:op :insert :id "a" :parent-id "root"}
                                        {:op :insert :id "b" :parent-id "root"}]
                                   {:trace? true}))

(println "\nTrace test:")
(println "  :trace count:" (count (:trace result3)))
(println "  :effects count:" (count (:effects result3)))
(assert (= (count (:trace result3)) 2) "Should have 2 trace steps")
(assert (= (count (:effects result3)) 2) "Should have 2 effects")

(println "\n=== Milestone 2: Introspection tools ===")

;; Test 2.1: diff functionality
(def before (k/*derive-pass* base))
(def after (k/apply-tx* before [{:op :insert :id "a" :parent-id "root"}
                                {:op :update-node :id "a" :props {:x 1}}]))

(def diff-result (ix/diff before after))
(println "Diff test:")
(println "  :added:" (:added diff-result))
(println "  :props-changed:" (:props-changed diff-result))
(assert (contains? (:added diff-result) "a") "Should detect added node")
(assert (contains? (:props-changed diff-result) "a") "Should detect props change")

;; Test 2.2: path functionality
(def tree-db (k/apply-tx* (k/*derive-pass* base)
                          [{:op :insert :id "a" :parent-id "root"}
                           {:op :insert :id "b" :parent-id "a"}
                           {:op :insert :id "c" :parent-id "b"}]))

(def path-result (ix/path tree-db "c"))
(println "\nPath test:")
(println "  path to 'c':" (mapv :id path-result))
(assert (= (mapv :id path-result) ["root" "a" "b" "c"]) "Should show path from root to c")

;; Test 2.3: trace introspection
(def trace-summary (ix/trace result3))
(println "\nTrace introspection test:")
(println "  trace summary:" trace-summary)
(assert (= (count trace-summary) 2) "Should summarize 2 steps")
(assert (every? #(= (:op %) :insert) trace-summary) "Both ops should be :insert")

(println "\n✅ All Milestone 1 & 2 tests passed!")