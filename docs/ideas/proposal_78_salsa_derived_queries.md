# Proposal 78 · Salsa Query Groups for Derived State

## Problem
Derived data is recomputed wholesale, and we do not record which base slots each pass depends on (see Proposal 49). We need a lighter-weight mechanism to memoise derived values per input id without hand-writing caches.

## Inspiration
Salsa organises derived values into query groups (`db`, `tracked`, `interned`) and lets each query declare its inputs. The runtime dirties only the affected queries and lazily recomputes them.cite/Users/alien/Projects/inspo-clones/salsa/src/lib.rs:1-83

## Proposed Change
1. Add a `kernel.derive/query` macro mirroring `salsa::tracked`:
   ```clojure
   (derive/defquery children-index
     {:inputs [:children-by-parent-id parent]}
     (vec (map-indexed vector (lens/children-of db parent))))
   ```
2. Maintain a registry mapping `query-id -> {::inputs #{slot ...} ::compute fn}`.
3. When primitives emit `:touched-slots`, mark the dependent queries dirty and recompute them on demand.

### Before
All passes run every time:
```clojure
(reduce (fn [db pass] (pass db)) db derive/passes)
```

### After
```clojure
(def db' (derive/run-queries db touched-slots))
```
where `run-queries` only evaluates queries whose input slots intersect `touched-slots`.

## Expected Benefits
- Near drop-in incremental derives without embracing the full Adapton graph.
- Query metadata doubles as documentation: developers see which base slots each derived value depends on.

## Trade-offs
- We must carefully design slot ids so they stay stable; otherwise caches churn.
- Lazy recomputation can hide bugs if invariants read stale queries—tests must compare against eager runs.

## Roll-out Steps
1. Implement the macro/registry plus an eager fallback (`derive/run-all`) for debugging.
2. Port one inexpensive pass (`:index/by-parent`) to the new API and benchmark.
3. Gradually migrate remaining passes, deleting the legacy pass vector afterwards.
