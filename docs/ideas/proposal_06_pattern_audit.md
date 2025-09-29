# Proposal 6 · Transaction Pattern Watchers (Metamorphic)

## Observed gap
- `src/kernel/core.cljc:530-548` records a linear `:trace` when `:trace?` is true, but consumers must sift through raw ops. There is no higher-level narrative (“insert→reorder→delete”) for sanity checks or UI tooling.

## Reference patterns
- **re-frame-10x Metamorphic** (`re-frame-10x/src/day8/re_frame_10x/tools/metamorphic.cljc:1-180`) defines predicate helpers such as `fsm-trigger?`, `run-queue?`, and `event-run?`, then composes them with a pattern DSL to detect epochs. The runtime keeps history, optional steps, and summarizers to emit human-readable matches.
- **Missionary flows** (`missionary/src/missionary/core.cljc:382-458`) demonstrate building streaming detectors on top of rendezvous queues—useful for watching Evolver traces as data streams.

## Proposed watcher DSL
```clojure
(defpattern insert-then-reorder
  (begin :insert #(= :insert (:op %)))
  (followed-by :reorder #(= :reorder (:op %))))

(defn run-patterns [trace]
  (metamorphic/run
    {:history []}
    [insert-then-reorder
     multi-block-merge
     orphan-detach])
  ;; => [{:pattern :insert-then-reorder
  ;;      :span {:op-indices [0 1]}
  ;;      :summary "Node inserted then reordered within parent"}]
)
```

Implementation mirrors `day8.re-frame-10x.tools.metamorphic`: pattern definitions produce state machines that consume a transaction trace (vector of `{ :op ... :db ... }`). Each matcher decides whether to advance, accept, or fail fast.

## Integration points
- Patterns run inside `apply-tx+effects*` when `:pattern-watchers?` is true. The stage pipeline (Proposal 7) invokes `pattern/runtime` after each op, keeping memory bounded by truncating history.
- Sanity checks (`src/kernel/sanity_checks.cljc`) can assert that canonical workflows (e.g., bulk import, node merge) emit expected patterns, providing metamorphic tests similar to re-frame-10x.
- Agents/UI: expose `kernel.introspect/list-patterns` returning metadata (name, sample transcript, docs) so LLMs can ask “did we hit the multi-block merge pattern?”

## Trade-offs & guardrails
- **Complexity**: a flexible DSL can become unreadable. Provide helpers `defpattern`, `begin`, `follow`, `optional`, mirroring re-frame-10x’s macros so definitions stay declarative.
- **Ambiguity**: overlapping patterns might match the same trace. Offer priority ordering and allow patterns to declare `:exclusive? true` to claim ownership of a span.
- **Performance**: tracing every transaction could allocate; keep pattern state small (maps of ints/preds) and allow opt-out in production.

## Next steps
1. Extract `metamorphic` runtime sketch: history vector, pointer, and pattern state machine functions.
2. Port an initial catalog: `insert→reorder`, `create→place`, “delete orphan”, “fix reference”. Source predicates from re-frame-10x (e.g., `fsm-trigger?`) but adapted to Evolver ops.
3. Add regression tests that feed known traces (from `test/`) and assert expected pattern summaries and failure diagnostics.
