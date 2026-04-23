(ns shell.render.doc
  "TODO (Tier 3): handler for :doc root. Flattens children into a
   Replicant fragment; empty content emits the ZWSP placeholder that
   keeps contenteditable visible in the a11y tree."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :doc
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :doc handler not yet migrated" {})))})
