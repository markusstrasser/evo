(ns shell.render.page-ref
  "TODO (Tier 3): :page-ref handler — delegates to `components.page-ref`."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :page-ref
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :page-ref handler not yet migrated" {})))})
