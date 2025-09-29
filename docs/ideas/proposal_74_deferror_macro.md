# Proposal 74 · `deferror` Macro for Uniform Kernel Exceptions

## Current friction (Evolver)
- Kernel primitives throw `ex-info` with ad hoc payloads (`src/kernel/core.cljc:167-220`). Messages vary (“place*: parent-id does not exist”, “add-ref*: self-edge not allowed”), and some errors forget to include the offending op or `:why` keyword.
- Error reporting downstream (responses, storyboard) relies on consistent `{:why kw :data map}` shapes. Today we fix this in catch blocks, but missing data still leaks through.
- Duplicated `throw (ex-info (str ...) {...})` makes it easy to forget instrumentation fields (e.g., `:axes`, `:op-index`).

## Inspiration
- Datascript’s `raise` macro (`/Users/alien/Projects/inspo-clones/datascript/src/datascript/util.cljc:15-24`) centralises exception creation, ensuring every error carries structured data and formatted message fragments.

## Proposed change
Add `kernel.error/deferror` and companion helpers (`raise!`, `assert!`) to build uniform exception data.

```clojure
(ns kernel.error)

(deferror self-edge
  {:doc "Prevent refs pointing to themselves"
   :message "Self-edge not allowed"
   :axes #{:references}})

;; usage inside add-ref*
(assert! (not= src dst)
         (self-edge {:rel rel :src src :dst dst}))
```

Macro behaviour:
1. Register error metadata (doc, default message, axes) in `errors*` for tooling.
2. Generate a function (e.g., `self-edge`) that merges provided data with defaults and constructs the final `ex-info` payload, including `{:why :kernel.error/self-edge :data ... :axes ...}`.
3. Provide `assert!` helper that throws structured errors when predicate fails, mirroring today’s `assert` but with instrumentation-friendly payloads.
4. Ensure response builders automatically convert raised errors into standardized `:error` maps without extra ceremony.

## Expected benefits
- **Consistent diagnostics**: All kernel exceptions carry `:why`, `:axes`, and contextual data, making responses and trace outputs uniform.
- **Less boilerplate**: Primitives replace manual `throw (ex-info ...)` with `assert!`/`raise!`, reducing ~3-4 LOC and preventing stringly-typed messages.
- **Doc-ready registry**: Tooling (LLMs, docs) can list known error ids with explanations, aligning with transparency goals.

## Trade-offs
- Macro adds an indirection layer; developers must learn to declare error ids up-front. Provide `describe-error` helper to show metadata quickly.
- Need to retrofit existing asserts/throws carefully to preserve behaviour; keep old message text in `:message` until clients migrate.

## Implementation steps
1. Implement `kernel.error` namespace with `errors*` registry, `deferror` macro, and helpers `raise!`, `assert!`.
2. Port primitives and pipeline stages to use the helpers, removing ad hoc `ex-info`/`assert` strings.
3. Update response + storyboard code to rely on structured `:why` namespaced keywords (e.g., `:kernel.error/self-edge`).
4. Extend sanity checks to ensure every thrown error id is registered (no anonymous `ex-info`).
