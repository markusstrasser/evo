# Proposal 72 · `defprimitive` Macro for Core Operation Registration

## Current friction (Evolver)
- Core primitives (`create-node*`, `place*`, `update-node*`, `prune*`, `add-ref*`, `rm-ref*`) share the same ceremony: normalize synonyms (`:id` vs `:node-id`), assert invariants, update the db, then register with `S/register-op!` manually (`src/kernel/core.cljc:120-230`).
- Each primitive must remember to emit instrumentation metadata (`:axes`), keep error messaging consistent, and expose Malli schemas for tooling. Drift is already visible (e.g., `prune*` registration wraps handler, `add-ref*` throws ad-hoc `ex-info`).
- When new primitives are added, we touch both the function and the registry block, increasing boilerplate and risk of forgetting axes/doc updates.

## Inspiration
- Malli’s experimental `defn` macro (`/Users/alien/Projects/inspo-clones/malli/src/malli/experimental.cljc:70-118`) augments plain functions with schema instrumentation automatically, wrapping the body to validate inputs/outputs while keeping authoring experience ergonomic.

## Proposed change
Introduce `kernel.opkit/defprimitive`, a macro that defines the function, handles argument normalization, auto-registers the handler with `kernel.schemas`, and emits consistent instrumentation metadata.

```clojure
(defprimitive create-node*
  {:doc "Idempotent create shell"
   :op :create-node
   :axes #{:existence}
   :schema ::S/create-node-op
   :normalize {:id [:node-id :id]
               :type [:node-type :type :div]
               :props [:props {}]}}
  [db {:keys [id type props]}]
  (if (lens/node-exists? db id)
    db
    (assoc-in db [:nodes id] {:type type :props props})))
```

Macro features:
1. Build a normalization function from the `:normalize` spec (synonyms + defaults). The generated wrapper produces the sanitized arg map before invoking the body.
2. Attach Malli instrumentation to the function (`::S/place*-fn` style) when running in dev, ensuring inputs/outputs stay aligned with schemas.
3. Auto-call `S/register-op!` with handler/function metadata (doc, axes, handler var), guaranteeing registry parity.
4. Optionally emit tracing hooks if combined with Proposal 71 (`:trace? true`).

## Expected benefits
- **Single declaration**: Author writes the primitive once; registration, schema wiring, and instrumentation fall out automatically.
- **Consistent error semantics**: Macro can provide helpers like `(raise! :self-edge {...})` (Proposal 74) so all primitives emit uniform error data.
- **Reduced boilerplate**: Future primitives (e.g., experimental `:split-node`) can be drafted rapidly without editing multiple sections.

## Trade-offs
- Macro must support CLJ/CLJS compilation; keep normalization helpers portable and avoid reflection.
- Need to balance flexibility (allowing custom wrappers like `prune*`’s predicate call) with macro ergonomics. Provide `:handler` override hook for advanced cases.

## Implementation steps
1. Extend `kernel.opkit` with `defprimitive` and supporting normalizer generator (`build-normalizer` from `:normalize` spec).
2. Port existing primitives to the macro, ensuring semantics match today’s functions.
3. Update `kernel.schemas/register-op!` tests to assert macro-registered handlers appear with doc/axes metadata.
4. Document the normalization DSL (synonyms, defaults, required keys), encouraging planners/LLMs to rely on canonical field names.
