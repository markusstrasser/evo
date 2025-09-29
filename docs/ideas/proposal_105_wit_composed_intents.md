# Proposal 105 · Composed Intent Modules using WIT

## Problem
Our intent lowering assumes a monolithic interpreter. Integrating optional features (references, layout) means branching in a single codepath instead of wiring modules together.

## Inspiration
- **Component composition** in the WebAssembly component model allows a primary component’s imports to be fulfilled by dependency components (`/Users/alien/Projects/inspo-clones/component-docs/component-model/src/composing-and-distributing/composing.md`). Interfaces become the seam for swapping implementations without changing the primary component.

## Proposal
Split advanced intent pipelines into composable WIT modules:

1. Define a core interpreter world (Proposal 104).
2. Define optional worlds (`reference`, `layout`) exporting additional lowering/rewrite passes.
3. Compose the final kernel component by “plugging” optional modules based on deployment needs.

### Before
```clojure
(defn interpret [db ops]
  (-> ops
      normalize
      (maybe-lower-references db)
      (maybe-apply-layout db)
      execute))
```

### After
```clojure
;; core.wit exports interpret-core
;; reference.wit exports lower-reference
;; layout.wit exports lower-layout

(wac/compose
  :primary core-component
  :deps {:reference reference-component
         :layout layout-component})
```

At runtime we can select which composed component to load, avoiding conditional logic in the interpreter while keeping the three-op kernel stable.

## Payoff
- **Slimmer core**: optional features live in separate modules; core stays minimal.
- **Deployment flexibility**: servers without layout needs omit that component entirely.
- **Testing clarity**: property tests can run against the core component, while integration tests cover composed variants.

## Considerations
- Composition tooling is young; start with documented scripts (mirroring `wac plug`) and evolve toward automation.
- Ensure composed components still expose the same minimal world so adapters don’t see interface churn.
