# Proposal 36 · Slate-Style Path Algebra

## Current friction (Evolver)
- Structural ops (`place*`, `reorder`, `move`) hand-roll index math over `:child-ids/by-parent` (`src/kernel/core.cljc:355-382`). Each function repeats “remove, compute index, splice” logic and relies on derived maps for cycle detection.
- `:path-of` in `:derived` is an opaque vector of node IDs; there is no reusable API for walking ancestors, siblings, or computing insertion points. Planners must juggle IDs + derived maps manually.

```clojure
(let [child-ids (vec (child-ids-of* db1 parent-id))
      base (rmv child-ids id)
      i (pos->index db1 parent-id id pos)
      v (vec (concat (subvec base 0 i) [id] (subvec base i)))]
  (assoc-in db1 [:child-ids/by-parent parent-id] v))
```

## Pattern to emulate
- Slate’s `PathInterface` (`/Users/alien/Projects/inspo-clones/slate/packages/slate/src/interfaces/path.ts:1-160`) exposes a tiny algebra over integer-index paths: `ancestors`, `levels`, `next`, `prev`, `transform`, `operationCanTransformPath`. Every mutation operates on the same primitive “path” type, so planners and transforms share helpers.
- `Path.transform` automatically adjusts sibling indices after insert/delete, removing bespoke splice code.

## Proposed shape
Introduce `kernel.path` with a persistent path type built on integer vectors:

```clojure
(ns kernel.path
  (:require [clojure.core :as c]))

(defn from-id [{:keys [derived]} id]
  (get-in derived [:index-path id]))

(defn levels [path]
  (mapv #(subvec path 0 %) (range 1 (inc (count path)))))

(defn transform [path {:keys [op anchor]}]
  (case op
    :insert (cond-> path
              (= (subvec path 0 (count anchor)) anchor)
              (update (dec (count anchor)) #(inc %)))
    :remove (cond-> path
              (= (subvec path 0 (count anchor)) anchor)
              (update (dec (count anchor)) #(dec %)))
    path))
```

Pipeline changes:
1. Extend derivation to compute `:index-path` (`[0 1 2 …]` from root) alongside existing `:path-of`. This collapses several derived maps (e.g. `:index-of`, `:prev-id-of`) into path arithmetic.
2. Rewrite `place*` using the algebra:

```clojure
(defn place* [db {:keys [id parent-id pos]}]
  (let [path (path/from-id db id)
        target (path/resolve parent-id pos db)
        db' (path/remove db path)
        db'' (path/insert db' target id)]
    db''))
```

3. Expose helper fns for planners (`path/ancestors`, `path/compare`, `path/sibling?`) so high-level intents (Logseq, Figma) can manipulate structure without touching raw maps.

## Expected benefits
- Eliminates bespoke splice code across structural ops—`path/remove` and `path/insert` encapsulate it once.
- Provides a common language (integer paths) for planners, audits, and tests, mirroring Slate’s proven abstraction for rich text editors.
- Makes derived maps smaller: many lookups (`:index-of`, `:next-id-of`, `:prev-id-of`) can be derived on the fly from paths, reducing cache churn.

## Implementation notes
1. Add `:index-path` derivation using DFS order (store parent indices while recursing). Cache as vector-of-ints per node.
2. Implement `path/transform` for each primitive op, porting Slate’s logic to adjust paths after insert/remove/move.
3. Provide Malli schema for the path type and update `validate-db!` to ensure every node has an `:index-path` entry during derivation.

## Trade-offs
- Converting existing ops to path algebra requires careful migration; keep old helpers around until end-to-end tests pass.
- Additional derived data (`:index-path`) increases initial derivation cost, though it replaces several other derived maps.
- Integer paths assume siblings stay in vectors; ensure adapters enforce canonical ordering (which is already required by `place*`).
