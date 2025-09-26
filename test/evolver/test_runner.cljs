(ns evolver.test-runner
  (:require [cljs.test :as test]
            [evolver.command-test]
            [evolver.sequential-navigation-test]
            [evolver.structural-operations-test] 
            [evolver.block-lifecycle-test]
            [evolver.content-manipulation-test]
            [evolver.feature-tests]))

(defn main! []
  (println "🧪 Running Comprehensive Evolver Test Suite")
  (println "===========================================")
  
  (println "\n📋 User Story Tests:")
  (println "• Sequential Navigation (Arrow Keys)")
  (println "• Structural Operations (Indent/Outdent/Move)")  
  (println "• Block Lifecycle (Create/Delete/Merge)")
  (println "• Content Manipulation (Enter/Shift+Enter)")
  (println "• Legacy Command Tests")
  (println "• Feature Documentation Tests")
  
  (test/run-tests 
    'evolver.sequential-navigation-test
    'evolver.structural-operations-test
    'evolver.block-lifecycle-test
    'evolver.content-manipulation-test
    'evolver.command-test
    'evolver.feature-tests))

(set! *main-cli-fn* main!)