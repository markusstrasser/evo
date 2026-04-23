(ns shell.render.italic
  "Handler for :italic. Hidden marker spans + <em>."
  (:require [shell.render-registry :refer [register-render! render-all]]
            [shell.render.marker :refer [marker-span]]))

(register-render! :italic
  {:handler
   (fn [node ctx]
     (let [attrs (nth node 1)
           children (nth node 2)
           m (or (:marker attrs) "_")]
       [(marker-span m)
        (into [:em] (render-all children ctx))
        (marker-span m)]))})
