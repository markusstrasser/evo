# Proposal 79 · Expresso-Style Rule Declarations for Invariants

## Problem
Invariant checks are hand-written loops sprinkled with `when`/`throw`. They are hard to read and harder to extend when we add new invariants.

## Inspiration
Expresso’s `rule` macro rewrites algebraic expressions using declarative patterns and optional guards, powered by `core.logic`.cite/Users/alien/Projects/inspo-clones/expresso/src/main/clojure/numeric/expresso/rules.clj:1-120

## Proposed Change
1. Introduce `(kernel.invariants/defrule name pattern ==> body :if guard)` to declare structural invariants.
2. Compile rules into logic goals that search for counterexamples; emit rich error objects when a rule fails.

### Before
```clojure
(doseq [[parent children] (:children-by-parent-id db)]
  (when (not= (count children) (count (distinct children)))
    (throw (ex-info "duplicate child" {:parent parent}))))
```

### After
```clojure
(inv/defrule unique-children
  [:children-by-parent-id ?parent ?children] ==> :pass
  :if (= (count ?children) (count (distinct ?children))))
```
Violations return `{::rule ::unique-children ::parent parent ::children children}` for instrumentation.

## Expected Benefits
- Declarative invariant catalogue that doubles as documentation.
- Guards allow us to share patterns but switch on context (e.g. skip root nodes).
- Logic engine gives us the failing bindings automatically.

## Trade-offs
- Pulls in `core.logic` costs unless we limit ourselves to first-order checks.
- Debugging macro expansions requires extra tooling (macroexpand pretty printer).

## Roll-out Steps
1. Implement `defrule` macro producing a function `(check db) -> violations`.
2. Port existing duplicate-child and cycle checks to rules; compare runtime.
3. Add REPL helper `(inv/explain ::unique-children db)` that prints bindings.
