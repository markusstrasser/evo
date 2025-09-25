(ns evolver.test-runner
  (:require [cljs.test :as test]
            [evolver.kernel-test]
            [evolver.keyboard-test]
            [evolver.reference-test]
            [evolver.command-test]
            [evolver.middleware-test]
            [evolver.fuzzy-ui-test]
            [evolver.chrome-integration-test]))

(defn main! []
  (test/run-tests 'evolver.kernel-test
                  'evolver.keyboard-test
                  'evolver.reference-test
                  'evolver.command-test
                  'evolver.middleware-test
                  'evolver.fuzzy-ui-test))

(set! *main-cli-fn* main!)