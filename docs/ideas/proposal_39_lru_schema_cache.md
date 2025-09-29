# Proposal 39 · LRU Schema Cache & Pull Helpers (Datascript)

## Motivation
Malli schema compilation and reference traversal happen repeatedly in hot paths: every op validation, planner speculative check, and tooling query rebuilds the same structures. This wastes CPU and allocates incessantly.

## Reference Implementation
- **Datascript** caches parsed pull attrs using a bespoke LRU (`/Users/alien/Projects/inspo-clones/datascript/src/datascript/lru.cljc`) coupled with the pull executor (`pull_api.cljc`). The result: rarely-parsed patterns and fast, incremental pulls.

## Proposed Solution
1. Introduce `kernel.cache.lru` – either vendoring Datascript’s tiny implementation (respecting EPL) or delegating to a small dependency – tuned for ~128 entries with O(log n) eviction.
2. Wrap Malli schema compilation in `schema-cache/cached-op-schema` keyed by op keyword + version. Invalidate the cache whenever `register-op!` mutates the registry.
3. Build a “mini pull API” for kernel refs, compiling declarative patterns once and caching the frame in the same LRU.
4. Surface cache metrics (hits, misses, evictions) via `kernel.introspect` so operators can size appropriately.

## Scenario
```clojure
(let [schema (schema.cache/cached-op-schema (:op tx))]
  (m/validate schema tx)) ; reuses compiled Malli schema
```

## Benefits
- **Latency reduction**: op validation becomes a cache lookup + `m/validate`. Particularly helpful when LLM planners simulate hundreds of operations.
- **Consistency**: coupling pull helpers with caching ensures that graph tooling benefits from the same memoisation.
- **Observability**: metrics highlight mis-sized caches or unexpected churn.

## Risks & Downside
- **Stale entries**: forgetting to invalidate after schema updates could serve outdated definitions. Mitigate with `register-op!` instrumentation and tests.
- **Memory usage**: caches hold onto compiled schemas; expose configuration and permit manual `reset!`.
- **Complexity**: adds mutable state; keep API narrow and document lifecycle.

## Deployment Plan
1. Vendor LRU implementation with attribution.
2. Wrap Malli compilation points (`validate-op!`, `op-definition-for`) with cache lookups.
3. Add integration tests verifying cache invalidation on registry mutations.
4. Extend docs to describe configuration knobs (`EVO_SCHEMA_CACHE_SIZE`, etc.).
