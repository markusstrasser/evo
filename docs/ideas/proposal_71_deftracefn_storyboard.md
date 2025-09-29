# Proposal 71 · `deftracefn` Macro for Storyboard-Integrated Functions

## Current friction (Evolver)
- Core pipeline stages (`stage:validate-schema`, `stage:apply-op`, etc.) duplicate trace/timing hooks and error wrapping (`src/kernel/core.cljc:270-320`). When we extend tracing (Proposal 34 storyboard), each function must be hand-edited to push events.
- Primitive helpers (`create-node*`, `place*`, `update-node*`) often need lightweight instrumentation for debugging. Today that means sprinkling `when trace?` checks or `tap>` calls manually.
- Consistency suffers: some functions attach `:why` metadata in errors, others don’t, making downstream diagnostics noisy.

## Inspiration
- Athens’ `defntrace` macro (`/Users/alien/Projects/inspo-clones/athens/src/cljc/athens/common/sentry.cljc:58-116`) wraps any function body with Sentry span start/finish logic, eliminating duplicated timing boilerplate while keeping function definitions terse.

## Proposed change
Create `kernel.trace/deftracefn`, a macro that wraps function bodies with storyboard trace instrumentation, standardized error maps, and optional taps.

```clojure
(deftracefn stage:apply-op
  {:why :op
   :storyboard? true
   :tap trace/push-stage!}
  [ctx]
  (assoc ctx :db-after (apply-op (:db-before ctx) (:op ctx))))
```

Macro behaviour:
1. Surround body with `(trace/with-span {:stage ...} ...)` if `:storyboard?` is true, logging enter/leave events into Proposal 34’s storyboard registry.
2. Wrap the body in `try` so `clojure.lang.ExceptionInfo` is converted into the canonical `{:error {:why ... :data ...}}` map, reusing the `:why` declared in metadata.
3. Emit trace/tap hooks (`tap stage event ctx`), record timing metrics, and attach Malli schemas to arguments/results for instrumentation.
4. Optionally expand to a plain `defn` when tracing is disabled (no runtime overhead when instrumentation is off).

## Expected benefits
- **Consistent telemetry**: Every traced function produces uniform storyboard events and error payloads, feeding the instrumentation bus without copy/paste code.
- **Less boilerplate**: Stage and primitive definitions focus on happy-path logic; the macro handles guard rails, logging, and timing.
- **LLM ergonomics**: Agents can rely on standardized error envelopes (`{:why ... :message ...}`) when planning multi-op flows, improving debuggability.

## Trade-offs
- Macro adds indirection; developers must recognise when to inspect expansions for debugging. Provide a helper `(trace/show stage:apply-op)` that prints the expanded form for transparency.
- Need to ensure the macro compiles to zero-overhead when tracing disabled; generate inline `when` checks rather than dynamic dispatch.

## Implementation steps
1. Extend `kernel.trace.storyboard` with helper APIs (`with-span`, `record-stage!`) reused by the macro.
2. Implement `deftracefn` with options for `:why`, `:tap`, `:storyboard?`, and `:instrument?` (auto Malli instrumentation in dev).
3. Port pipeline stages and high-value primitives to the macro, trimming duplicated try/catch blocks.
4. Add sanity tests ensuring macro-generated functions return identical results and produce storyboard entries when tracing is on.
