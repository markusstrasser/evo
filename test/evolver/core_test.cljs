(ns evolver.core-test
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]))

(deftest example-test
  (testing "simple assertion"
    (is (= 1 1))))

;; Uncomment the line below to run tests directly from this file in REPL
;; (run-tests)
