# Proposal 61 · Specter-Powered Invariant Playbook

## Problem
- `kernel.invariants/check-invariants` (see `src/kernel/invariants.cljc:17-99`) is a forest of nested `doseq` loops and manual `assert`s. Each new check reruns `contains?` and `get` plumbing, and failure reporting is scattered across `str` concatenations.
- The development agent has to skim 80+ lines to know which invariants exist; making a one-line tweak risks missing symmetric loops.

## Inspiration
- Specter’s `defnav`/`select` macros let us express targeted traversals declaratively while reusing navigation helpers (`specter/src/clj/com/rpl/specter/macros.clj:1-40`). We can compose navigators (e.g., “all children IDs”) and run `select`/`transform` to gather violations in a few lines.

## Proposed change
1. Define Specter navigators for key kernel structures (e.g., `[:child-ids/by-parent ALL]`, `:derived :index-of`), maybe reusing Proposal 45’s nav registry.
2. Replace the imperative `doseq` checks with a `let` binding that uses `select` to collect violation maps:

```clojure
(def children-nav (path :child-ids/by-parent MAP-VALS ALL))

(defn invariant-report [db]
  (concat
    (for [root (select [:roots ALL] db)
          :when (not (contains? (:nodes db) root))]
      {:rule :root-missing :id root})
    (for [[parent child] (select [:child-ids/by-parent MAP-ENTRIES ALL] db)
          :when (not= parent (get-in db [:derived :parent-id-of child]))]
      {:rule :parent-mismatch :parent parent :child child})
    ...))
```

3. Expose `invariant-report` (vector of maps) and make `check-invariants` simply throw when the report is non-empty. Agents can now inspect violations without diving into asserts.

## Expected benefits
- Dramatically shorter, more declarative invariant definitions; adding/removing checks involves a single `for` clause instead of coordinating multiple `doseq`s.
- Easier REPL usage: `(select ...)` returns the exact offending IDs, so the agent can debug without rerunning the whole pipeline.
- Aligns with earlier Specter-based lens work, so navigators stay reusable across derivation, debugging, and invariants.

## Trade-offs
- Introduces Specter as a runtime dependency in the invariants namespace; acceptable because the checks already run under `:assert?` (non-hot path).
- Developers must learn Specter’s syntax, but the payoff is high: invariants literally describe the shapes they inspect, lowering cognitive load for future maintainers.
