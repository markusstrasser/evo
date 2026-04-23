(ns shell.render.highlight
  "Handler for :highlight. Hidden `==` markers + <mark>."
  (:require [shell.render-registry :refer [register-render! render-all]]
            [shell.render.marker :refer [marker-span]]))

(register-render! :highlight
  {:handler
   (fn [node ctx]
     (let [children (nth node 2)]
       [(marker-span "==")
        (into [:mark] (render-all children ctx))
        (marker-span "==")]))})
