# Proposal 37 · Datafy & Nav Bridge for Kernel DBs (clojure.datafy)

## Problem Statement
Kernel consumers – humans, devtools, and LLM agents – still spelunk Evolver DBs as raw nested maps. Even with helpers in `kernel.introspect`, callers reconstruct parent/child relationships by hand, reimplementing traversal logic and leaking internal layout. This slows debugging, produces brittle scripts, and increases the surface area that must remain stable forever.

## Prior Art
- **clojure.datafy** (`/Users/alien/Projects/inspo-clones/clojure/src/clj/clojure/datafy.clj`) wraps complex objects in `Datafiable`/`Navigable` implementations so REBL, inspectors, and tools explore them lazily.
- **REBL & Portal** demonstrate that, once datafy is available, users navigate with minimal friction while the implementation keeps control of laziness and shape.

## Proposed Approach
1. Introduce `kernel.datafy` that exposes two primary entry points: `(db-view db)` returning a `Datafiable` wrapper and `(entity-view db id)` returning a `Navigable` entity lens.
2. Implement `clojure.core.protocols/Datafiable` such that `datafy` emits a lightweight summary map (`{:nodes … :roots … :derived-keys …}`) while metadata keeps the original DB (`:clojure.datafy/obj`).
3. Implement `Navigable` to intercept `(nav summary key value)` calls. For example, navigating `:nodes` returns lazy sequences of `entity-view` wrappers, and navigating `:indexes` returns on-demand snapshots of derived maps.
4. Extend `kernel.introspect` helpers (`diff`, `trace`, etc.) to accept both raw DBs and `db-view` wrappers so existing code keeps working.
5. Ship reference utilities – `focus-node`, `explain-entity`, `pp-datafy` – that demonstrate idiomatic usage in REPL sessions and agent workflows.

## Worked Example
```clojure
(require '[clojure.datafy :as datafy]
         '[kernel.datafy :as kdata])

(let [db-view (kdata/db-view (:db tx-result))
      summary (datafy/datafy db-view)]
  {:roots (:roots summary)
   :first-child
   (-> summary
       (datafy/nav :nodes (first (:roots summary)))
       (datafy/nav :children nil)
       first
       (datafy/datafy))})
```

## Benefits
- **Encapsulation**: external callers only depend on the `datafy` contract; we can reorganise internal maps without breaking tools.
- **Lazy exploration**: `Navigable` lets us defer expensive derived recomputation until a user drills into it.
- **LLM ergonomics**: agents can ask for “nav to parent” rather than reconstructing indices by string manipulation.
- **Inspector integration**: Portal/REBL/Swing inspector automatically respect `datafy`, giving Evolver an instant GUI story.

## Cost & Risks
- **Protocol overhead**: contributors unfamiliar with `datafy` must learn the idiom; mitigate with docs and examples.
- **Accidental realization**: naive implementations could walk entire trees; enforce laziness and guardrails (`:max-items`, pagination).
- **Schema drift**: wrappers must stay in sync with derived fields; add property tests comparing `nav` results to direct map lookups.

## Alternatives Considered
- Continue expanding bespoke inspectors in `kernel.introspect` – rejected because it scales poorly and keeps callers tied to map guts.
- Adopt third-party graph viewers – heavier lift and still requires a structured API.

## Migration Plan
1. Prototype `kernel.datafy` for DB + node entities.
2. Update docs (`docs/kernel_simplification_proposals.md`, REPL guides) with usage patterns.
3. Port existing scripts in `docs/ideas` and `scripts/` to rely on `datafy` wrappers.
4. Add regression tests ensuring `datafy`/`nav` round trip to the original DB.
