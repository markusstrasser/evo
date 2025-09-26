(ns debug-nav
  (:require [evolver.commands :as commands]
            [evolver.kernel :as kernel]
            [evolver.constants :as constants]))

;; Simple test to debug navigation
(let [store (atom constants/initial-db-base)]
  ;; Set selection to p1-select (which exists in constants)
  (swap! store assoc-in [:view :selection] ["p1-select"])
  (swap! store assoc-in [:view :selection-set] #{"p1-select"})
  (swap! store assoc-in [:view :cursor] "p1-select")
  
  ;; Check what ctx returns
  (let [ctx-result (commands/ctx store)]
    (println "cursor:" (:cursor ctx-result))
    (println "db nodes:" (keys (:nodes (:db ctx-result))))
    
    ;; Try nav-parent function directly
    (let [nav-parent-fn (get commands/intent->nav :nav-parent)
          parent-result (nav-parent-fn ctx-result)]
      (println "nav-parent result:" parent-result))))
