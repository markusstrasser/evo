(ns kernel.main
  "Main entry point for browser application"
  (:require [core.demo :as demo]))

(defn main []
  (println "Kernel main starting...")
  (demo/init!))
