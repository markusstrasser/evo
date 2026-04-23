(ns shell.render.text
  "TODO (Tier 3): handler for :text nodes. Newlines become [:br]."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :text
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :text handler not yet migrated" {})))})
