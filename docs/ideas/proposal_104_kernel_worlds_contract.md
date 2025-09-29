# Proposal 104 · Kernel Worlds Aligned with WIT Contracts

## Problem
Our Malli schemas document shapes, but they don’t express which parts of the kernel are safe to expose to downstream adapters. Adapters have to guess legal entry points.

## Inspiration
- **WIT worlds** describe the boundary between components and hosts, capturing both exports and required imports (`/Users/alien/Projects/inspo-clones/component-docs/component-model/src/design/worlds.md`). Each world defines exactly what functionality crosses the boundary.

## Proposal
Define "kernel worlds"—EDN contracts that mirror WIT world semantics, then emit WIT from them.

### Before
```clojure
(defn interpret [db txs]
  ...)
;; Adapters must read code to know allowed imports/exports.
```

### After
```clojure
(def kernel-worlds
  {:core {:exports [:interpret :derive-db :describe-ops]
          :imports [:log :clock]}
   :analyzer {:exports [:dry-run :explain-op]
              :imports [:store :editor-bridge]}})

(wit/generate kernel-worlds)
```

Adapters can target the `:core` world, while internal tooling can bind to the richer `:analyzer` world. Any capability not listed stays internal by design, matching the sandboxing guarantees described in the component model docs.

## Payoff
- **Clear boundaries**: Teams know exactly what the kernel promises to downstream consumers.
- **Composable host story**: Future hosts (CLI tests, UI renderers) can “import” just the interfaces they need, enabling partial embedding.
- **Bridge to Proposal 96**: The same EDN map drives WIT generation, Malli schemas, and storybook documentation.

## Considerations
- Start with one world (existing public API) before expanding to avoid design churn.
- Keep worlds purely declarative; implementation remains in current namespaces to respect kernel purity.
