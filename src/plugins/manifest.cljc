(ns plugins.manifest
  "Explicit bootstrap surface for plugin registration.

   The plugin namespaces below still self-register handlers/derived plugins at
   namespace load time. This manifest centralizes that startup policy so
   `shell.editor` no longer owns a long side-effect require list."
  (:require [kernel.derived-registry :as registry]
            [plugins.selection :as selection]
            [plugins.editing :as editing]
            [plugins.clipboard :as clipboard]
            [plugins.navigation :as navigation]
            [plugins.structural :as structural]
            [plugins.folding :as folding]
            [plugins.context-editing :as context-editing]
            [plugins.text-formatting :as text-formatting]
            [plugins.autocomplete :as autocomplete]
            [plugins.pages :as pages]
            [plugins.backlinks-index :as backlinks-index]))

(def ^:private loaded-plugins
  {:selection selection/loaded?
   :editing editing/loaded?
   :clipboard clipboard/loaded?
   :navigation navigation/loaded?
   :structural structural/loaded?
   :folding folding/loaded?
   :context-editing context-editing/loaded?
   :text-formatting text-formatting/loaded?
   :autocomplete autocomplete/loaded?
   :pages pages/loaded?
   :backlinks-index backlinks-index/loaded?})

(defn init!
  "Force plugin manifest loading and return a small startup summary.

   The return value is useful in tests and startup debugging; the registration
   side effects themselves occur when this namespace is loaded, and any
   idempotent derived registrations that tests may clear are restored here."
  []
  (registry/register-derived! :backlinks backlinks-index/compute-backlinks-index)
  {:loaded loaded-plugins
   :count (count loaded-plugins)})
