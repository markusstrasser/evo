# Proposal 44 · REPL Path Lenses & Navigation Helpers

## Problem
Raw derived maps expose path vectors (`[:root :page-1 :card-5]`), but developers must mentally infer relationships. Reading specs or invariants requires manual index arithmetic. We need tiny, pure helpers to make state introspection ergonomic without introducing heavy abstractions.

## Before
```clojure
(let [path (get-in db [:derived :path-of "node-9"])]
  (println path)
  ;; deduce siblings & order by hand
  )
```
- Sibling/ancestor traversal is manual.
- Comparing two paths requires writing your own comparator.
- No API for expressing “move to next sibling”, “insert before”, etc.

## After (Lens helpers)
```clojure
(require '[kernel.path.lens :as lens])

(let [info (lens/explain db "node-9")]
  (select-keys info [:ancestors :index :siblings]))
```
`lens/explain` returns a map like:
```clojure
{:path [:root :page-1 :node-9]
 :ancestors [[:root] [:root :page-1]]
 :siblings ["node-8" "node-9" "node-10"]
 :next "node-10"
 :previous "node-8"
 :transform {:insert-after (fn [anchor] ...)
             :wrap (fn [] ...)}}
```
- Helpers compute derived facts from existing data, pure and testable.
- Planning DSLs and REPL explorations reuse same functions.

## Library vs DIY
- **Slate-style path algebra** already lives in Proposal 36; this proposal focuses on REPL wrappers.
- No external dependency needed. However, we could optionally depend on `clojure.zip` for navigation if we adopt zippers (already a core library).
- If we want rich string rendering, we might use `fipp` or `puget` (existing deps). Not required.

## Usage Patterns
- `lens/of derived id` returns the path vector.
- `lens/ancestors derived id` -> list of ancestor paths.
- `lens/compare a b` -> -1/0/1, like Slate’s `Path.compare`.
- `lens/next derived id` -> sibling to the right (nil if last).
- `lens/transform path {:op :after :anchor some-id}` -> new path vector representing insert location.

## Benefits
- **Readability**: specs/tests can assert using `lens/compare` instead of raw indices: `(is (neg? (lens/compare id-a id-b)))`.
- **Zero new concepts**: just pure functions returning maps/vectors, no macros or protocols.
- **LLM ergonomics**: when describing edits, agents can reference `lens/next`/`lens/previous` to align with human reasoning.

## Risks
- Overlaps with core path algebra (Proposal 36). Keep lens functions shallow wrappers around core path operations to avoid divergence.
- Derived data must be current; if consumers pass stale DBs results are wrong. Document expectation.

## Implementation Steps
1. Implement `lens/of`, `lens/ancestors`, `lens/compare`, `lens/next`, `lens/previous` using derived maps.
2. Add tests comparing lens results against manual calculations from fixtures.
3. Document sample REPL session and add a script (`scripts/show-path.clj`) for quick inspection.
4. Cross-link from invariants and derivation docs to encourage reuse.
