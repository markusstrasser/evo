# Proposal 43 · Missionary-Driven Simulator Harness (Electric & Missionary)

## Motivation
Our mutation harness records effect keywords but cannot model asynchronous adapters or reason about scheduling. As we move toward a Missionary-powered runtime (Proposal 5), we need deterministic simulation to test effect pipelines, timeouts, and cancellation.

## Inspiration
- **Missionary macros `sp`/`?`/`!`** (`/Users/alien/Projects/inspo-clones/missionary/src/missionary/core.cljc:70-200`) support coroutine-style programming with explicit cancellation. Combine them with `timeout`, `race`, and `compel` to model deadlines and cooperative cancellation exactly like runtime fibers do.
- **Missionary mailbox/rendezvous primitives** (`missionary/src/missionary/core.cljc:330-413`, `missionary/java/missionary/impl/*.java`) give deterministic transports that we can reuse for event feeds inside the simulator, mirroring runtime behaviour.
- **Electric `missionary_util.cljc`** (`/Users/alien/Projects/inspo-clones/electric/src/hyperfiddle/electric/impl/missionary_util.cljc`) demonstrates wrapping flows with instrumentation tokens and throttled schedulers; it even exposes `wrap-task*` to assert protocol invariants, which we can adapt for effect harness logging.

## Proposal
1. Implement `kernel.simulator.missionary` providing `with-scheduler` (builds a Missionary executor), `run-effects`, and `assert-sequence` helpers built on tasks. Allow injecting clock control so tests can fast-forward simulated time.
2. Model effect handlers as tasks returning completion signals; sequential or parallel execution becomes composition of `m/join`/`m/amb`. Provide shims for Ring-style async handlers by wrapping them in `missionary.core/attempt`.
3. Integrate the storyboard bus (Proposal 41) so the simulator can subscribe to stage events while effects run, and expose helpers like `(expect-stage-order ...)` that consume from the bus using `m/observe`.
4. Offer DSL macros to declare scenarios: `(scenario [db tx] (expect [:derive :effects]) ...)` that run in Missionary, assert event order, and check for cancellations/timeouts using `m/timeout`/`m/race`.
5. Update `docs/ideas/proposal_10` and sanity checks to use the new harness, ensuring parity with existing behaviour.

## Example
```clojure
(with-scheduler
  (run-effects adapter effects
    (-> scenario
        (expect [:stage/derive :stage/effects])
        (timeout 50))))
```

## Advantages
- **Determinism**: controlling the scheduler eliminates flakiness in async tests.
- **Realism**: simulate cancellation, timeouts, and concurrency exactly as runtime will experience them.
- **Unified tooling**: same harness powers CI tests, REPL inspection, and docs.

## Risks
- **Complexity**: Missionary concepts (fibers, cancellation) add learning overhead; mitigate with high-level wrappers and documentation.
- **Interop**: ensure harness works on both JVM and CLJS (Missionary supports both).
- **Test migration**: rewriting existing tests costs time; prioritise high-value scenarios first.

## Migration Steps
1. Prototype harness on a subset of effects (e.g., view diffs).
2. Compare results against legacy mutation log to confirm fidelity.
3. Gradually port sanity checks and document patterns for new tests.
4. Expose CLI entry to run simulator scenarios for fast feedback.
