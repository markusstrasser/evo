(ns shell.render.highlight
  "TODO (Tier 3): :highlight handler — hidden marker spans + <mark>."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :highlight
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :highlight handler not yet migrated" {})))})
