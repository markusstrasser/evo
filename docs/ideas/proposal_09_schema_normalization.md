# Proposal 9 · Schema-Driven State Normalisation (Malli)

## Current state
- `src/kernel/schemas.cljc:1-120` defines Malli schemas but only uses them for validation (`validate-op!`, `validate-db!`). We accept either strings or keywords for IDs, but downstream code assumes keywords in many places.
- Incoming data from clients (e.g., Slate, Logseq) often includes string IDs, numeric indexes, and sparse child vectors. Today we normalize ad hoc in op handlers.

## Reference patterns
- **Malli transformers** (`malli/src/malli/transform.cljc:1-180`) compose small coercions such as `-string->uuid`, `-number->long`, `-string->keyword`, and `-transform-map-keys`. These transformers can be chained via `mt/transformer` (see `malli/docs/value-transformation.md:10-40`).
- **Datascript** normalizes entities before indexing (`datascript/src/datascript/db.cljc:235-360`) by walking nested maps, coercing refs, and filling defaults. We can mimic its “canonical entity” notion for Evolver DBs.

## Proposed normalizer
```clojure
(def db-normalizer
  (mt/transformer
    {:name :evolver/normalize
     :decoders {'::S/id (comp keyword str)
                '::S/pos normalize-pos
                '::S/node ensure-node}}
    mt/string-transformer
    mt/strip-extra-keys-transformer))

(defn normalize-db [db]
  (m/decode S/db-schema db db-normalizer))

(defn normalize-op [op]
  (m/decode S/op-schema op op-normalizer))
```

- `normalize-pos` coerces `[:index "3"]` → `[:index 3]`, keywordizes anchor IDs, and fills missing vectors.
- `ensure-node` ensures every node has `:props {}` and `:sys {}` maps (similar to Datascript’s entity expansion).

## Integration points
- Run `normalize-db` at API boundaries (`apply-tx+effects*` entry) to canonicalize the DB snapshot before derivations.
- Wrap `->tx` to call `normalize-op` per op so registry handlers see consistent inputs.
- Provide selective normalization toggles (e.g., `:normalize? false`) for hot paths in benchmarks.

## Trade-offs
- **Strictness**: Normalization may reject legacy payloads. Offer a migration helper that reports offending keys (via `me/humanize`).
- **Cost**: Full decode on each op could be expensive. Cache derived transformers and leverage Malli’s fast path by pre-compiling the schema.
- **Interop**: Downstream agents might rely on lax forms. Document the canonical form (IDs as keywords, child vectors always vectors) and provide conversion utilities for them.

## Next steps
1. Extend `S/registry` with Malli symbol aliases (e.g., `::S/id`, `::S/node`) to reference inside the transformer config.
2. Implement `normalize-db` and `normalize-op` wrappers plus unit tests covering string IDs, missing child vectors, and numeric indices.
3. Update sanity checks to assert canonical shapes (e.g., schema asserts that `:child-ids/by-parent` values are vectors, never `nil`).
