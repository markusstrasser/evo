# Proposal 50 · Javelin-Style Formula Cells for Derived Data

## Problem
- Every transaction loop calls `registry/run` (full recompute) regardless of the number of mutated nodes (`src/kernel/core.cljc:353-379`).
- Even with Proposal 49’s dependency metadata, wiring incremental updates manually is error-prone; we lack a declarative way to express “`index-of` depends on `child-ids/by-parent`”.

## Inspiration
- **Javelin’s `defc`/`defc=` macros** treat reactive values as formula cells, auto-updating downstream cells when dependencies change (`/Users/alien/Projects/inspo-clones/javelin/src/javelin/core_clj.clj:97-104`). Formulas are declared once; the runtime ensures consistent propagation.

## Proposed change
1. Encode derived fields as `cell`s backed by the canonical DB. For example, `child-ids-of` becomes `(cell= (derive-child-ids (:child-ids/by-parent db-cell)))`.
2. Maintain a single mutable cell (`db-cell`) pointing to the current database snapshot. When primitives mutate the DB, transact via `(reset! db-cell new-db)`, letting formula cells recompute automatically.
3. Expose pure getters (`derived/get :index-of id`) that deref the relevant cell, ensuring caches are memoized by the underlying reactive graph.

```clojure
;; sketch
(def db-cell (atom initial-db))
(def child-ids-of (j/defc= (compute-child-ids (:child-ids/by-parent @db-cell))))
(def index-of (j/defc= (compute-index child-ids-of)))

(defn apply-primitive [db op]
  (let [db' (primitive db op)]
    (reset! db-cell db')
    db'))
```
- `compute-index` reuses pure helpers; Javelin caches results until dependencies mutate, so repeated lookups are cheap.

## Expected benefits
- Automatic dependency tracking simplifies incremental derivation (no manual dirty bookkeeping). Derived fields update lazily on demand but stay in sync.
- Encourages more granular derived surfaces—new derived values cost little because Javelin handles invalidation.
- Aligns with existing instructions favouring synchronous/pure patterns: cells are pure formulas; side effects remain in primitives.

## Trade-offs
- Introduces reliance on Javelin (CLJ/CLJS). Need to ensure tree operations do not run concurrently; wrap `reset!` calls to maintain determinism.
- Javelin macros expand to atoms and watchers; we must guard against accidental memory leaks by disposing cells when unused (e.g. use weak references or explicit `dispose!`).

## Migration steps
1. Wrap current `registry/run` inside a `defc` prototype to validate performance on hot paths.
2. Provide escape hatch: when cells disabled (e.g., on server), fallback to existing `registry/run` pipeline.
3. Add sanity checks comparing cell-driven derived maps to old outputs across randomized transactions (test namespace can drive both backends).
