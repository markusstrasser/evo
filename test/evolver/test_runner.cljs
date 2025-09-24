(ns evolver.test-runner
  (:require [cljs.test :as test]
            [evolver.kernel-test]
            [evolver.keyboard-test]
            [evolver.reference-test]
            [evolver.command-test]
            [evolver.middleware-test]
            [evolver.fuzzy-ui-test]
            [evolver.keyboard-integration-test]))

(defn main! []
  (test/run-tests 'evolver.kernel-test
                  'evolver.keyboard-test
                  'evolver.reference-test
                  'evolver.command-test
                  'evolver.middleware-test
                  'evolver.fuzzy-ui-test
                  'evolver.keyboard-integration-test))

(set! *main-cli-fn* main!)