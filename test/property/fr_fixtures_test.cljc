(ns property.fr-fixtures-test
  (:require [clojure.test :refer [deftest is testing]]
            [support.fr-fixtures :as fixtures]))

(deftest deterministic-fr-fixtures-run
  (doseq [fixture fixtures/fixtures]
    (testing (:id fixture)
      (let [result (fixtures/run-fixture fixture)]
        (is (:pass? result) (pr-str result))))))

(deftest generated-variants-do-not-count-as-registry-goldens
  (let [counts (fixtures/coverage-kind-counts)]
    (is (= 1 (:registry-golden counts)))
    (is (= 1 (:generated-variant counts)))))
