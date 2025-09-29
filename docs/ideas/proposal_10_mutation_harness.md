# Proposal 10 · Mutation Harness for Effects

## Current state
- `src/kernel/effects.cljc` only emits one effect (`:view/scroll-into-view`). There is no official harness to capture these effects in tests—sanity checks operate on DB snapshots only.

## Reference patterns
- **Replicant mutation log** (`replicant/src/replicant/mutation_log.cljc:1-170`) wraps every DOM mutation method, logging structured entries (`[:append-child el child]`) into an atom. The log is pure EDN and powers deterministic tests.
- **Missionary rendezvous** (`missionary/src/missionary/core.cljc:409-432`) demonstrates capturing effectful flows as data streams. We can expose the harness both as a reducer (collect vector) and as a stream (feed downstream pipelines).

## Proposed harness
```clojure
(ns kernel.effects.sim)

(defn ->simulator [] (atom []))

(defmulti record-effect (fn [_ effect] (:effect effect)))

(defmethod record-effect :view/scroll-into-view [log effect]
  (swap! log conj (select-keys effect [:effect :ids :cause])))

(defn run-with-simulator [db tx {:keys [log] :as opts}]
  (let [log (or log (->simulator))
        {:keys [db effects] :as result}
        (core/apply-tx+effects* db tx opts)]
    (reduce record-effect log effects)
    (assoc result :log @log :log-atom log)))
```

- Extend `record-effect` per effect type (`:view/diff`, `:fs/save`) keeping handler logic in one namespace.
- Provide integration with the transaction pipeline (Proposal 7) so the harness can be plugged in as a stage.

## Test strategy
- Sanity checks assert on `:log` instead of string matching: e.g., `[{:effect :view/scroll-into-view :ids ["node-42"]}]`.
- Mutation harness doubles as documentation: dump logs into `./test-results/latest-effects.edn` for manual inspection.

## Trade-offs
- New effect types must define a recorder; enforce this with `(every? #(contains? record-effect %) effect-types)` in CI.
- Harness should not diverge from production adapters. Write contract tests comparing simulator logs with instrumented real adapters (when available).
- Log growth: provide tooling to group consecutive identical effects or truncate logs for large transactions.

## Next steps
1. Create `kernel/effects/sim.cljc` with `->simulator`, `record-effect`, and `run-with-simulator` helpers.
2. Update tests to use the harness and add fixtures covering multiple effect types (scroll, diff, analytics).
3. Document the effect log schema in `docs/effects.md` so clients/LLMs know how to consume it.
