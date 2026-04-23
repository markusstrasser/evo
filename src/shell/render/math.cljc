(ns shell.render.math
  "Handlers for :math-inline and :math-block.

   Emit `[:span.math \"$…$\"]` / `[:div.math \"$$…$$\"]`. MathJax's global
   scanner picks them up via `processHtmlClass: '\\bmath\\b'` and the
   `.math-ignore` class on every user-content container blocks stray
   typesetting on prose dollars (see .claude/rules/global-dom-scanners.md)."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :math-inline
  {:handler
   (fn [node _ctx]
     (let [content (nth node 2)]
       [:span.math (str "$" content "$")]))})

(register-render! :math-block
  {:handler
   (fn [node _ctx]
     (let [content (nth node 2)]
       [:div.math {:style {:text-align "center" :margin "0.5em 0"}}
        (str "$$" content "$$")]))})
