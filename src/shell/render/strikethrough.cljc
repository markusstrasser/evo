(ns shell.render.strikethrough
  "TODO (Tier 3): :strikethrough handler — hidden marker spans + <del>."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :strikethrough
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :strikethrough handler not yet migrated" {})))})
