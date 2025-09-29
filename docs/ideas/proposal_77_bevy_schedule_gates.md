# Proposal 77 · Bevy-Style Schedule Gates

## Problem
Our transaction pipeline is a fixed vector. Instrumentation, invariant checks, and effect detectors are always invoked even when no stage needs them, adding latency and branching in the hot path.

## Inspiration
Bevy’s `App::add_systems` wires systems into labelled stages and wraps them with `run_if` predicates so work can be turned off without rewriting the schedule.cite/Users/alien/Projects/inspo-clones/bevy/crates/bevy_app/src/app.rs:121-128

## Proposed Change
1. Replace the current `[:normalize :infer :derive :effects]` vector with a `schedule` of `{::stage id ::systems [fn ...] ::run? predicate}` maps.
2. Expose `kernel.pipeline/register-stage!` and `kernel.pipeline/set-run-criterion!` helpers so tooling can toggle stages (e.g. skip `:effects` during CLI repls).
3. Auto-enable heavy stages only when instrumentation flags demand them.

### Before
```clojure
(def default-pipeline [:normalize :infer :derive :effects])
```

### After
```clojure
(def default-schedule
  [{::stage :normalize ::systems [tx/normalize] ::run? (constantly true)}
   {::stage :derive ::systems [derive/run] ::run? #(-> % :options :derive?)}
   {::stage :effects ::systems [effects/detect] ::run? #(-> % :options :emit-effects?)}])
```
`kernel.core/apply-tx+effects*` iterates the schedule, calling stages only when `::run?` returns truthy.

## Expected Benefits
- Simple flag flip to disable expensive stages, enabling lighter REPL loops and doc-only runs.
- Makes it trivial to add debug stages (e.g. trace capture) without paying their cost in production.

## Trade-offs
- Slightly more indirection when reading the pipeline; newcomers must learn the schedule structure.
- Predicates need to be pure or memoised to stay cheap.

## Roll-out Steps
1. Introduce the schedule structure alongside the legacy vector and add parity tests comparing outputs.
2. Update `kernel.core/default-options` to wire stage predicates (e.g. `:emit-effects?`).
3. Delete the vector form once downstream tooling consumes the new API.
