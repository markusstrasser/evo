# Proposal 81 · Aero Profiles for Instrumentation Modes

## Problem
Feature flags live in ad-hoc maps. Toggling between "benchmark", "dev", and "instrumented" setups requires manual option juggling.

## Inspiration
Aero’s tagged EDN (`#profile`, `#include`, `#env`) resolves configuration trees per environment, with deferred evaluation for expensive entries.cite/Users/alien/Projects/inspo-clones/aero/src/aero/core.cljc:1-120

## Proposed Change
1. Add `config/kernel.edn` using Aero tags:
   ```edn
   {:pipeline {:derive? #profile {:dev true :bench false}
              :effects? #profile {:dev true :bench false}}}
   ```
2. On boot, read config once and stash options under `kernel.config/current`.
3. Replace sprinkled feature flags with lookups (`config/enabled? [:pipeline :effects?]`).

## Expected Benefits
- One authoritative switchboard for instrumentation vs. production mode.
- Deferred config entries let us compute expensive diagnostics only when needed (mirrors Aero’s `Deferred`).

## Trade-offs
- Adds a runtime dependency on Aero (CLJ + CLJS). Need to confirm bundling works for CLJS consumer shells.
- Configuration reload semantics must be defined (likely static per process for now).

## Roll-out Steps
1. Vendor Aero (or isolate the small subset we need) into `dev/` for now.
2. Replace existing `enable-block-timestamps?`/`emit-effects?` flags with `config` lookups.
3. Document profile names and add CLI switches to pick a profile.
