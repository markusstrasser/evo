# Proposal 69 · `defresponse` Builders for Envelope Functions

## Current friction (Evolver)
- `src/kernel/responses.cljc:17-46` hand-writes five nearly identical functions: build a map, maybe assoc optional keys, validate with `rsp-schema`, return.
- Each new status requires duplicating the pattern (build map → `v!` → return). Forgetting to call `v!` or to include `:effects []` defaults is easy, especially for quick REPL experiments.
- Documentation lives only in code comments; there is no registry of available responses for tooling or CLJ/CLJS clients.

## Inspiration
- Clerk’s `defcached` macro (`/Users/alien/Projects/inspo-clones/clerk/src/nextjournal/clerk.clj:621-640`) wraps `def` so authors write the intent once; the macro handles evaluation, caching, and doc metadata automatically.

## Proposed change
Create `kernel.responses/defresponse`, a macro that generates the constructor function, applies defaults, validates, and registers metadata for tooling.

```clojure
(defresponse ok
  {:doc "Successful evaluation with db/effects payload"
   :defaults {:effects []}}
  [{:keys [db effects trace]}]
  {:status :ok
   :db db
   :effects effects
   :trace trace})
```

Macro expansion sketch:
```clojure
(do
  (defn ok [{:keys [db effects trace]}]
    (let [m (-> {:status :ok :db db}
                (assoc :effects (vec (or effects [])))
                (medley/assoc-some :trace trace))]
      (v! m)
      m))
  (swap! registry* assoc :ok {:doc "..." :fn #'ok}))
```
`registry*` powers REPL docs (`(describe :ok)`), generated API docs, or adapters that want to list supported statuses.

## Expected benefits
- **Zero-boilerplate additions**: New statuses boil down to data + body; defaults and validation are automatic.
- **Consistent envelopes**: Macro can enforce Malli schemas per status (e.g., `:error` requires `:why`), reducing runtime bugs and keeping instrumentation strong.
- **Introspection**: Central registry lets tooling (LLMs, CLIs) discover available responses and their docs in one place.

## Trade-offs
- Macro needs to support both CLJ/CLJS; rely on shared helper functions rather than CLJ-only reflection.
- Authors must learn the macro options (`:defaults`, `:coerce?`, etc.). Provide docstring and linting rules to catch misuse.

## Implementation steps
1. Introduce `responses*` atom storing metadata about each constructor.
2. Implement `defresponse` to emit the function, register metadata, and allow optional transformers (e.g., apply Malli transformers before validation).
3. Port existing constructors (`ok`, `error`, `conflict`, `redirect`, `diag`) to the macro; add docs with concrete examples for REPL usage.
4. Update `kernel.responses` tests to cover registry metadata and ensure backwards-compatible behaviour.
