(ns harness.intent-fixtures
  "Shared registry fixtures for isolated kernel/script tests."
  (:require [kernel.intent :as intent]))

(defn with-registered-intents
  "Build a fixture that replaces the intent registry for the duration of a test.

   `intent-configs` is a map of intent keyword to register-intent! config.
   Each fixture starts from an empty registry, registers only the requested
   intents, then restores the previous registry on exit."
  [intent-configs]
  (fn [f]
    (let [saved-intents @intent/!intents
          saved-uncited @intent/!uncited-intents]
      (reset! intent/!intents {})
      (reset! intent/!uncited-intents #{})
      (doseq [[kw config] intent-configs]
        (intent/register-intent!
         kw
         (merge {:doc (str kw " test intent")
                 :handler (fn [_db _session _intent] [])}
                config)))
      (try
        (f)
        (finally
          (reset! intent/!intents saved-intents)
          (reset! intent/!uncited-intents saved-uncited))))))

(def with-state-machine-intents
  "Minimal intent registry needed by kernel.state-machine-test."
  (with-registered-intents
    {:selection {}
     :enter-edit {:allowed-states #{:selection}}
     :navigate-with-cursor-memory {:allowed-states #{:editing}}
     :smart-split {:allowed-states #{:editing}}
     :exit-edit {:allowed-states #{:editing}}}))
