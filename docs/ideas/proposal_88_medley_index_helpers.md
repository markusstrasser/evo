# Proposal 88 · Medley Helpers for Derived Indexes

- **Date**: 2025-09-29
- **Status**: Draft

## Context & Pain
`core.db` implements four bespoke reducers (`compute-parent-of`, `compute-index-of`, `compute-siblings`, `compute-traversal`) that each re-walk the same adjacency map with hand-written `for`/`reduce-kv` loops.

```clojure
;; src/core/db.clj:19-43
(defn- compute-parent-of [children-by-parent]
  (into {}
        (for [[parent children] children-by-parent
              child children]
          [child parent])))

(defn- compute-index-of [children-by-parent]
  (into {}
        (for [[_ children] children-by-parent
              [idx child] (map-indexed vector children)]
          [child idx])))
```

These loops are verbose, easy to drift apart, and make unit tests repeat the same fixtures. Updating one invariant often requires editing four different reducers.

## Inspiration
Medley already ships primitives like `map-vals`, `map-kv-vals`, and `assoc-some` purpose-built for associative transformations.

- `/Users/alien/Projects/inspo-clones/medley/src/medley/core.cljc` (`map-vals`, `map-kv-vals`, lines 1-120)

## Proposal
1. Add `medley.core` as a dependency (already present under `labs` tooling) and lean on its helpers:

```clojure
(defn- compute-parent-of [children-by-parent]
  (reduce-kv (fn [parents parent child-ids]
               (reduce #(assoc %1 %2 parent) parents child-ids))
             {}
             children-by-parent))

;; becomes
(def compute-parent-of
  (comp (partial medley/map-vals #(vec %))
        ; ensures vectors for tree utils
        (partial medley/reduce-map
                 (fn [xf]
                   (fn [parents parent child-ids]
                     (reduce #(xf parents %2 parent) parents child-ids))))))
```

2. Collapse sibling computation using `partition-all` + `map-kv-vals` to avoid manual `concat` and guard clauses.
3. Refactor traversal into a shared helper that returns both `:der/pre` and `:der/post`, leveraging `medley/queue` for breadth-first order when needed.

## Expected Benefits
- **Less code**: drop ~60 lines of custom reducers, rely on well-tested helpers.
- **Readability**: derived-map intent expressed declaratively (`map-vals`, `reduce-map`) instead of nested `for` loops.
- **Consistency**: easier to share helpers between `core.db` and `labs.derive.registry`.

## Trade-offs & Risks
- Introduces an extra dependency at kernel level (though Medley is already vendored for tooling). Ensure CLJS bundle impact is acceptable.
- Contributors must learn the small Medley API (documented inline).

## Next Steps
1. Sketch replacements in `core.db` guarded behind a feature flag so tests can assert identical maps.
2. Update `labs.derive.registry` to call the shared helpers, eliminating redundant derivation logic.
3. Document preferred helpers in `docs/kernel_simplification_proposals.md` so future reducers follow the same pattern.
