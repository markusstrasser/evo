# Proposal 86 · Namespaced Canonical DB Keys

- **Date**: 2025-09-29
- **Status**: Draft
- **Owner**: Kernel research

## Context & Pain
`core.db/empty-db` seeds anonymous keys (`:nodes`, `:children-by-parent`, `:roots`) that diverge from ADR-001's namespaced contract. Downstream code (e.g. `labs.lens`, `labs.derive.registry`) compensates with alternate maps like `:child-ids/by-parent`, duplicating adapters and validation logic.

```clojure
;; src/core/db.clj:5-17
(defn empty-db []
  {:nodes {}
   :children-by-parent {}
   :roots #{:doc :trash}
   :derived {:parent-of {} ...}})
```

The mix of bare and namespaced keys is the source of repeated schema conversions, bespoke invariants, and UI adapters that special-case `:doc`/`:trash` instead of trusting sentinel keywords.

## Inspiration
Athens keeps its storage contract namespaced (`:node/title`, `:block/children`) and treats sentinel parents as first-class values inside the same map. That alignment lets Datascript-derived indexes, navigation helpers, and migrations operate on one shape.

- `/Users/alien/Projects/inspo-clones/athens/src/cljc/athens/common_db.cljc`

## Proposal
1. Rename canonical slots to ADR namespaced keys:
   - `:nodes` → `:doc/nodes`
   - `:children-by-parent` → `:tree/children`
   - `:roots` set → `:tree/roots` vector of sentinels (e.g. `[:tree/root :bin/trash]`).
2. Port helpers to respect the new shape:
   - `core.db/derive-indexes` reads `:tree/children`, writes `:der/*` maps.
   - `core.ops/place` reads and writes `[:tree/children parent]`.
3. Remove duplicate views under `labs.lens` and `labs.derive.registry` by pointing them at the canonical keys (no shadow maps like `:child-ids/by-parent`).
4. Provide a single transition fn `(db11->db12 db)` that renames keys so existing fixtures/tests migrate instantly.

```clojure
;; after (sketch)
(defn empty-db []
  {:doc/nodes {}
   :tree/children {:tree/root [] :bin/trash []}
   :tree/roots [:tree/root :bin/trash]
   :der/indexes {:der/parent-of {} ...}})
```

## Impact
- **Code reduction**: delete bespoke adapters translating between `:children-by-parent` and `:child-ids/by-parent` (≈3 helper namespaces).
- **Consistency**: ADR naming becomes the single source of truth; instrumentation and invariants collapse to one set of walkers.
- **Plugin ergonomics**: userland lenses/macros can rely on stable namespaced slots instead of probing for multiple shapes.

## Trade-offs & Risks
- Wide rename touches every caller; requires coordinated update across CLJ/CLJS.
- Dumps a migration requirement on any stored snapshots (fixable with the one-shot `(db11->db12 db)` helper).

## Next Steps
1. Prototype rename in a branch, regenerating derivation + invariant suites to surface missing rewires.
2. Update REPL tooling (`dev.clj` inspectors) to print `:doc/*` keys.
3. Run `labs.diagnostics.invariants` against migrated fixtures to validate coverage.
