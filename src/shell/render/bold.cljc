(ns shell.render.bold
  "Handler for :bold. Emits [marker-span <strong>…</strong> marker-span].
   The marker spans are visually hidden but preserved in clipboard
   selection so `**x**` round-trips across copy/paste."
  (:require [shell.render-registry :refer [register-render! render-all]]
            [shell.render.marker :refer [marker-span]]))

(register-render! :bold
  {:handler
   (fn [node ctx]
     (let [attrs (nth node 1)
           children (nth node 2)
           m (or (:marker attrs) "**")]
       [(marker-span m)
        (into [:strong] (render-all children ctx))
        (marker-span m)]))})
