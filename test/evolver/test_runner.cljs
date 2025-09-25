(ns evolver.test-runner
  (:require [cljs.test :as test]
            [evolver.kernel-test]
            ;; [agent.code-analysis-test] ;; TODO: Fix compilation issue
            ))

(defn main! []
  (test/run-tests 'evolver.kernel-test
                  ;; 'agent.code-analysis-test
                  ))

(set! *main-cli-fn* main!)