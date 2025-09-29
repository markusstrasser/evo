# Proposal 30 · Coroutine Derivation Builders (Missionary `sp/ap`)

## Missionary pattern
- `missionary/src/missionary/core.cljc:230-310` defines the `sp`, `ap`, and `cp` macros using `cloroutine.core/cr`, creating lightweight coroutines where `?`, `?>`, and `?<` act like `yield` steps.
- The macros compile to partials over runtime runners (`sp-run`, `ap-run`) so the body remains readable, and control operators (`park`, `fork`, `switch`) get injected through the environment map.

## Pain in Evolver
- Derivation and transaction stages (`src/kernel/core.cljc:160-360` & `524-575`) juggle nested `loop`/`recur` plus manual `try/catch` to short-circuit failures. Instrumentation (traces, sanity checks) is interleaved with business logic.
- Pattern detectors (future Proposal 6) need a way to `yield` insights between passes without building hand-rolled state machines.

## Stealable idea
Adopt Missionary’s coroutine macro pattern to build a declarative `derive*` DSL:

```clojure
(defmacro defstage [name bindings & body]
  `(defn ~name [ctx#]
     ((partial (cr {yield stage-yield}
                  (let ~bindings
                    ~@body))
               run-stage)
      ctx#)))

;; Example usage
(defstage stage-validate [{:keys [op idx]}]
  (stage/yield {:event :validate :idx idx :op op})
  (S/validate-op! op))
```

The macro hides the boilerplate for emitting intermediate events (like `stage/yield`) and lets stages pause/continue cleanly, mirroring how `sp` bodies call `?` to block or `?>` to fork.

## Benefits
- *Readable staged scripts*: each stage body reads like straight-line code; `stage/yield` (inspired by `?`) exposes instrumentation without extra loops.
- *Structured instrumentation hooks*: the coroutine runner can collect yielded values for traces, pattern audits, or debugger UIs.
- *Composable pipelines*: chaining stages becomes a reduce over coroutine functions, similar to how Missionary composes flows.

## Trade-offs
- Requires introducing `cloroutine` (already Missionary’s dependency) or a lightweight alternative to support resumable functions.
- Developers must learn the coroutine semantics (when to use `stage/yield`, how errors propagate) – documentation and examples are essential.
- Need to ensure CLJ/CLJS parity; Missionary handles this via shared macros, so we should follow suit.

## Next steps
1. Create `kernel.stage` namespace with a `defstage` macro modeled after `sp` (`cr` + runner partial) but simplified for synchronous stages.
2. Port key derivation/validation loops into staged coroutines; compare trace output before/after.
3. Provide helpers to collect yielded events into traces or send them to watchers (linking with Proposal 6 pattern audits).
