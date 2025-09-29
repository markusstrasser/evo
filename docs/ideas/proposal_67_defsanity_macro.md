# Proposal 67 · `defsanity` Macro for Scenario Deck

## Current friction (Evolver)
- `src/kernel/sanity_checks.cljc:37-225` repeats the same try/catch scaffolding in every function (`test-safely`, `test-throws`). Even with Proposal 29’s scenario matrix, each check still needs a bespoke wrapper to register itself.
- Metadata (docstrings, expected behaviour) drifts easily—some checks return booleans, others maps, and nothing enforces consistent structure for downstream tooling.

## Inspiration
- `clojure.test/deftest` (`/Users/alien/Projects/inspo-clones/clojure/src/clj/clojure/test.clj:622-640`) stores the test body in var metadata and registers it with the runner automatically. Authors focus on assertions; the macro wires the result into the global registry.

## Proposed change
Add `kernel.sanity/defsanity`, a macro that declares a scenario, registers it in the central `scenarios*` deck (Proposal 29), and emits a thin wrapper for backwards-compatible function calls.

```clojure
(defsanity full-derivation
  {:doc "Tier-A and Tier-B derived maps are populated"
   :tags #{:structure}}
  [{:keys [nodes child-ids-by-parent]}]
  (let [db (assoc base-db :nodes nodes :children-by-parent-id child-ids-by-parent)
        derived (-> db registry/run :derived)]
    {:tier-a (select-keys derived tier-a-keys)
     :tier-b (select-keys derived tier-b-keys)}))
```

Macro responsibilities:
1. Capture the optional doc/tags/expectation metadata and stash it in `scenarios*`.
2. Wrap the body in standard try/catch, returning the canonical `{:scenario id :passed? bool :result ... :error ...}` shape.
3. Define a public function (`full-derivation`) that delegates to `run-scenario` for REPL callers, keeping the API stable.
4. Auto-wire Malli schemas for scenario inputs/outputs so instrumentation can verify shapes.

## Expected benefits
- **Uniform outputs**: Every scenario yields the same structured map, so REPL reporters, CLIs, and CI can consume results without adapters.
- **Decluttered definitions**: Authors only describe setup/expectations; the macro handles registration, error wrapping, and doc collation.
- **LLM friendliness**: Metadata lives with the code. Planner agents can query `scenarios*` to discover checks and reuse fixtures without scanning source files.

## Trade-offs
- Macro layer hides the actual function signature; provide `(sanity/describe :full-derivation)` helper to display expanded metadata.
- Need to ensure macro expansion works in both CLJ/CLJS builds; rely on runtime `swap!` into a shared atom rather than load-order side effects.

## Implementation steps
1. Introduce `scenarios*` atom + helper fns (`register-scenario!`, `scenario-map`) in `kernel.sanity_checks`.
2. Implement `defsanity` macro mirroring `deftest`—attach metadata, emit wrapper, update `run-all` to iterate `scenarios*`.
3. Port existing checks to the macro, deleting `test-safely`/`test-throws` once parity is confirmed.
4. Add REPL helpers (`list-scenarios`, `only`, `skip`) that filter based on tags/doc for faster feedback.
