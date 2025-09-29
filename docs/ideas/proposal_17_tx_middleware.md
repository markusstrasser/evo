# Proposal 17 · Transaction Middleware Inspired by Ring’s Cookie/Session Layers

**Current touchpoints**
- `src/kernel/core.cljc:550-620` – `default-pipeline`/`run-pipeline` stream per-op stages, but cross-cutting concerns still require inlining logic around `apply-tx+effects*`.
- Adapters today bolt on metrics/audit code ad hoc in their own entry points.

**Pain**
Even with the declarative stage pipeline, there is no first-class way to interpose request/response transformers that act once per transaction. Metrics, audit logs, undo journaling, or dry-run toggles must wrap `apply-tx+effects*` manually, leading to copy/paste and inconsistent behaviour across adapters.

**Inspiration**
- Ring middleware chain (`/Users/alien/Projects/inspo-clones/ring/ring-core/src/ring/middleware/*.clj`) separates request enrichment and response finalisation via composable wrappers. Each `wrap-*` follows the same template: `request->handler->response`, optionally normalizing state both before and after (see `wrap-session` at `ring/middleware/session.clj:78-150` and `wrap-cookies` at `cookies.clj:160-230`).
- Ring also exposes helper functions (e.g., `session-request`, `session-response`) so middleware logic is testable outside the wrapper—mirroring how we can split kernel middleware into pure helpers.
- Missionary’s `m/eduction` pipes (`/Users/alien/Projects/inspo-clones/missionary/src/missionary/core.cljc:560-610`) layer observers around reducers, giving a functional analogue for wrapping the transaction runner.

**Proposed change**
Introduce `kernel.tx.middleware`, a small toolkit of `wrap-*` helpers that decorate the pipeline runner.

```clojure
(ns kernel.tx.middleware)

(defn wrap-derive-cache [handler]
  (fn [ctx opts]
    (let [ctx' (assoc ctx :derive (get opts :derive kernel.core/*derive-pass*))]
      (handler ctx' opts))))

(def tx-handler
  (-> kernel.core/execute-tx-context
      wrap-derive-cache
      wrap-metrics
      wrap-effects))

(tx-handler {:db db :tx tx} {:trace? true})
```

`kernel.core/apply-tx+effects*` becomes a thin shim: build initial context, hand it to the composed middleware stack, then convert the final context to the public result map.

**Expected benefits**
- Adapters gain a stable hook surface: they can insert custom `wrap-*` fns without editing `kernel.core`.
- Cross-cutting policies (dry-run, undo journaling, audit logging) live in one place and can be reused across CLJ/CLJS.
- Tests can swap in a minimal middleware stack (e.g., skip effects) for deterministic runs.

**Implementation notes**
1. Extract the existing `apply-tx+effects*` context lifecycle into `execute-tx-context` returning `{::db-after .. ::effects .. ::trace ..}`.
2. Provide Ring-style helpers (`wrap-metrics`, `wrap-trace-limit`, `wrap-effects`) that operate on `[ctx opts]` signatures.
3. Document the default stack (`wrap-validate`, `wrap-run-pipeline`, `wrap-build-response`) and expose a helper to recompose it, similar to `ring.middleware.stack`.

**Trade-offs**
- Another layer of indirection; we need tracing tools to visualise active middleware (e.g., `(tx.middleware/describe-stack)`).
- Option ordering matters. Supply presets for common adapters (CLI vs. long-running server) and assert stack shape in sanity checks.
