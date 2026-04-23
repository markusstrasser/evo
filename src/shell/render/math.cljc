(ns shell.render.math
  "TODO (Tier 3): :math-inline / :math-block handlers.

   Emit `[:span.math \"$…$\"]` / `[:div.math \"$$…$$\"]`; MathJax's global
   scanner picks them up via `processHtmlClass: '\\bmath\\b'` (see
   .claude/rules/global-dom-scanners.md)."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :math-inline
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :math-inline handler not yet migrated" {})))})

(register-render! :math-block
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :math-block handler not yet migrated" {})))})
