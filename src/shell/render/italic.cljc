(ns shell.render.italic
  "TODO (Tier 3): :italic handler — hidden marker spans + <em>."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :italic
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :italic handler not yet migrated" {})))})
