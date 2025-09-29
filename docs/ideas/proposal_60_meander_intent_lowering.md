# Proposal 60 · Meander Rewrite Pass for Sugar Ops

## Problem
- `src/kernel/sugar_ops.cljc:11-68` hand-wires each sugar op as imperative `let` chains. Adding a new intent means duplicating boilerplate (`->` threading, guard throws) while keeping derived anchors in sync.
- Behaviour becomes implicit: the only way to understand lowering is to read each function, which burdens the REPL-driven agent (and makes auto-generated docs harder).

## Inspiration
- Meander’s pattern/rewrite DSL expresses complex structural transformations declaratively (`meander/examples/datascript.cljc:88-138`). The `m/rewrite` clauses document semantics and automatically expand nested cases.

## Proposed change
1. Define a declarative rewrite table that maps high-level sugar op maps onto primitive op sequences.
2. Use `m/rewrite`/`m/cata` to normalize optional keys (defaults, anchors) and emit canonical primitive ops.
3. Keep `defop` as a thin shell that calls the rewrite and feeds the resulting primitive list through `kernel.core/apply-op`.

```clojure
(def sugar->primitives
  (m/rewrite op
    {:op :insert :node-id ?id :parent-id ?parent
     :node-type (m/or ?type :div) :props (m/or ?props {}) :anchor (m/or ?anchor :last)}
    [{:op :create-node :id ?id :type ?type :props ?props}
     {:op :place :id ?id :parent-id ?parent :pos ?anchor}]

    {:op :move :node-id ?id :target-parent-id ?parent :anchor (m/or ?anchor :last)}
    [{:op :place :id ?id :parent-id ?parent :pos ?anchor}]
    ...))
```

- Sugar lowering becomes data-driven: REPL docs can just print the rewrite table, and agents can extend via `clojure.core/into` without editing functions.
- Branches like `:move`’s guard (`from-parent-id` check) become dedicated `m/guard` clauses instead of nested `when`.

## Expected benefits
- Dramatically lower cognitive load: the agent can inspect/patch the rewrite table in one place, aligning with how Meander documents Datalog pulls.
- Adding a new sugar intent is one declarative clause; the primitive contract stays uniform and easier to test with property-based checks over the rewrite output.
- Easier debugging at the REPL: `(m/rewrite ...)` returns plain data so we can diff transforms without executing side effects.

## Trade-offs
- Introduces Meander dependency in sugar namespace; acceptable because the macro phase runs at build/load time and performance isn’t critical.
- Developers must learn Meander’s syntax, but the similar pattern already documented in Proposal 46 (transaction normalization) reduces onboarding friction.
