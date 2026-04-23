(ns shell.render.bold
  "TODO (Tier 3): :bold handler — hidden marker spans + <strong>."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :bold
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :bold handler not yet migrated" {})))})
