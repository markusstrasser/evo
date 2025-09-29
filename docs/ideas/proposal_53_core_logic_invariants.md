# Proposal 53 · Core.logic Constraint Checks for Kernel Invariants

## Problem
- Invariant enforcement uses imperative assertions (`src/kernel/invariants.cljc:20-108`). Each check loops through the DB manually, and adding new rules requires threading `doseq` logic.
- Explanations for failures are ad hoc strings; we cannot query “which nodes violate parent symmetry?” without re-running imperative scans.

## Inspiration
- **core.logic’s constraint store** expresses relations declaratively and reuses a solver to detect contradictions (`/Users/alien/Projects/inspo-clones/core.logic/src/main/clojure/clojure/core/logic.clj:50-120`). Constraints can be composed, and results include the failing variables.

## Proposed change
1. Model invariants as logic relations: e.g., `parent-rel` ensures `(parent-of child parent)` matches adjacency; `acyclic?` becomes failure to satisfy a recursion-free relation.
2. Build a `run*` query that attempts to satisfy all invariants simultaneously, returning offending node bindings when contradictions arise.
3. Replace imperative assertions with a call to the logic query; convert the resulting substitutions into structured findings (reusing `kernel.deck`).

```clojure
;; sketch
(ns kernel.invariants.logic
  (:require [clojure.core.logic :refer [run* fresh == conde]]))

(defn parent-edge [db]
  (run* [q]
    (fresh [parent child]
      (== q {:child child :parent parent})
      (membero [parent child] (edges db))
      (conde
        [(== parent (parent-id-of db child))]
        [(!= parent (parent-id-of db child))]))))

(defn check-invariants [db]
  (let [violations (parent-edge db)]
    (when (seq violations)
      {:rule :parent-symmetry :violations violations}))
```
- Additional relations (duplicate siblings, cycle detection) share the same relational DSL, collapsing repetitive loops.

## Expected benefits
- Declarative invariants are easier to extend; we can compose relations and reuse solver primitives (e.g., `distincto` for uniqueness).
- Logic engine produces precise counterexamples (bindings), improving error reporting without bespoke loop code.
- Opens door to generative testing: run logic queries to synthesize valid DB examples for property tests.

## Trade-offs
- core.logic adds runtime overhead versus simple loops; however invariants already run under `:assert?` flag. Optimize by limiting relation domains (only touched nodes) when incremental derivation is available.
- Developers must be comfortable with miniKanren syntax; provide helper macros/wrappers for common patterns.

## Migration plan
1. Wrap logic-backed invariants behind feature flag and compare results with existing imperative checks in tests.
2. Gradually port individual invariants (start with parent symmetry & duplicate children) while keeping fallback assertions for safety.
3. Document new DSL and provide cheat-sheet mapping imperative patterns to relations for contributors.
