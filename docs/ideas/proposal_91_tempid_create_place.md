# Proposal 91 · Tempid Fusion for Create+Place

- **Date**: 2025-09-29
- **Status**: Draft

## Context & Pain
The three-op contract forces clients to issue `:create-node` followed by `:place` (often immediately). Planners, REPL scripts, and adapters duplicate "if new? -> create + place else place" boilerplate.

```clojure
;; src/core/ops.clj
(create-node db "n" :paragraph {})
(place db "n" parent {:after anchor})
```

Hot paths end up lowercasing two ops for every insert; intent authors must juggle IDs manually and guard against duplicate creates.

## Inspiration
Datascript handles entity creation by auto-allocating tempids and resolving them during `transact`. The helper `assoc-auto-tempids` walks tx data, ensuring a single declaration yields the entity with a stable ID before indexes update.

- `/Users/alien/Projects/inspo-clones/datascript/src/datascript/db.cljc` (`auto-tempid`, `assoc-auto-tempids`, lines 1300-1380)

## Proposal
1. Introduce tempid support in the kernel:
   - Accept maps like `{:op :k/create :id :temp/new}`.
   - Maintain a `:tempids` map in the interpret context mapping temp symbols → stable strings (e.g. `id-<n>`).
2. Extend `create-node` to optionally accept a placement descriptor and return both the realized ID and updated DB:

```clojure
(defn create-node [db {:keys [id node/type node/props under at]}]
  (let [id' (realize-id id)]
    {:db (-> db
             (assoc-in [:doc/nodes id'] {:node/type type :node/props props})
             (cond-> under (place id' under at)))
     :id id'}))
```

3. Update interpreter to rewrite paired `:create-node`+`:place` operations into a single tempid-aware op before validation.
4. Surface helpers in labs (e.g. `(make-node {:type :paragraph :under parent})`) that return one map instead of two ops.

## Expected Benefits
- **Less boilerplate**: intents emit one op; kernel handles placement automatically.
- **Fewer errors**: eliminates duplicate create detection by ensuring ID materialization happens once.
- **Deterministic IDs**: tempid map can incorporate seeds for reproducible traces/tests.

## Trade-offs & Risks
- Must keep backwards compatibility: existing two-op sequences should continue to work.
- Tempid state adds complexity to interpreter context; need clear lifecycle.

## Next Steps
1. Prototype tempid realization in a feature flag, verifying property tests (old vs new sequences) produce equivalent final DBs.
2. Update `labs.structure.sugar-ops/insert` to emit the fused form and delete redundant helper ops.
3. Document new op shape in the schema registry (Proposal 90).
