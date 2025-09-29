# Proposal 80 · HoneySQL-Style Intent Builder DSL

## Problem
Higher-level planners construct transaction vectors manually (`assoc`, `merge`, `conj`). This is verbose and brittle—ordering mistakes quietly change behaviour.

## Inspiration
HoneySQL represents SQL as a data DSL and keeps formatting logic in one place. Clause registries (`register-clause!`) make it easy to add new statement types without touching consumers.cite/Users/alien/Projects/inspo-clones/honeysql/src/honey/sql.cljc:1-120

## Proposed Change
1. Introduce `(kernel.dsl/intent ...)` macros mirroring HoneySQL’s clause system:
   ```clojure
   (intent
     [:op/create {:id :node :type :block}]
     [:op/place {:id :node :parent parent :pos [:after sibling]}])
   ```
2. Provide `register-intent!` so new clauses can add lowering logic (e.g. `:op/merge-props`).
3. Emit canonical op sequences (`vector`) for kernel consumption.

### Before
```clojure
(concat
  [{:op :create-node :id id :type :div}]
  [{:op :place :id id :parent-id parent :pos [:after sibling]}])
```

### After
```clojure
(intent->tx
  (intent
    [:create-node {:id id :type :div}]
    [:place {:id id :parent parent :pos [:after sibling]}]))
```
`intent->tx` expands to the exact op vector; clause handlers decide how to normalise inputs.

## Expected Benefits
- Cleaner planner-callers, lower risk of missing keys or mixing `:parent-id`/`:parent`.
- Clause registry captures metadata (docs, examples) just like HoneySQL’s clause ordering.

## Trade-offs
- Another DSL to learn; errors must be well-formatted to avoid confusion.
- Need to ensure macros stay hygienic when used from CLJS/SCI.

## Roll-out Steps
1. Implement `register-intent!` + built-ins for current primitives.
2. Port mission-critical planners (paste, indent) to the DSL behind a feature flag.
3. Add doc generator to export clause summaries into `docs/` for LLM guidance.
