# Proposal 63 · Atomic Op Builders for Planner Ergonomics

## Problem
- LLM agents currently assemble kernel ops by hand-crafting maps (e.g., `{:op :insert ...}`). Each plan must remember default props, optional anchors, and invariants scattered across the repo.
- This raises cognitive load: you have to read handler implementations (`src/kernel/sugar_ops.cljc`) to know the shape, and typos make it to runtime.

## Inspiration
- Athens factors its graph ops into tiny constructor fns (`athens/common_events/graph/atomic.cljc:11-89`). Every atomic op has a `make-*-op` helper that returns a fully-formed map with `:op/type`, `:op/args`, and metadata like `:op/atomic?`.

## Proposed change
1. Extend `kernel.opkit/defop` (or a sibling macro) to auto-generate `make-*` builder fns for each registered op. The macro already knows the Malli schema, so it can emit a constructor that applies defaults and validates required keys.
2. Expose these builders in a namespace like `kernel.ops.builders`, giving agents a single import when composing txs:

```clojure
(defop :insert {...}) ;; today
;; new helper emitted alongside:
(defn make-insert [{:keys [id parent-id type props pos] :as op}] ...)
```

3. Allow optional keyword args and friendly error messages (leveraging the schema). For composite plans, compose builders instead of writing raw maps.

## Expected benefits
- Agents stay in “intent” space—`(ops/make-insert {...})` reads like a DSL and guarantees canonical defaults. No more hunting for `:node-type` vs `:type` mismatches.
- Kernel team gets free documentation: `cljdoc` can render these constructors, and REPL `doc` output shows parameter options immediately.
- Future ops follow the same template; macro emits both handler registration and builder, slimming boilerplate.

## Trade-offs
- Adds a small amount of macro work, but the builder body is just wrapping the existing schema defaults.
- If we add dynamic metadata later, ensure the macro forwards `:axes` etc. into the builder so instrumentation stays consistent.
