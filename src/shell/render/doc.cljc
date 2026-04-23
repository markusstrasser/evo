(ns shell.render.doc
  "Handler for the :doc AST root.

   Returns a sibling vector of children — the view-mode container
   (`<span.block-content>` / `<blockquote>` / `<h{1..6}>`) wraps them.
   Empty content emits a zero-width-space so the block stays visible
   to the a11y tree and to Playwright (existing e2e specs expect it)."
  (:require [shell.render-registry :refer [register-render! render-all]]))

(register-render! :doc
  {:handler
   (fn [node ctx]
     (let [children (nth node 2)
           rendered (render-all children ctx)]
       (if (seq rendered)
         rendered
         ["​"])))})
