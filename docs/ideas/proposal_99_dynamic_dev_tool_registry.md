# Proposal 99 · Dynamic Dev Tool Registry

## Pain Point Today
- Dev helpers are scattered across namespaces (`dev.clj`, `agent.core`, `agent.store-inspector`) with no central registry or enable/disable controls.
- Onboarding requires reading entire files to discover available tools; automation cannot enumerate them.

## Inspiration
- Clojure MCP maintains symbol lists per tool category and resolves them at runtime (`/Users/alien/Projects/inspo-clones/clojure-mcp/src/clojure_mcp/tools.clj:1-160`).
- Builders (e.g., `build-read-only-tools`, `filter-tools`) produce curated toolkits from declarative symbol sets.

## Proposal
Mirror MCP’s registry:

```clojure
(ns dev.tools
  (:require [dev.config :as config]))

(def inspect-syms
  '[agent.store-inspector/inspect-store
    agent.store-inspector/check-reference-integrity])

(def trace-syms
  '[agent.core/get-command-trace
    agent.core/clear-command-trace])

(def resolve-tool
  (memoize (fn [sym]
             (require (symbol (namespace sym)))
             @(resolve sym))))

(defn load-tools [categories]
  (->> categories
       (mapcat #(map resolve-tool (get category->syms %)))
       (map #(% (config/runtime))) vec))
```

### Before
- Tools are called manually (`(agent.core/help)` prints names; there is no programmatic access).

### After
```clojure
(load-tools [:inspect :trace])
;; => [{:id :inspect-store :fn #'agent.store-inspector/inspect-store ...}]
```

## Payoff
- **Composable bundles**: easily expose “read-only toolkits” vs “mutation toolkits” to agents or UI.
- **Feature flags**: config can enable/disable categories without editing code.
- **Doc automation**: docs & help screens derive from registry metadata (Proposal 92).

## Considerations
- Ensure resolved fns are pure or provide capability descriptors (what state they touch, expected args).
- Provide alias metadata so CLJS builds can tree-shake unused categories.
