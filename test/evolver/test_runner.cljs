(ns evolver.test-runner
  (:require [cljs.test :as test]
            [evolver.kernel-test]))

(defn main! []
  (test/run-tests 'evolver.kernel-test))

(set! *main-cli-fn* main!)