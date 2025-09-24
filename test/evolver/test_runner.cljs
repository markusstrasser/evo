(ns evolver.test-runner
  (:require [cljs.test :as test]
            [evolver.command-test]))

(defn main! []
  (test/run-tests 'evolver.command-test))

(set! *main-cli-fn* main!)