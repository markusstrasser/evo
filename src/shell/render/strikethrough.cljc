(ns shell.render.strikethrough
  "Handler for :strikethrough. Hidden `~~` markers + <del>."
  (:require [shell.render-registry :refer [register-render! render-all]]
            [shell.render.marker :refer [marker-span]]))

(register-render! :strikethrough
  {:handler
   (fn [node ctx]
     (let [children (nth node 2)]
       [(marker-span "~~")
        (into [:del] (render-all children ctx))
        (marker-span "~~")]))})
