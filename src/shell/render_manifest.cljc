(ns shell.render-manifest
  "Explicit bootstrap surface for render handlers.

   Requires every `shell.render.*` namespace; each one registers its
   handler at namespace-load time via `shell.render-registry/register-render!`.
   Mirrors `plugins.manifest` for the intent/plugin side.

   Tier 2 wires the manifest in with skeleton handlers that throw on
   dispatch. Tier 3 fills in each handler one at a time; block.cljs
   stops touching legacy `render-*` helpers once the last tag is
   migrated."
  (:require [shell.render-registry :as registry]
            [shell.render.text]
            [shell.render.doc]
            [shell.render.bold]
            [shell.render.italic]
            [shell.render.highlight]
            [shell.render.strikethrough]
            [shell.render.math]
            [shell.render.link]
            [shell.render.page-ref]
            [shell.render.image]))

(defn init!
  "Idempotent startup hook. Returns a summary useful for tests and
   REPL debugging; side effects (registration) happen at namespace load."
  []
  {:registered (registry/registered-tags)
   :count (count (registry/registered-tags))})
