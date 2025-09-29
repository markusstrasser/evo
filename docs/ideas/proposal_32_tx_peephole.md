# Proposal 32 · Transaction Peephole Optimizer

## Problem
Transactions today are raw vectors of op maps. We execute them as-is, so redundant sequences (create+delete, double moves, update same field twice) consume CPU and clutter traces. We rely on runtime invariants to catch nonsense, which is late and noisy.

## Desired End State
Introduce a declarative rewrite layer that simplifies op sequences before execution. We want rules that say "if you see X followed by Y, replace with Z" in a composable, testable fashion.

## Before
```clojure
[{:op :create-node :id "temp" :parent-id "root"}
 {:op :delete-node :id "temp"}
 {:op :place :id "card-1" :parent-id "root" :pos [:after "card-1"]}] ; no-op move
```
- Kernel walks the sequence and executes each op.
- We pay for a create+delete even though result is a no-op.
- Place operation moves node to same slot, cluttering traces.

## After (Peephole pass)
```clojure
;; simplified sequence
[] ; both ops removed
```
Rules: (a) `create` followed by `delete` cancels. (b) `place` to same parent/index becomes no-op.

## Implementation Sketch
```clojure
(defprotocol Rewrite
  (rewrite [expr ctx]))

(defrecord Seq [ops]
  Rewrite
  (rewrite [this ctx]
    (->> ops
         (map #(rewrite % ctx))
         (apply rules/collapse)
         (filter (complement noop?))
         (Seq.)))
```
- Compile vector into simple AST nodes (`Seq`, `Create`, `Delete`, `Move`, `Update`).
- Use rewrite rules inspired by Electric’s `Expr/peephole` to simplify.
- Emit cleaned vector for execution.

## Rule Definition
We can encode rewrites declaratively as data:
```clojure
(def rules
  [{:match [:create {:id ?id}] [:delete {:id ?id}]
    :replace []}
   {:match [:place {:id ?id :parent-id ?p :pos ?slot}]
    :when (fn [ctx] (= [ ?p ?slot ] (current-location ctx ?id)))
    :replace []}])
```
`rules/collapse` walks the ops, finds matches, and replaces them. Two options:
- **Custom matcher**: write our own pattern matcher (small, gexpr-like).
- **Use existing library**: `clj-rewrite` or `meander`. Meander is powerful but heavy; for small patterns, a custom DSL is straightforward.

### Depend on a library?
- **Meander** gives rich matching, but adds 2MB jar and macro learning curve.
- **Instaparse** is overkill.
- **Recommendation**: start with custom pattern-match specialised to ops (maps). 50-100 LOC with destructuring and guard functions. If rules grow complex, revisit meander.

## Benefits
- Keeps kernel execution simple (dumb interpreter) while enabling smarter planning.
- Offloads cleanup from runtime invariants; we catch odd sequences before they hit core.
- Reusable by agents: they can call `(tx/normalize ops)` to tidy sequences before sending.

## Risks
- Need thorough tests to ensure rewrites are semantics-preserving. Use property-based tests pairing original vs simplified sequences on fixtures.
- Performance overhead: compilation + rewrite adds CPU; mitigate by caching AST and short-circuiting when no rules apply.

## Migration Plan
1. Build rewrite engine in `kernel.tx.normalize` with AST + rules.
2. Implement minimal rule set: create+delete cancel, redundant place eliminations, merging adjacent updates.
3. Hook into `apply-tx+effects*` (optional flag) and run in shadow mode logging diff between raw and simplified sequences.
4. Once confident, turn on by default and extend rule deck as new redundancies surface.
