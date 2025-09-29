# Proposal 22 · Effects as Endofunctors (Missionary × Ring Async)

**Current touchpoints**
- `src/kernel/effects.cljc` – effect detection only tags operations.
- `src/kernel/core.cljc:632-633` – simply accumulates effect maps.

**Finding**
Missionary tasks form a monad; Ring’s async handlers support dual arities (`handler req` or `handler req respond raise`). We can see each effect as an endofunctor on the adapter category, mapping adapters to adapters while emitting side effects within the same context.

**Proposal**
Define an `Effect` protocol with synchronous and asynchronous arities and use Missionary to compose them.

```clojure
(defprotocol Effect
  (fmap [effect adapter]
    [effect adapter respond raise]))

(defrecord Diff [delta]
  Effect
  (fmap [_ adapter]
    (runtime/run-task adapter
      (missionary/sp (adapter/apply-diff! adapter delta)))))
```

Composition obeys functor laws; identity effect is `identity` functor. This gives a categorical lens for effect pipelines in complex clients (e.g., VR HUDs, Figma live previews, game editors with asynchronous asset loading).

**Expected benefits**
- Unified abstraction for sync/async effects.
- Formal reasoning about effect composition (commutativity, cancellation).

**Implementation notes**
1. Implement `Effect` for each effect type with dual arities matching Ring’s async pattern.
2. Provide helpers (`compose-effects`) that guarantee functor laws, verified via mutation-log simulator tests.
3. Document patterns for adapter implementers (e.g., Figma plugin vs. CLI) to hook into the runtime.

**Trade-offs**
- Additional protocol complexity; requires adapter authors to understand Missionary’s task semantics.
- Async composition introduces scheduling considerations; must ensure deterministic testing via Proposal 10’s simulator.
