(ns shell.render.text
  "Render handler for :text nodes.

   Single-line → return the raw string.
   Multi-line  → return a sibling vector with [:br] interposed — the
   `render-registry/render-all` flattener splats it into the parent."
  (:require [clojure.string :as str]
            [shell.render-registry :refer [register-render!]]))

(register-render! :text
  {:handler
   (fn [node _ctx]
     (let [s (nth node 2)]
       (if (and (string? s) (str/includes? s "\n"))
         (let [parts (str/split s #"\n" -1)]
           (vec (interpose [:br] parts)))
         (or s ""))))})
