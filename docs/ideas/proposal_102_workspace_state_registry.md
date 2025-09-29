# Proposal 102 · Persisted Dev Panels via Workspace-Scoped Registry

## Problem
Dev tooling like inspectors or future storybook explorers lack state persistence. Re-running `(init!)` forgets which filters or scopes were active, so developers repeat manual setup.

## Inspiration
- **Zed's Component Preview persistence** stores active page per workspace using a lightweight SQLite domain (`/Users/alien/Projects/inspo-clones/zed/crates/zed/component_preview/persistence.rs:6-73`). Every preview auto-saves the current tab keyed by workspace + item id, restoring it on reopen.

## Proposal
Introduce `dev.persistence` with a registry API that mirrors Zed's `ComponentPreviewDb`, but backed by `datahike`/EDN files to stay platform-neutral.

### Before
```clojure
(defn init! []
  (println "🎉 Environment ready!")
  ;; Filters & selections lost between runs
  true)
```

### After
```clojure
(ns dev.persistence
  (:require [clojure.edn :as edn]))

(defn save! [panel-id {:keys [workspace-id state]}]
  (spit (path workspace-id panel-id) (pr-str state)))

(defn load! [panel-id workspace-id]
  (some-> (slurp (path workspace-id panel-id)) edn/read-string))

(defn remember-active-scope [workspace panel scope]
  (save! [:scope panel] {:workspace-id workspace :state scope}))
```

`init!` (and future storybook panels) consult `load!` to restore the last used scope/filters, mirroring Zed's UX but without introducing async state.

## Payoff
- **Less friction**: inspectors, storybooks, and registries remember user context per workspace/session.
- **Composable**: registry can host multiple panels (`:storybook`, `:instrumentation`) with minimal ceremony.
- **Future-proof**: the persistence API doubles as a contract for LLM agents to stash annotations per workspace.

## Considerations
- Keep storage synchronous (write EDN on swap) to align with project preference for pure flows.
- Provide pruning/rotation policy to avoid unbounded growth.
