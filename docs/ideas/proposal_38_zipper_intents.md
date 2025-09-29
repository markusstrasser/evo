# Proposal 38 · Zipper-Based Intent Planner (clojure.zip)

## Problem Statement
Transaction plans are currently manipulated as bare vectors of op maps. Every optimisation (merging, hoisting, cancelling) rewrites those vectors with ad hoc index math. This invites bugs (off-by-one, stale path context), and makes planner tooling hard to build.

## Inspiration
- **`clojure.zip`** (`/Users/alien/Projects/inspo-clones/clojure/src/clj/clojure/zip.clj`) offers persistent, purely functional zippers with navigation (`down`, `up`, `left`, `right`) and local edits.
- **rewrite-clj / cljfmt** rely on zippers to perform reliable code transformations, proving the technique scales to large trees.

## Proposed Approach
1. Model planner ASTs as tree nodes: groups, branches, op leaves. Provide `branch?`, `children`, `make-node` functions compatible with `zip/zipper`.
2. Publish `kernel.planner.zip` with utilities: `plan-zip`, `edit`, `prewalk`, `remove-noops`, `hoist`, `merge-adjacent`, `insert-before`, `replace`.
3. Encode path metadata (e.g. doc coordinate, op index) in zipper loc metadata so tools can report precise diagnostics.
4. Integrate with Proposal 36 (Slate Path Algebra) by translating between plan zipper coordinates and path vectors, enabling cross-checks.
5. Supply REPL helpers (`explain-loc`, `trace-edit`) and property tests verifying zipper rewrites preserve semantics (compose with `kernel.core/run-tx`).

## Example
```clojure
(defn prune-noops [plan]
  (->> (planner.zip/plan-zip plan)
       (planner.zip/edit
         (fn [loc]
           (if (= :noop (:op (zip/node loc)))
             (zip/remove loc)
             loc)))
       zip/root))
```

## Expected Payoff
- **Correctness**: local edits automatically maintain parent/child context, reducing accidental index corruption.
- **Traceability**: loc metadata gives us diff-friendly explanations (“removed reorder at [:body 3]”).
- **Composable tooling**: downstream features (peephole optimiser, grammar expansion) reuse the same primitives instead of reimplementing traversal.
- **Performance**: zippers avoid full copies on each edit; we pay for path context only where needed.

## Downsides & Mitigations
- **Learning curve**: zippers are less familiar than vectors. Mitigate with API wrappers (`planner.zip/remove-where`) and documentation.
- **Serialization**: zipper locs are not serializable; keep them confined to planner stages rather than storing in DB.
- **Interop**: ensure plain vectors remain supported by providing `plan->zip->plan` round trips for legacy code.

## Rollout Steps
1. Define AST node schema and implement zipper helpers.
2. Port one existing optimisation (e.g. redundant move collapse) to the new API.
3. Add property tests comparing old vs new behaviour on generated plans.
4. Document patterns for contributors (when to use zipper vs raw map).
