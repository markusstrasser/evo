# Proposal 55 · Tree-Sitter Style Incremental Delta Windows

## Problem
- `apply-tx+effects*` re-runs `registry/run` on the whole tree after each op (`src/kernel/core.cljc:353-379`). Even with peephole normalization this recomputes derived data globally.
- `kernel.tree-index/build` and the lens helpers assume full re-derivation; we have no notion of local “dirty windows” to focus downstream passes or effect detection.

## Inspiration
- Tree-Sitter maintains persistent syntax trees and updates them with structural edits via `ts_tree_edit`, returning changed ranges that down-stream tools can consume (`tree-sitter/lib/src/tree.c:55-95`, `tree-sitter/lib/include/tree_sitter/api.h:282-446`). It also exposes `ts_tree_get_changed_ranges` to cheaply compute the minimal set of byte spans affected by an edit.

## Proposed change
1. Represent each kernel transaction as a sequence of `{:edit [start end new-span]}` records while applying primitives.
2. Mirror Tree-Sitter’s strategy: after each op, feed the edit into a `:delta/index` pass that computes affected node intervals and stores them under `:derived :dirty-ranges`.
3. Downstream passes (`registry/run`, invariants, effects) consult `:dirty-ranges` to limit their work to touched subtrees instead of rewalking the entire tree.
4. Expose `kernel.delta/get-changed-ranges` so adapters/effects can scope expensive diffs.

```clojure
;; sketch
(defn apply-op-with-delta [db op]
  (let [{:keys [db' edit]} (apply-primitive db op)
        ranges (dirty/update-ranges (:derived db) edit)]
    {:db db'
     :delta {:op op :edit edit :ranges ranges}}))

(defn update-ranges [prev-derived edit]
  ;; inspired by ts_tree_edit + ts_tree_get_changed_ranges
  (dirty-tree/update (:tree-index prev-derived) edit))
```

## Expected benefits
- Hot path cost is proportional to the size of modified subtrees, matching Tree-Sitter’s O(log n) updates instead of O(n) re-derivation.
- Effects (scroll, view diffs) can reason about local ranges, enabling features like selective repainting or granular undo stacks.
- Aligns with future text/rope integrations—changed ranges are a lingua franca for document-aware planners.

## Trade-offs
- Requires keeping lightweight span metadata alongside derived data; introduce small structs for byte/line offsets.
- Need to ensure edits are normalized (e.g., move vs create+delete) so range math stays correct; reuse normalize rules to guarantee canonical edits before delta computation.
- More bookkeeping per primitive; mitigate with profiling and caching.
