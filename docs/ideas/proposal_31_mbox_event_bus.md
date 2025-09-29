# Proposal 31 · Pure Event Bus Primitives (Missionary `dfv/mbx/rdv`)

## Missionary pattern
- `missionary/src/missionary/core.cljc:320-430` introduces functional primitives: `dfv` (single-assignment dataflow var), `mbx` (mailbox/actor), and `rdv` (synchronous rendezvous). Each is a function whose arities implement the protocol (`post`/`fetch`, `give`/`take`) and the docs ship runnable examples that demonstrate deterministic ordering.
- Under the hood (`missionary/java/missionary/impl/Mailbox.java` and `Rendezvous.java`) the primitives keep all state inside persistent vectors/sets. Cancellation is explicit: aborting a pending fetch invokes the failure continuation with `missionary.Cancelled`. That gives us a reference for how to surface backpressure and cleanup in Evolver without bespoke atoms.
- These helpers give a high-level API over small state machines without exposing implementation details, matching Evolver’s emphasis on pure data structures.

## Pain in Evolver
- Kernel tooling (trace watchers, mutation harness, planned transaction middleware) currently accumulates side data in ad hoc atoms or vectors (see `src/kernel/core.cljc:532-569` and `kernel.sanity_checks.cljc:32-120`). There is no unified way to tap into transaction streams or share derived data between stages without mutating shared state.

## Stealable idea
Adopt Missionary’s bus primitives to build a pure event transport inside the kernel:

```clojure
(defn event-stream []
  (let [queue (atom clojure.lang.PersistentQueue/EMPTY)]
    {:emit (fn [x] (swap! queue conj x))
     :poll (fn [] (when-let [q (peek @queue)]
                   (swap! queue pop)
                   q))}))

;; inspired by Missionary's `mbx` and `rdv` arity split
(defn ->mailbox []
  (let [mb (event-stream)]
    (reify KernelMailbox
      (post [_ msg] ((:emit mb) msg))
      (fetch [_] ((:poll mb)))))
```

By standardising the interface (`post`/`fetch`, `assign`/`deref`), we can replace scattered atoms with composable transports.

## Benefits
- *Deterministic instrumentation*: collectors (sanity checks, mutation harness) consume events via `fetch`, mirroring Missionary’s `mbx` example actor.
- *Pluggable backends*: we can swap the backing implementation (vector, queue, windowed log) without touching callers, as long as they speak the bus protocol.
- *Easier agent automation*: LLM or UI agents read from the bus to follow transaction stories instead of scraping trace maps.

## Trade-offs
- Requires deciding on blocking semantics. Missionary tasks naturally block via `?`; our synchronous variant must define whether `fetch` returns nil or throws when empty.
- Additional layer of abstraction; we must ensure it doesn’t overcomplicate simple scripts. Provide small wrappers (e.g., `with-mailbox`) to keep ergonomics high.
- Maintaining order guarantees (e.g., fairness) is on us; Missionary handles it via the underlying Java classes.

## Next steps
1. Define `kernel.bus` namespace with mailbox/rendezvous/dataflow helpers inspired by Missionary but tuned for synchronous, pure data use.
2. Refactor trace accumulation in `apply-tx+effects*` and pattern watchers to emit into the bus instead of conj’ing onto vectors.
3. Update tooling (sanity checks, mutation harness) to consume from the bus and assert on event sequences.
