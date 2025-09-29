# Proposal 21 · Transactions as Free Monoid (Datascript)

**Current touchpoints**
- `src/kernel/core.cljc:602-633` – imperative loop threads DB and effects manually.
- `src/kernel/sanity_checks.cljc` – transactions treated as opaque vectors.

**Finding**
Datascript’s `transact-report`/`transact-add` (`datascript/db.cljc:1594-1607`) fold primitive datoms into a report. This demonstrates the free monoid structure over operations.

**Proposal**
Wrap kernel primitives in a `TxOp` record with `apply`, `derive`, and `delta` functions; define `compose` and `fold` to sequence them algebraically.

```clojure
(defrecord TxOp [apply derive delta])

(def identity-op (->TxOp identity identity (constantly nil)))

(defn compose [a b]
  (->TxOp (comp (:apply b) (:apply a))
          (comp (:derive b) (:derive a))
          (merge-deltas (:delta a) (:delta b))))

(defn fold-tx [ops]
  (reduce compose identity-op ops))
```

**Expected benefits**
- Explicit algebra enables simplifications (e.g., cancelling inverse moves) before hitting the DB.
- Clear hook to add metrics or tracing by decorating `TxOp` composition.

**Implementation notes**
1. Ensure each primitive returns a `TxOp` (ties into incremental derive Proposal 2).
2. Property-test monoid laws (identity, associativity) using generated op sequences.
3. Allow planners to reduce words before execution for domain-specific clients (Figma manipulating node graphs, game editors adjusting scenes).

**Trade-offs**
- Additional abstraction layer may feel heavy for simple ops; need solid tooling (pretty-printers) to inspect composed ops.
- Requires careful management of `delta` merging semantics to avoid subtle bugs in composite operations.
