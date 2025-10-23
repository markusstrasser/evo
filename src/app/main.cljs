(ns app.main
  "Main entry point for browser application"
  (:require [core.demo :as demo]))

(defn main []
  (println "App starting...")
  (demo/init!))
