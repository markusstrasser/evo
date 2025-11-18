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

   Returns:
     {:verified-frs #{:fr.nav/... :fr.edit/...}  ; Set of all FRs with test coverage
      :test-count 10                              ; Total tests with FR citations
      :tests-by-fr {:fr.nav/... #{'test-name}}   ; Map of FR -> test names
      :uncited-test-count 42}                     ; Tests without FR citations"
  []
  (let [test-vars (get-test-vars)
        total-tests (count test-vars)]
    (reduce
     (fn [acc test-var]
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
           acc)))
     {:verified-frs #{}
      :test-count 0
      :tests-by-fr {}
      :uncited-test-count total-tests}
     test-vars)))

(defn verification-coverage
  "Calculate verification coverage percentage.

   Requires FR registry to be loaded:
     (require '[dev.spec-registry :as fr])
     (verification-coverage)
     ;=> {:total-frs 12, :verified-frs 5, :coverage-pct 41}"
  []
  (let [scan-result (scan-tests-for-frs)
        verified-count (count (:verified-frs scan-result))]
    ;; Note: Requires FR registry to be loaded for total count
    ;; This function is safe to call even if registry not loaded
    (try
      (require 'dev.spec-registry)
      (let [fr-ns (the-ns 'dev.spec-registry)
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
