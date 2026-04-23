(ns shell.render.image
  "TODO (Tier 3): :image handler — inline image via `components.image/Image`.
   Block-level image-only rendering stays in `components.block` (it has
   resize handles and distinct click semantics)."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :image
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :image handler not yet migrated" {})))})
