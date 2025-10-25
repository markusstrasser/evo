(ns app.main
  "Main entry point for browser application"
  (:require [app.blocks-ui :as blocks-ui]))

(defn main []
  (println "App starting...")
  (blocks-ui/main))
