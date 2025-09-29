# Proposal 75 · `defschema` Macro for Malli Registry Entries

## Current friction (Evolver)
- `src/kernel/schemas.cljc:8-90` hand-writes a massive literal map for every schema (`::create-node-op`, `::sugar-op`, etc.). Adding a new op requires editing the map, recompiling, and hoping the keyword is unique.
- Documentation and defaults live elsewhere; nothing links the schema entry to op axes or builder docs. LLM agents must read raw Malli forms to understand arguments.
- Maintaining derived forms (e.g., `::sugar-op` multi schema) invites mistakes—forgetting to update the union leads to validation failures at runtime.

## Inspiration
- Datascript’s `deftrecord` macro (`/Users/alien/Projects/inspo-clones/datascript/src/datascript/parser.cljc:14-52`) extends record definitions with protocol implementations automatically, avoiding manual duplication and guaranteeing consistent metadata across records.

## Proposed change
Introduce `kernel.schemas/defschema`, a macro to declare schema entries with metadata and optional derivations, auto-registering them into the Malli registry.

```clojure
(defschema ::insert-op
  {:doc "Composite insert sugar op"
   :op :insert
   :axes #{:existence :topology}
   :summary "Create node + place"
   :union ::sugar-op}
  [:map
   [:op [:= :insert]]
   [:node-id ::node-id]
   [:parent-id ::node-id]
   [:node-type {:optional true} keyword?]
  [:props {:optional true} map?]
   [:anchor {:optional true} ::anchor]])
```

Macro capabilities:
1. Register the compiled schema in `registry*` via `m/schema`, ensuring `::insert-op` resolves on both CLJ/CLJS.
2. Attach metadata (doc, axes, summary) so tooling (`kernel.opkit/defop`, doc generators) can surface argument descriptions automatically.
3. Optionally update unions/multi schemas declared via `:union`/`:multi` keys, eliminating manual duplication when adding a new variant.
4. Provide `describe-schema` helper that prints the doc and Malli-form for REPL/LLM consumption.

## Expected benefits
- **Declarative schemas**: Each schema lives in one place with docs, axes, and relationships, aligning with the repository’s instrumentation-first ethos.
- **Safer unions**: Macro-maintained unions prevent forgetting to add new ops to `::sugar-op`, keeping validation accurate.
- **Better tooling**: Operation builders/macros can query schema metadata (doc, axes, defaults) to generate editors or LLM prompts automatically.

## Trade-offs
- Macro adds load-order requirements—schema definitions must run before code that queries `registry`. Provide `ensure-schema!` to lazily load when necessary.
- Need to balance flexibility (support arbitrary Malli forms) with helpful defaults. Keep the macro thin: compile the user-provided form, don’t invent DSLs.

## Implementation steps
1. Introduce `defschema` macro in `kernel.schemas`, along with `registry*` (atom) replacing the literal map.
2. Refactor existing schema declarations through the macro, ensuring tests for `validate-op!`/`validate-tx!` continue to pass.
3. Extend `S/register-op!` to pull doc/axes metadata from schema definitions when available, reducing duplication across macros.
4. Update docs to show how operation macros (`defop`, `defprimitive`) can introspect schema metadata for builder/REPL help.
