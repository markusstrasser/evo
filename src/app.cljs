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
    ;; Initial
    (r/render root [:div "Store: " @store])

    (add-watch store :render
               (fn [key atom old-state new-state]
                 (r/render root [:div "Store: " new-state])))))




