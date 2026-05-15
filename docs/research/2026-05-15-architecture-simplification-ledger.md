# Architecture Simplification Ledger - 2026-05-15

Objective: look for a simpler architecture that improves mental model, UX, DX,
modularity, or robustness. Stop when the remaining ideas no longer delete real
complexity or block known failure modes.

## Current Ground Truth

Evo is already mostly at the simple version:

- persistent document changes reduce to `:create-node`, `:place`, and
  `:update-node`;
- view/session state is outside the DB;
- runtime dispatch is DOM event -> intent -> handler result -> transaction ->
  session patch;
- extension surfaces are intent, derived index, and render registries;
- recent redesign work already converted the big semantic ambiguities into
  transaction laws, derived-key ownership, product-state validation, keyboard
  ownership tests, and FR executable-scenario coverage.

The useful search space is therefore not "new architecture." It is removing the
last compatibility-shaped seams that contradict the small mental model.

## Raw Candidate Nodes

1. Command algebra: collapse plugin handlers and scripts into one explicit
   command result shape.
2. Product event log: derive DB and UI state from one event stream rather than
   a persistent DB plus session atom.
3. Declarative interaction table: key + mode + selection shape -> command,
   replacing split global/component keyboard ownership.
4. Render projection boundary: block AST + product state -> hiccup, with all DOM
   lifecycle behavior behind one selection reconciler.
5. Behavior triad compiler: one user-visible behavior DSL generates docs,
   fixtures, and Playwright scenarios.

## Disposition

| Node | Disposition | Reason |
| --- | --- | --- |
| Command algebra | Implemented narrow cut | The live code still allowed handler results as raw vectors or result maps, plus unknown-intent no-ops. That contradicted the documented single intent path and hid broken callers. |
| Product event log | Reject | Would make the model more ambitious, not smaller. The current DB/session split is explicit and guarded by `kernel.product-state`. |
| Declarative interaction table | Park | Keyboard ownership is now documented and tested. A full table may be useful only if another duplicate-dispatch incident appears. |
| Render projection boundary | Park | The largest UX complexity is browser/contenteditable reality, not lack of a projection abstraction. Extract only around a concrete stale-closure or lifecycle bug. |
| Behavior triad compiler | Park | FR executable coverage is already green. Compiler work would add machinery before it deletes a current manual failure mode. |

## Implemented Simplification

Intent handlers now have one result contract:

```clojure
(fn [db session intent] -> nil | {:ops [...] :session-updates {...}})
```

Removed from the architecture:

- raw vector handler returns;
- unknown-intent no-op fallback;
- tests that depended on unregistered aliases such as `:copy`,
  `:navigate-down`, and `:navigate-up`.

Why this is simpler:

- every handler result is a named product of the intent compiler;
- no caller has to remember whether a handler returns ops directly or wraps
  them;
- broken dispatch names fail at the registry boundary instead of silently
  looking like no-ops;
- pending-buffer materialization still composes with nil/no-op handlers.

## Evidence

Touched implementation:

- `src/kernel/intent.cljc`
- `src/plugins/structural.cljc`
- `src/plugins/editing.cljc`
- `src/plugins/context_editing.cljc`

Touched tests:

- `test/kernel/intent_contract_test.cljc`
- `test/integration/clipboard_scenarios_test.cljc`
- `test/plugins/structural_test.cljc`
- `test/refactor/logseq_parity_baseline_test.cljc`

Verification run:

```bash
\bb test
```

Result: 540 tests, 1765 assertions, 0 failures, 0 errors.

## Diminishing Returns

No broader breakthrough survived the repo-grounded filter in this pass. The
kernel architecture is already small. The remaining attractive ideas mostly add
abstractions around browser/editor edge cases, which is opposite the repo's
subtraction history unless a concrete incident recurs.

The next simplification worth considering is not a redesign: convert one of the
remaining manual failure-mode guardrails into a precise lint only when the
pattern is low-noise.
