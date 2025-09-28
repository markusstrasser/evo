;; Test script for Milestone 3: Operation registry (multimethod refactor)

(require '[kernel.core :as k])
(require '[kernel.sugar-ops]) ; This loads the sugar op extensions
(require '[kernel.sanity_checks :as check])

(println "=== Milestone 3: Operation Registry Tests ===")

;; Test 1: Core primitives still work
(def base {:nodes {"root" {:type :root}} :child-ids/by-parent {}})
(def test1 (k/apply-op base {:op :create-node :id "a" :type :div}))
(assert (contains? (:nodes test1) "a") "Core :create-node failed")
(println "✅ Core primitive :create-node works")

;; Test 2: Sugar ops work after requiring namespace
(def test2 (k/apply-op base {:op :insert :id "b" :parent-id "root"}))
(assert (contains? (:nodes test2) "b") "Sugar :insert failed")
(println "✅ Sugar operation :insert works")

;; Test 3: apply-tx+effects* uses new multimethod
(def result (k/apply-tx+effects* base [{:op :create-node :id "c" :type :span}
                                       {:op :place :id "c" :parent-id "root"}
                                       {:op :insert :id "d" :parent-id "root"}]))
(assert (contains? (:nodes (:db result)) "c") "Mixed ops failed for c")
(assert (contains? (:nodes (:db result)) "d") "Mixed ops failed for d")
(println "✅ apply-tx+effects* works with multimethod")

;; Test 4: run-tx works with sugar ops
(def run-result (k/run-tx base [{:op :insert :id "e" :parent-id "root"}]))
(assert (:ok? run-result) "run-tx failed")
(assert (contains? (:nodes (:db run-result)) "e") "run-tx didn't create node")
(println "✅ run-tx works with sugar ops")

;; Test 5: Unknown op throws error
(try
  (k/apply-op base {:op :unknown-op :id "x"})
  (assert false "Should have thrown for unknown op")
  (catch Exception e
    (assert (re-find #"Unknown :op" (.getMessage e)) "Wrong error message")
    (println "✅ Unknown ops properly throw errors")))

;; Test 6: All sanity checks still pass
(def sanity-result (check/run-all))
(assert (:all-passed? (:summary sanity-result))
        (str "Sanity checks failed: " (:summary sanity-result)))
(println "✅ All sanity checks pass with new multimethod")

(println "\n🎯 Milestone 3 Complete: Operation registry successfully refactored!")
(println "   - dispatch function replaced with multimethod")
(println "   - Sugar ops moved to separate namespace")
(println "   - All existing functionality preserved")
(println "   - Extensible registry achieved")