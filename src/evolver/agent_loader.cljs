(ns evolver.agent-loader
  "Loads all agent tools for browser REPL access"
  (:require [agent.core :as agent]
            [agent.store-inspector :as inspector]
            [agent.reference-tools :as refs]
            [agent.schemas :as schemas]))

(defn load-agent-tools!
  "Load all agent tools and make them available globally"
  []
  (js/console.log "Loading agent tools...")

  ;; Make agent tools available on global object for easy access
  (when (and js/window js/window.evo)
    (set! (.-agent js/window.evo) agent)
    (set! (.-inspector js/window.evo) inspector)
    (set! (.-refs js/window.evo) refs)
    (set! (.-schemas js/window.evo) schemas))

  ;; Test environment detection
  (let [env (agent/detect-environment)]
    (js/console.log "Environment detected:" (clj->js env))
    env))

;; Auto-load when this namespace is required
(defonce _loaded (load-agent-tools!))