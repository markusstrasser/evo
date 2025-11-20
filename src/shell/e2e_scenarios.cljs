(ns shell.e2e-scenarios
  (:require [cljs.reader :as reader]
            [shadow.resource :as resource]))

(def scenarios
  (-> (resource/inline "e2e_scenarios.edn")
      reader/read-string))

(when (exists? js/window)
  (set! (.-__E2E_SCENARIOS js/window) (clj->js scenarios)))
