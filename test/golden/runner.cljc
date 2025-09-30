(ns golden.runner
  "Golden test runner - loads golden test cases and verifies behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [core.db :as db]
            [core.interpret :as interp]
            [plugins.permute.core :as permute]
            [clojure.edn :as edn]
            #?(:clj [clojure.java.io :as io])))

#?(:clj
   (defn load-golden-cases
     "Load all golden test cases from test/golden/**/*.edn"
     [dir]
     (let [golden-dir (io/file dir)
           edn-files (filter #(re-find #"\.edn$" (.getName %))
                            (file-seq golden-dir))]
       (for [f edn-files]
         (assoc (edn/read-string (slurp f))
                :file (.getName f))))))

(defn run-golden-case
  "Run a single golden test case and return result."
  [{:keys [name db intent expected-order] :as golden-case}]
  (let [;; Derive initial DB
        db-with-derived (db/derive-indexes db)

        ;; Lower intent to ops
        {:keys [ops issues] :as lower-result} (permute/lower db-with-derived intent)

        ;; If lowering failed, return issues
        _  (when (seq issues)
             (throw (ex-info (str "Failed to lower intent in golden case: " name)
                            {:golden-case golden-case
                             :issues issues})))

        ;; Execute ops
        {:keys [db issues trace]} (interp/interpret db-with-derived ops)

        ;; If execution failed, return issues
        _  (when (seq issues)
             (throw (ex-info (str "Failed to execute ops in golden case: " name)
                            {:golden-case golden-case
                             :ops ops
                             :issues issues})))

        ;; Check actual vs expected
        actual-order (:children-by-parent db)
        matches? (= expected-order actual-order)]

    {:name name
     :passed? matches?
     :expected expected-order
     :actual actual-order
     :ops ops
     :trace trace}))

#?(:clj
   (defn run-all-golden-tests
     "Run all golden tests in directory and report results."
     [dir]
     (let [cases (load-golden-cases dir)
           results (map run-golden-case cases)
           passed (filter :passed? results)
           failed (remove :passed? results)]
       (println (format "Golden tests: %d passed, %d failed out of %d"
                       (count passed)
                       (count failed)
                       (count results)))
       (doseq [f failed]
         (println (format "\nFAILED: %s" (:name f)))
         (println "Expected:" (:expected f))
         (println "Actual:  " (:actual f)))
       {:passed (count passed)
        :failed (count failed)
        :results results})))

(deftest golden-tests
  (testing "Golden test cases for permutation"
    #?(:clj
       (let [result (run-all-golden-tests "test/golden/permute")]
         (is (zero? (:failed result))
             (str (:failed result) " golden tests failed"))))))
