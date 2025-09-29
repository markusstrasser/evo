# Proposal 28 · Node Entity Lenses

## Current friction (Evolver)
- Call sites reach deep into the DB with raw `get-in` calls, e.g. `src/kernel/introspect.cljc:19-43` and `src/kernel/sanity_checks.cljc:52-110`. Repeating `get-in db [:derived :parent-id-of id]` spreads knowledge of derived layout everywhere.
- Higher-level tooling (LLMs, UI adapters) has to remember which keys live under `:nodes` vs `:derived` vs `:refs`, making read APIs verbose and error-prone.

## Inspiration
- Datascript wraps entity lookups in `datascript/impl/entity.cljc:1-200`, returning map-like `Entity` records that lazily expose attributes, reverse refs, and cached lookups. Consumers treat entities like Clojure maps instead of spelunking indexes manually.

## Before vs After
```clojure
;; before (src/kernel/introspect.cljc:27-41)
(let [pid (get-in db [:derived :parent-id-of])
      cid (get-in db [:derived :child-ids-of])
      root (or (-> db :roots first) "root")]
  (loop [x id acc []]
    (if x (recur (pid x) (conj acc x)) acc)))

;; caller must remember derived keys and fallback root logic
```

```clojure
;; after (sketch)
(require '[kernel.entity :as ent])

(defn breadcrumbs [db id]
  (let [node (ent/entity db id)]
    (->> (iterate ent/parent node)
         (take-while some?)
         reverse
         (map (fn [e]
                {:id (ent/id e)
                 :children (map ent/id (ent/children e))})))))

;; kernel.entity
(defrecord NodeEntity [db id]
  ent/IEntity
  (id [_] id)
  (type [_] (get-in db [:nodes id :type]))
  (props [_] (get-in db [:nodes id :props] {}))
  (parent [_] (some-> (get-in db [:derived :parent-id-of id]) (entity db)))
  (children [_] (map #(entity db %) (get-in db [:derived :child-ids-of id] []))))

(defn entity [db id] (when (get-in db [:nodes id]) (->NodeEntity db id)))
```

## Benefits
- *Ergonomic reads*: Callers work with a small protocol (`ent/id`, `ent/parent`, `ent/children`, `ent/attr`) instead of raw nested maps.
- *Lazy caching*: Just like Datascript entities, we can memoize expensive lookups (e.g., computed attributes) per entity.
- *Adapter-ready*: Downstream bridges (Slate, Logseq, Figma) can ship these entities to LLMs or UI renderers as self-describing maps.

## Trade-offs
- Must decide on caching semantics (e.g., simple memo vs. watchers). Keep it pure by recomputing each call or store ephemeral cache with `volatile!` like Datascript.
- Entities hold a pointer to the original DB; accidental mutation of the DB map would invalidate them. Document that DBs stay immutable.
- Need to ensure CLJS friendliness (no `volatile!` on the wrong side). Follow Datascript’s cross-platform patterns for parity.

## Next steps
1. Create `kernel/entity.cljc` with a lightweight `NodeEntity` record, an `IEntity` protocol, and convenience functions (`entity`, `parent`, `children`, `attrs`).
2. Replace hot call sites in `kernel.introspect` and REPL tooling to consume the new API, proving ergonomics without touching core logic.
3. Add property tests comparing `entity` lookups against the raw `:nodes`/`:derived` maps to guarantee fidelity across refactors.
