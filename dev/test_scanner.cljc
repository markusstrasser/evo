(ns dev.test-scanner
  "Scan test namespaces for FR citations to track verification coverage.

   Usage:
     (require '[dev.test-scanner :as scanner])
     (scanner/scan-tests-for-frs)
     ;=> {:verified-frs #{:fr.nav/vertical-cursor-memory ...}
     ;    :test-count 5
     ;    :tests-by-fr {:fr.nav/vertical-cursor-memory #{'cursor-memory-test}}}

   FUNCTIONAL REQUIREMENT: This supports verification tracking for the
   Spec-as-Database pattern. Tests cite FRs via metadata, proving implementation."
  (:require [clojure.test :as t]))

(defn get-test-vars
  "Extract all test vars from test namespaces."
  []
  (->> (all-ns)
       (filter #(re-matches #".*-test$" (str (ns-name %))))
       (mapcat ns-interns)
       (map val)
       (filter (comp :test meta))))

(defn scan-tests-for-frs
  "Scan all test namespaces and extract FR citations from metadata.

   Returns map with keys:
   - :verified-frs        => set of FR IDs referenced by tests
   - :test-count          => number of tests that cite FRs
   - :tests-by-fr         => map FR -> set of test symbols
   - :uncited-tests       => set of tests missing :fr/ids
   - :uncited-test-count  => count of uncited tests
   - :total-tests         => total number of discovered tests"
  []
  (let [test-vars (get-test-vars)
        total-tests (count test-vars)
        result (reduce (fn [acc test-var]
                         (let [m (meta test-var)
                               test-name (:name m)
                               fr-ids (:fr/ids m)]
                           (if (seq fr-ids)
                             (-> acc
                                 (update :verified-frs into fr-ids)
                                 (update :test-count inc)
                                 (update :tests-by-fr
                                         (fn [by-fr]
                                           (reduce (fn [m fr-id]
                                                     (update m fr-id (fnil conj #{}) test-name))
                                                   by-fr
                                                   fr-ids))))
                             (update acc :uncited-tests conj test-name))))
                       {:verified-frs #{}
                        :test-count 0
                        :tests-by-fr {}
                        :uncited-tests #{}}
                       test-vars)]
    (-> result
        (assoc :total-tests total-tests)
        (assoc :uncited-test-count (count (:uncited-tests result))))))

(defn verification-coverage
  "Calculate verification coverage percentage.

   Requires FR registry to be loaded:
     (require '[spec.registry :as fr])
     (verification-coverage)
     ;=> {:total-frs 12, :verified-frs 5, :coverage-pct 41}"
  []
  (let [scan-result (scan-tests-for-frs)
        verified-count (count (:verified-frs scan-result))]
    ;; Note: Requires FR registry to be loaded for total count
    ;; This function is safe to call even if registry not loaded
    (try
      (require 'spec.registry)
      (let [fr-ns (the-ns 'spec.registry)
            list-frs-fn (ns-resolve fr-ns 'list-frs)
            total-frs (count (list-frs-fn))]
        {:total-frs total-frs
         :verified-frs verified-count
         :coverage-pct (if (zero? total-frs)
                         0
                         (int (* 100 (/ verified-count total-frs))))})
      (catch #?(:clj Exception :cljs js/Error) _
        ;; Registry not loaded, return partial info
        {:verified-frs verified-count
         :coverage-pct nil}))))

(comment
  ;; Usage examples
  (scan-tests-for-frs)
  ;=> {:verified-frs #{:fr.nav/vertical-cursor-memory}
  ;    :test-count 1
  ;    :tests-by-fr {:fr.nav/vertical-cursor-memory #{cursor-memory-test}}
  ;    :uncited-test-count 268}

  (verification-coverage)
  ;=> {:total-frs 12, :verified-frs 1, :coverage-pct 8}
  )
