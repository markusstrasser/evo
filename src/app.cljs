(ns app
  (:require [replicant.dom :as r]))

(defonce store (atom {:a 5}))

(+ 1 1)

(defn ^:export main []
  (r/set-dispatch!
    (fn [event-data handler-data]))
      ;; We only care about DOM events for this logic.
  ;; Initial render
  (let [root (.getElementById js/document "root")]
    (r/render root [:div "Hello World"])))