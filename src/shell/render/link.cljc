(ns shell.render.link
  "TODO (Tier 3): :link handler — case on (:type (parse-evo-target …))
   for evo:// targets, render anchor + dispatch :navigate-to-page /
   :zoom-in / :go-to-journal intents. External links open in new tab."
  (:require [shell.render-registry :refer [register-render!]]))

(register-render! :link
  {:handler
   (fn [_node _ctx]
     (throw (ex-info "TODO :link handler not yet migrated" {})))})
