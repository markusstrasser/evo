# Proposal 66 · `defpass` Macro for Derivation Registry

## Current friction (Evolver)
- `src/kernel/derive/registry.cljc:10-118` stores derivation passes as raw maps inside a single vector. Adding a pass means touching the literal, hand-writing `:id`, `:after`, `:doc`, and the `:run` fn, then remembering to keep the ordering stable.
- Instrumentation, timing, and docs live elsewhere; nothing enforces that a pass declares the axes it touches or the derived keys it produces. Missed metadata silently degrades tooling.
- Removing or disabling a pass requires manual filtering in `run`/`timing-run`, scattering conditionals throughout the file.

## Inspiration
- Pathom’s `defresolver` macro (`/Users/alien/Projects/inspo-clones/pathom3/src/main/com/wsscode/pathom3/connect/operation.cljc:405-520`) wraps resolver definitions with metadata capture, argument normalization, and automatic registry insertion. The macro guarantees every resolver carries consistent `::input`, `::output`, and doc data that Pathom can index.

## Proposed change
Introduce `kernel.derive.registry/defpass`, a macro that expands to:
1. Define the pass body as an actual function (better stack traces, easy REPL calling).
2. Register metadata (id, docstring, deps, produced derived keys, optional axes) into a central `passes*` atom.
3. Emit compile-time validation (e.g., ensure `:after` keys correspond to already-declared passes) and attach Malli schemas for instrumentation hooks.

```clojure
(ns kernel.derive.registry)

(defpass parent-id-of
  {:doc "Populate :derived/parent-id-of from adjacency"
   :after #{}
   :produces #{:derived/parent-id-of}}
  [db]
  (reduce-kv
    (fn [m parent child-ids]
      (reduce #(assoc %1 %2 parent) m child-ids))
    db
    (:children-by-parent-id db)))
```

Macro expansion (sketch):
```clojure
(do
  (defn parent-id-of-pass [db]
    ...body returning updated db...)
  (swap! passes* assoc :parent-id-of
         {:fn #'parent-id-of-pass
          :after #{}
          :produces #{:derived/parent-id-of}
          :doc "Populate ..."}))
```
`run`/`timing-run` reduce over `(ordered-passes)` compiled from `passes*`. Feature flags (`:only`, `:exclude`) operate on metadata instead of manually filtered vectors.

## Expected benefits
- **Single source of truth**: passes declare dependencies and outputs alongside code. Tooling (timers, docs, REPL browse) can query `passes*` instead of parsing literals.
- **Safer evolution**: macro can assert that every pass returns a db, emits declared keys, and optionally wrap the body with timing/trace hooks, keeping instrumentation focus front-and-centre.
- **Lower cognitive load**: adding/removing a pass is one form; the macro handles ordering and registry updates, reducing chances of missing entries in multiple places.

## Trade-offs
- Macro indirection means contributors must learn `defpass` semantics. Mitigate with `macroexpand-1` examples in docs and a `clj-kondo` hook to lint metadata.
- Load order matters: passes defined across namespaces should require `kernel.derive.registry` before calling `ordered-passes`. Provide explicit API (`reg/register!`) for third-party additions.

## Implementation notes
1. Add `passes*` atom + helpers (`ordered-passes`, `describe-pass`, `enable!`) inside `kernel.derive.registry`.
2. Port existing passes to `defpass`, preserving functionality while trimming boilerplate.
3. Update `timing-run`/`run` to consume the registry instead of the literal vector and add instrumentation hooks directly in the macro (e.g., optional `:tap` fn).
4. Extend sanity tooling to assert every registered pass advertises the derived keys it populates (aligns with instrumentation/invariant focus).
