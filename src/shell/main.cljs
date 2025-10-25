(ns shell.main
  "Main entry point for browser application"
  (:require [shell.blocks-ui :as blocks-ui]))

(defn main []
  (println "App starting...")
  (blocks-ui/main))
