(ns shell.main
  "Main entry point for browser application"
  (:require [shell.editor :as editor]))

(defn main []
  (println "App starting...")
  (editor/main))
