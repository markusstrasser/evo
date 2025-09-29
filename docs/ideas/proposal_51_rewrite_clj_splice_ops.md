# Proposal 51 · Rewrite-clj Splicing Primitives for Tree Surgery

## Problem
- Tree primitives (`place*`, `prune*` in `src/kernel/core.cljc:101-170`) manually juggle vectors and recursion. Moving a node requires hand-splicing child vectors, duplicating logic for different cases (detach, move, reorder).
- The code mixes structural invariants with indexing math, making it easy to introduce off-by-one bugs when adding new operations (e.g., splitting nodes, partial pruning).
- We lack reusable utilities for "splice this node into parent" or "lift children into grandparent".

## Inspiration
- **rewrite-clj’s zipper splicing helpers** (`/Users/alien/Projects/inspo-clones/rewrite-clj/src/rewrite_clj/zip/edit.clj:33-43`) provide pure functions to lift a node’s children into its parent while preserving order. paredit ops (`paredit.cljc:653-704`) show how to compose splice/remove behaviours declaratively.

## Proposed change
1. Introduce `kernel.splice` namespace that implements foundational splicing operations on our canonical DB shape. Model functions after rewrite-clj’s `splice`, `splice-killing-backward`, etc., but operating on `:child-ids/by-parent` and `:nodes`.
2. Refactor `place*` and `prune*` to delegate to these splicing utilities, separating invariant checks from structural edits.
3. Provide additional helpers (e.g., `raise-children`, `replace-subtree`) so planners can compose complex refactors without reimplementing vector surgery.

```clojure
;; before (place*)
(let [child-ids (lens/children-of db1 parent-id)
      base (vec (remove #{id} child-ids))
      i (pos->index db1 parent-id id pos)
      v (vec (concat (subvec base 0 i) [id] (subvec base i)))]
  (assoc-in db1 [:child-ids/by-parent parent-id] v))

;; after (sketch)
(ns kernel.splice)

(defn insert-at [db parent index node-id]
  (update-in db [:child-ids/by-parent parent]
             rewrite-child-vector parent index node-id))

(defn detach [db node-id]
  (let [parent (lens/parent-of db node-id)]
    (update-in db [:child-ids/by-parent parent]
               remove-node node-id)))

(defn place [db {:keys [id parent-id pos]}]
  (-> db
      (detach id)
      (insert-at parent-id (pos->index db parent-id id pos) id)))
```

## Expected benefits
- Centralizes tree surgery logic, shrinking core primitives and making them easier to audit.
- Unlocks richer operations (e.g., splice a node’s children into its parent) without duplicating code; planners gain reusable building blocks similar to rewrite-clj’s paredit actions.
- Facilitates law testing: we can property-test `splice` independently to ensure it preserves invariants (no duplicate children, no orphans).

## Trade-offs
- Requires careful translation of rewrite-clj semantics to our adjacency maps; ensure helpers handle roots/multi-roots correctly.
- Adds an extra abstraction layer—developers must learn `kernel.splice` API, but the trade-off is smaller primitive bodies.

## Roll-out steps
1. Implement minimal helpers (`detach`, `insert-at`, `raise-children`) with exhaustive tests covering multi-root and cycle scenarios.
2. Refactor `place*`/`prune*` to rely on helpers, keeping existing tests green.
3. Document splicing cookbook so planners understand how to compose new behaviours safely.
