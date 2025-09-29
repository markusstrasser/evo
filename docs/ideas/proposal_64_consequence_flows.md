# Proposal 64 · Consequence Flow Combinators for Multi-Step Plans

## Problem
- Multi-step kernel behaviours (e.g., “paste block + retarget selection + emit effects”) are open-coded by planners, branching into ad hoc vectors of ops. There’s no structured way to express “when op X succeeds, run sequence Y,” forcing the agent to mentally inline control flow.

## Inspiration
- Athens represents complex interactions as data using consequence composites (`athens/common_events/graph/composite.cljc:1-17`). `make-consequence-op` captures a trigger and a list of follow-on ops, which downstream resolvers can interpret uniformly.

## Proposed change
1. Introduce a `kernel.planner.flow` namespace with helpers like `(consequence trigger ops)` and `(after op & more)` that expand to tagged maps.
2. Update planner tooling to accept these flow nodes and flatten them into primitive txs, much like Athens’ resolver extracts atomics (`athens/common_events/bfs.cljc:118-198`).
3. Offer sugar helpers for common patterns: `(ops/paste target nodes)` could internally return a consequence flow that rewrites into insert/save/remove in order.

```clojure
(flow/consequence {:op :insert :id from}
  [(ops/move {:id from ...})
   (effects/scroll {:id to})])
```

## Expected benefits
- Plans stay declarative—agents chain flows without writing explicit “do X then Y” glue. Kernel evaluation unwraps the data and preserves ordering guarantees.
- Flows can carry metadata (e.g., `:undo-group`) for future features without revisiting every planner call site.
- Debugging improves: printing a plan shows a tree of consequences, mirroring high-level intent instead of a flat tx vector.

## Trade-offs
- Requires updating `apply-tx+effects*` (or a preprocessing pass) to recognize flow nodes, but the logic mirrors Athens’ existing flattening.
- Need guidelines so flows don’t become deeply nested spaghetti; provide REPL helpers to visualize the expansion just like Athens’ BFS utilities.
