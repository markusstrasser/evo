(ns evolver.core
  (:require [replicant.dom :as r]))

(defonce app-state (atom {:counter 5}))

(defn ^:export main []
  (r/set-dispatch!
    (fn [event-data handler-data]
      ;; Handle events here
      ))
  ;; Initial render
  (let [root (.getElementById js/document "root")]
    (r/render root [:div "App State: " @app-state])

    (add-watch app-state :render
               (fn [key atom old-state new-state]
                 (r/render root [:div "App State: " new-state])))))