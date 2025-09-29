# Proposal 62 · Core.match Patterns for Position Specs

## Problem
- `pos->index` (`src/kernel/core.cljc:30-47`) handles every position variant with a chain of `cond` clauses and manual destructuring. Reading the function means mentally simulating each branch (`:first`, `[:index i]`, `[:before anchor]`, etc.).
- When we introduce new path semantics (Proposal 36/45), touching this function risks subtle fall-through bugs; there’s no exhaustiveness check.

## Inspiration
- Core.match offers concise pattern dispatch with automatic destructuring (`overtone/src/overtone/examples/monome/satie.clj:2;100-104`). Matching on vectors/keywords reads like a spec and fails fast when no pattern matches.

## Proposed change
1. Depend on `clojure.core.match` (dev-time) and rewrite `pos->index` as a `match` expression so each clause describes the accepted shape.

```clojure
(match pos
  nil (count base)
  :first 0
  :last (count base)
  [:index i] (assert-valid-index i (count base))
  [:before anchor] (idx anchor)
  [:after anchor] (inc (idx anchor))
  :else (throw (ex-info "bad :pos" {:pos pos})))
```

2. Extract shared guards into helpers (`assert-valid-index`, `idx`) so the match clauses stay declarative.
3. Use `:else` to produce consistent error data—core.match ensures we don’t forget a branch when we extend the `:pos` schema.

## Expected benefits
- Easier to read/maintain: the shape of each accepted position is explicit and mirrored in the schema, lowering cognitive load for agent contributors.
- Exhaustiveness: adding a new pattern fails fast unless we handle it in `match`, so tests catch missing branches immediately.
- Works the same in CLJ/CLJS via `macros.core.match`, so we retain cross-platform behaviour while making the spec-like code match the docstring.

## Trade-offs
- Adds a lightweight dependency; acceptable because performance isn’t critical and core.match runs at compile time.
- Developers must ensure CLJS bundle includes the macro namespace, but that’s a one-time require.
