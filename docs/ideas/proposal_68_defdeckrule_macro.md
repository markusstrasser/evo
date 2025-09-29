# Proposal 68 · `defdeckrule` Macro for Invariant Deck

## Current friction (Evolver)
- `src/kernel/deck.cljc:12-84` encodes invariants as literal maps stuffed into a vector. Each rule repeats keys (`:id`, `:when`, `:check`) and inlines boilerplate for location metadata.
- Adding severity levels or additional metadata (e.g., derived keys touched) means editing every entry by hand. Nothing checks that `:when` only contains valid stage keywords or that `:check` returns structured findings.
- Tooling that introspects the deck has to walk the literal vector, making it awkward to extend (e.g., toggling rules, attaching instrumentation hooks).

## Inspiration
- Malli’s `collect!` macro (`/Users/alien/Projects/inspo-clones/malli/src/malli/instrument.clj:120-175`) scans namespaces and registers function schemas with consistent metadata. The macro centralises registration, validation, and doc capture so downstream tooling can rely on uniform entries.

## Proposed change
Introduce `kernel.deck/defdeckrule`, a macro that:
1. Defines the finding function as a real var (enabling instrumentation and REPL invocation).
2. Registers metadata (id, stages, severity, docstring, produced keys) into `rules*`.
3. Validates at compile time (e.g., `:when` ⊆ `#{:pre :post :postorder}`) and hooks Malli schemas for generated findings.

```clojure
(defdeckrule node-exists
  {:doc "Parent entries must exist in :nodes"
   :when #{:pre :post}
   :level :error
   :touches #{:child-ids/by-parent :nodes}}
  [{:keys [nodes child-ids/by-parent]}]
  (for [[parent _] child-ids/by-parent
        :when (not (contains? nodes parent))]
    {:at [:child-ids/by-parent parent]
     :msg (str "parent " parent " missing in :nodes")}))
```

`run` simply reduces over `(active-rules options)` derived from the registry atom. Rules can expose optional `:auto-fix` functions or tags for grouping.

## Expected benefits
- **Declarative registry**: Metadata (doc, severity, stage applicability) sits alongside the check logic. Dashboards/tests can surface this without spelunking maps.
- **Extensibility**: Third parties can add rules by requiring the namespace and calling the macro—no need to edit the literal vector.
- **Instrumentation focus**: Macro can automatically wrap `:run` bodies with timing or tracing hooks, keeping invariant monitoring first-class.

## Trade-offs
- Macro hides the fact that rules are functions returning sequences; ensure REPL helpers (`describe-rule`) show the underlying body.
- Need to manage load order carefully so `rules*` is populated before evaluation; provide `ensure-loaded` helper for tests.

## Implementation steps
1. Replace the literal `rules` vector with an atom `rules*` and query helpers (`rules`, `active-rules`).
2. Implement `defdeckrule` to register metadata and enforce schema (Mallli schema for findings: `{::rule keyword? ::level #{:warn :error} ::at vector? ::msg string?}`).
3. Port existing rules to the macro, adding `:doc`, `:touches`, and `:severity` metadata.
4. Update `deck/run`/`findings-summary` to operate on registry data and expose introspection helpers for docs/REPL usage.
