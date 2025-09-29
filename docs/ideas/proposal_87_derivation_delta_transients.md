# Proposal 87 · Incremental Derivation via Delta Passes

- **Date**: 2025-09-29
- **Status**: Draft

## Context & Pain
`core.interpret/interpret` calls `db/derive-indexes` after each accepted op, rebuilding every derived map regardless of the touched subtree.

```clojure
;; src/core/interpre t.clj:174-176
(let [normalized-ops ...
      [final-db issues] (validate-ops db normalized-ops)
      derived-db (db/derive-indexes final-db)] ...)
```

`derive-indexes` walks the entire tree, allocating new maps for `:parent-of`, `:index-of`, sibling links, and traversal order on every op. Benchmarks show O(|tree|) work even for `:update-node` that only changes props.

## Inspiration
Datascript avoids global recomputes by emitting targeted index updates inside `with-datom`. Each datom change mutates transient indexes and promotes them back to persistent structures once per transaction.

- `/Users/alien/Projects/inspo-clones/datascript/src/datascript/db.cljc` (`with-datom`, `queue-tuples`, lines ~410-520)

## Proposal
1. Wrap primitive operations in delta emitters:
   - `core.ops/create-node` returns `[db' {:der/touches #{id}}]`.
   - `core.ops/place` emits both the old parent and new parent for recompute.
2. Introduce `core.db/apply-derivation-delta` that takes the delta map and updates `:der/indexes` using transients:

```clojure
(defn apply-derivation-delta [db {:keys [parents removed-edges added-edges]}]
  (persistent!
    (reduce
      (fn [t parent]
        (-> t
            (update-in [:der/indexes :der/parent-of] #(derive/patch-parent % parent))
            (update-in [:der/indexes :der/index-of] #(derive/patch-index % parent))))
      (transient db)
      parents)))
```

3. Adjust interpreter to thread deltas:

```clojure
(reduce (fn [{:keys [db]} op]
          (let [{db' :db delta :delta} (apply-op-with-delta db op)]
            {:db (apply-derivation-delta db' delta)}))
        {:db db}
        normalized-ops)
```

4. Keep a guard rail `(assoc db :der/indexes nil)` path so `labs.diagnostics.invariants/check-invariants` can force a full recompute when debugging.

## Expected Benefits
- **Time**: reduce post-op derivation from O(|tree|) to O(|local subtree|).
- **Allocations**: transient patching removes repeated `into {}`/`assoc` churn inside `derive-indexes`.
- **Clarity**: op handlers declare exactly which parents/siblings they mutate.

## Trade-offs & Risks
- Delta bookkeeping increases primitive surface area; mistakes could desync caches.
- Requires additional instrumentation (e.g. sampling full derivations in CI) to catch drift.

## Rollout Plan
1. Spike `apply-derivation-delta` using the Datascript pattern (`advance-max-eid`, `with-datom`) as a reference for transient map updates.
2. Flip interpreter behind `:derivation-mode :full|:delta` flag with property tests comparing results.
3. Remove legacy recompute once the delta path stays green across integration tests.
