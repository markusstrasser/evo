# Proposal 89 · Diff-Based Effects with Editscript

- **Date**: 2025-09-29
- **Status**: Draft

## Context & Pain
`labs.effects/detect` currently emits a single hard-coded effect when it sees an `:insert` op.

```clojure
;; src/labs/effects.cljc:3-10
(defn detect [prev next op-index op]
  (cond-> []
    (= (:op op) :insert)
    (conj {:effect :view/scroll-into-view ...})))
```

Adapters must recalculate DOM diffs or tree changes themselves. As derived structures grow, effect detection will either explode in ad hoc conditionals or drift from the actual structural diff.

## Inspiration
Editscript provides an efficient, data-agnostic diff that returns add/delete/replace operations with paths. We can harness it to detect structural deltas between `prev` and `next` databases.

- `/Users/alien/Projects/inspo-clones/editscript/src/editscript/core.cljc` (`diff`, `patch`, lines 1-120)

## Proposal
1. Introduce a kernel-level helper:

```clojure
(defn structural-diff [prev next]
  (editscript.core/diff (:tree/children prev)
                        (:tree/children next)
                        {:algo :quick}))
```

2. Expand `labs.effects/detect` to translate diff ops into normalized view effects:

```clojure
(mapv (fn [[path op payload]]
        (case op
          :+ {:effect :tree/add
              :path path
              :payload payload}
          :- {:effect :tree/remove :path path}
          :r {:effect :tree/replace :path path :payload payload}))
      (editscript.core/get-edits (structural-diff prev next)))
```

3. Emit metadata including `op-index`/`tx-id` so adapters can decide whether to scroll, animate, or batch updates.
4. Keep the existing `:view/scroll-into-view` effect as a post-processor rule applied to `:tree/add` events.

## Expected Benefits
- **Generalises effects**: one pathway covers add/delete/reorder without bespoke watchers.
- **Adapter ergonomics**: frontends receive structured, minimal diffs instead of raw DB snapshots.
- **Testing**: we can snapshot diff outputs directly, avoiding DOM-dependent assertions.

## Trade-offs & Risks
- Editscript has O(n^2) worst-case behavior on large vectors; choose `:quick` algorithm and guard with a size cutoff.
- Diffing on every op may add latency; mitigate with incremental derivation (Proposal 87) or caching previous `:tree/children` slices.

## Next Steps
1. Prototype `structural-diff` on sample tx traces; measure average diff size vs. naive recompute.
2. Add property tests: applying the emitted diff to `prev` should yield `next` (leveraging `editscript.core/patch`).
3. Update adapters/docs to consume new effect payloads.
