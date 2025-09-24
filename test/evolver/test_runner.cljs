(ns evolver.test-runner
  (:require
    [cljs.test]
    [evolver.core-test]
    [evolver.kernel-test]))

(defn main! [& args]
  (cljs.test/run-tests 'evolver.core-test 'evolver.kernel-test))
