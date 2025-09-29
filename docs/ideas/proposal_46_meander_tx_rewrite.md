# Proposal 46 · Meander Rewrites for Transaction Normalization

## Problem
- Peephole optimizer (`src/kernel/tx/normalize.cljc:22-97`) encodes each rule as explicit loops/filters. Adding a new rewrite requires hand-rolling traversal logic and manually ensuring idempotence.
- The current implementation only supports linear windows; expressing structural rewrites (e.g. collapse `[:insert -> :place -> :update]`) or context-sensitive rules rapidly becomes unwieldy.
- Planner tooling cannot inspect which rule fired or generate variants because the code is not data-driven.

## Inspiration
- **Meander’s rewrite system** compiles declarative pattern/substitution pairs into efficient matchers (`/Users/alien/Projects/inspo-clones/meander/examples/datascript.cljc:80-118`). Patterns such as `(m/rewrite {:selector ?k} …)` show how complex tree reshapes reduce to concise rules while remaining composable.

## Proposed change
1. Introduce `kernel.tx.rewrite` namespace that registers normalization rules as Meander `rewrite` clauses. Each rule states pattern → replacement using pure EDN.
2. Replace manual `cancel-create-then-delete`, `drop-noop-reorder`, `merge-adjacent-patches` loops with equivalent `m/rewrites` compilation. Rules can reference the op vector and context (e.g. next op) declaratively.
3. Export a helper `(normalize ops)` that simply applies the compiled rewrite system until fixed point—Meander supports looping via `m/rewrites` or `strategy` combinators.

```clojure
;; before (src/kernel/tx/normalize.cljc)
(defn merge-adjacent-patches [ops]
  (->> ops
       (partition-by (fn [op] [(:op op) (:id op)]))
       (mapcat ...)
       vec))

;; after (sketch)
(ns kernel.tx.rewrite
  (:require [meander.epsilon :as m]))

(def compile-normalize
  (m/rewriter
    [[:* {:op :create-node :id ?id} {:op :delete :id ?id} & ?tail]
     [:* & ?tail]]
    [[:* {:op :reorder :pos nil} & ?tail]
     [:* & ?tail]]
    [[:* {:op :update-node :id ?id :props ?a}
         {:op :update-node :id ?id :props ?b}
         & ?tail]
     [:* {:op :update-node :id ?id :props (merge ?a ?b)} & ?tail]]))

(defn normalize [ops]
  (loop [xs ops]
    (let [ys (compile-normalize xs)]
      (if (= xs ys) xs (recur ys)))))
```
- More intricate rules (like moving inserts before sibling deletes) stay concise and cover nested contexts.

## Expected benefits
- Dramatically lowers maintenance cost as new rewrites are small, declarative clauses instead of bespoke control flow.
- Enables automatic tracing: the rewrite compiler can annotate which clause fired, feeding Planner diagnostics.
- Facilitates advanced transformations (e.g. canonicalizing planner output) without increasing cyclomatic complexity.

## Trade-offs
- Adds dependency on `meander` for dev/runtime; ensure CLJS compatibility (epsilon runtime already ships as CLJS).
- Rule authoring requires familiarity with Meander patterns—mitigate by providing cookbook examples and unit tests per rule.

## Migration plan
1. Port existing three rules into Meander notation and compare results via property tests.
2. Provide instrumentation mode `(normalize ops {:trace? true})` capturing applied rules.
3. Deprecate manual helpers once parity established; keep `normalize` signature unchanged for callers.
