# Proposal 73 · `defeffect` Macro for Effect Detection Registry

## Current friction (Evolver)
- `kernel.effects/detect` currently inlines effect construction (`src/kernel/effects.cljc:5-16`). Upcoming proposals (57 morphdom diffs, 40 incremental deltas) will add multiple detectors with shared scaffolding (cause metadata, ids, severity).
- Without structure, every new effect emitter must remember to fill `:cause`, attach op indices, and maintain consistent shape for adapters. Debugging which detector fired becomes guesswork.
- We lack a registry for tooling (`kernel.responses`/LLMs) to understand which effect types exist or to toggle them during experiments.

## Inspiration
- Portal’s logging macros (`/Users/alien/Projects/inspo-clones/portal/src/portal/console.cljc:71-80`) funnel every log call through a single `capture` helper, guaranteeing consistent payloads and metadata while giving consumers a central hook.

## Proposed change
Create `kernel.effects/defeffect`, a macro for declaring effect detectors that register themselves and produce consistent envelopes.

```clojure
(defeffect scroll-into-view
  {:doc "Scroll inserted nodes"
   :when #{:insert}
   :severity :info}
  [{:keys [prev next op-index op]}]
  (when (= :insert (:op op))
    [{:effect :view/scroll-into-view
      :ids [(:id op)]
      :cause {:op-index op-index :op (:op op)}}]))
```

Macro responsibilities:
1. Register detector metadata (doc, applicable ops, severity, feature flag key) in an atom `detectors*`.
2. Wrap the body in exception guards so detectors never crash the pipeline—return `[]` on error with optional `:diagnostic` effect when debugging.
3. Compose the main `detect` function by reducing over active detectors, concatenating their results, and optionally emitting trace info (`{:detector :scroll-into-view :count 1}`).
4. Support toggles (`with-effects #{:scroll-into-view :zipper-diff}`) for REPL/workflows.

## Expected benefits
- **Consistent payloads**: Macro enforces Malli schema for effects (e.g., `{:effect keyword? :cause map?}`), keeping adapters simple and instrumentation robust.
- **Discoverability**: Tooling can list registered detectors with docs/severity, aligning with the project’s goal of transparent instrumentation.
- **LLM control**: Agents can selectively enable detectors depending on context (prototyping vs. prod) without editing code.

## Trade-offs
- Additional registry layer adds indirection; provide `(effects/describe :scroll-into-view)` helper so developers can inspect definitions quickly.
- Need to manage performance: detectors should short-circuit gracefully. Macro can allow `:fast-path?` hint to inline simple predicates.

## Implementation steps
1. Add `detectors*` atom + helper fns (`active-detectors`, `describe-detector`).
2. Implement `defeffect` macro; support optional `:tap` for instrumentation and `:requires` derived keys to ensure detectors declare dependencies.
3. Port existing logic in `detect` to a `defeffect` and add new detectors for upcoming proposals (e.g., keyed diff, GC notifications).
4. Extend sanity checks to assert all registered detectors return vectors of effect maps that pass the shared schema.
