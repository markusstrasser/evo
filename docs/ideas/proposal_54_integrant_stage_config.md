# Proposal 54 · Integrant-Keyed Stage Configuration

## Problem
- Stage lifecycle (validation, derivation, effects) is configured imperatively in `src/kernel/core.cljc:309-366`. There is no metadata describing how stages should initialise or shut down, so adapters duplicate wiring when they need alternate configs (e.g., CI vs interactive mode).
- Feature flags (trace?, assert?) are threaded manually through option maps; we lack structured configuration with derivable overrides.

## Inspiration
- **Integrant’s registry** stores metadata per namespaced key, supports dependency resolution, and exposes runtime reloading (`/Users/alien/Projects/inspo-clones/integrant/src/integrant/core.cljc:1-120`). Systems compose by merging config maps keyed by behaviour—ideal for staging pipeline variants.

## Proposed change
1. Define Integrant keys for kernel stages (e.g., `:kernel.stage/validate-schema`, `:kernel.stage/apply-op`). Each key’s `init-key` returns the stage function along with metadata (axes, docs, instrumentation hooks).
2. Represent `apply-tx+effects*` configuration as an Integrant config map. Different contexts (dev, tests, headless) merge overlays that enable/disable stages or swap implementations.
3. Leverage Integrant’s dependency resolution to ensure optional stages (e.g., invariants) only initialise when their dependencies exist.

```clojure
;; sketch config
{:kernel/stages
 {:kernel.stage/validate-schema {:config {:schema S/op-schema}}
  :kernel.stage/apply-op        {:depends [:kernel.stage/validate-schema]}
  :kernel.stage/derive          {:depends [:kernel.stage/apply-op]}
  :kernel.stage/assert          {:depends [:kernel.stage/derive]
                                 :when    :assert?}
  :kernel.stage/effects         {:depends [:kernel.stage/derive]}}
 :kernel/pipeline {:stages (ig/refset :kernel.stage/*)
                   :flow   workflow-data}}
```
- Initialisation returns a compiled pipeline. Overlays (e.g., `{:kernel.stage/assert {:enabled? false}}`) can be applied per environment.

## Expected benefits
- Simplifies environment-specific setups (`dev`, `ci`, `perf`) by merging configs instead of rewriting functions.
- Provides metadata for documentation tooling (list all stages, their dependencies, configs) automatically.
- Integrates with hot-reload: when a stage namespace changes, Integrant can halt/restart that stage without restarting the entire system.

## Trade-offs
- Adds Integrant dependency; ensure CLJS compatibility (Integrant is CLJC). Provide thin wrapper so consumers not using Integrant can still call `apply-tx+effects*` directly.
- Slight startup overhead to initialise the Integrant system; cache the compiled pipeline for runtime invocations.

## Implementation steps
1. Wrap existing stage fns in `ig/init-key` definitions, using current option map semantics internally.
2. Provide convenience API `(kernel.pipeline/build default-config opts)` returning a callable pipeline.
3. Document configuration overlays in `docs/DEV.md` and update sanity checks to verify Integrant config matches legacy behaviour before removing the old path.
