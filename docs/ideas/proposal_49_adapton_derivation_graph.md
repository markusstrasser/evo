# Proposal 49 · Adapton-Style Derivation Dependency Graph

## Problem
- Derivation registry (`src/kernel/derive/registry.cljc:21-209`) recomputes passes sequentially without recording dependency edges per node. When planners need incremental updates (which node invalidated which derived keys) we fall back to full recomputation.
- Debugging derived corruption requires manual tracing; we cannot explain *why* `:preorder` changed or which primitive touched a dependency.

## Inspiration
- **Adapton’s DCG engine** maintains an explicit dependency graph between computations and values, enabling dirtying and recomputation (`/Users/alien/Projects/inspo-clones/adapton.rust/src/engine.rs:1-120`). The engine records edges, supports incremental recompute, and exposes reflective APIs for visualization.

## Proposed change
1. Extend derivation passes to emit dependency metadata: each pass reports `(register! derived-key dependencies)`. For example, `:index-of` depends on `[:child-ids/by-parent parent-id]`.
2. Build a lightweight dependency graph (map of derived key → set of upstream slots) maintained alongside `:derived`. Store it under `:derived-meta :deps`.
3. Expose `derive/dirty` that accepts touched base keys (e.g. parents/children mutated) and walks the dependency graph to recompute only affected passes, similar to Adapton’s dirty/clean sweep.
4. Provide introspection helpers (`kernel.derive.graph/explain`) to show why a derived value changed between snapshots.

```clojure
;; after (sketch)
(defn run-pass [{:keys [deps db]} {:keys [id run requires]}]
  (let [before (select-keys db requires)
        db' (run db)
        after (select-keys db' requires)]
    {:db db'
     :deps (assoc deps id {:requires requires
                           :fingerprint (hash after)})}))

(defn dirty [graph touched]
  (loop [queue touched, dirty-passes #{}]
    (if-let [k (first queue)]
      (let [affected (graph k)]
        (recur (into (rest queue) affected)
               (into dirty-passes affected)))
      dirty-passes)))
```
- `graph` stores reverse edges; `dirty` returns passes to recompute, enabling incremental runs.

## Expected benefits
- Enables O(|affected|) derivation updates, cutting hot path cost for localized edits.
- Supplies rich explanations for LLMs: we can answer “`index-of` changed because `child-ids/by-parent` for `node-42` mutated.”
- Lays groundwork for live visualizations à la Adapton Lab (Proposal 47 can render the dependency graph).

## Trade-offs
- Requires storing extra metadata; keep it compact (sets of keywords/ids) and allow opting out in resource-constrained adapters.
- Incremental invalidation must be thoroughly tested to avoid stale caches; fall back to full `registry/run` under `:debug/full-derive?` flag.

## Roll-out plan
1. Teach each pass to declare dependencies and fingerprints; compare fingerprints to detect actual change before enqueuing downstream passes.
2. Implement `derive/dirty-run` that takes a set of touched ids and recomputes only necessary passes (backed by property tests comparing to full derivation).
3. Document the dependency graph format so planners and tooling can reuse it for explanations and instrumentation.
