# Proposal 52 · Malli Transformer Interceptors for Op Normalization

## Problem
- Schema validation in `src/kernel/core.cljc:265-273` calls `S/validate-op!` which only checks shape; normalization (`pos` keywords, keywordization of ids, default props) is scattered across primitives.
- Adapters must sanitize ops before calling the kernel, leading to duplicated coercion logic (string ids vs. keywords, string booleans, etc.).

## Inspiration
- **malli.transform** composes interceptors that run `:enter`/`:leave` hooks around schema traversal, handling coercion declaratively (`/Users/alien/Projects/inspo-clones/malli/src/malli/transform.cljc:1-120`). Transformers can be compiled once and reused across CLJ/CLJS.

## Proposed change
1. Define transformer pipelines per op schema (e.g. `::place-op`) that coerce incoming data (strings → keywords, JSON numbers → ints) and apply defaults.
2. Modify `S/validate-op!` to run `(mt/decode schema op transformer)` prior to validation, producing normalized ops that primitives can trust.
3. Surface transformers so adapters can reuse the same normalization when constructing transactions outside the kernel.

```clojure
;; after (sketch)
(ns kernel.schemas
  (:require [malli.transform :as mt]))

(def json->op
  (mt/transformer
    mt/string-transformer
    (mt/function [:enter :op]
      (fn [op]
        (cond-> op
          (string? (:id op)) (update :id str)
          (string? (:parent-id op)) (update :parent-id str))))))

(defn validate-op! [x]
  (let [op (:op x)
        definition (op-definition-for op)
        decoded (if-let [xf (:transform definition)]
                  (xf x)
                  x)]
    (when-not (m/validate (:schema definition) decoded)
      (throw ...))
    decoded))
```
- Operation handlers receive normalized values (ids guaranteed strings, `:pos` canonicalized) reducing branching inside primitives.

## Expected benefits
- Eliminates ad hoc normalization code (e.g., `place*`’s `pos (or pos :last)`), shrinking primitive bodies.
- Agents can rely on consistent, schema-driven coercion across CLJ/CLJS transports (JSON, EDN, etc.).
- Future schema evolution (e.g., new optional keys) requires updating transformer metadata rather than touching every primitive.

## Trade-offs
- Slight overhead per op to run transformers; mitigate by compiling and memoizing interceptors (malli already optimizes this pattern).
- Need to ensure CLJS bundle includes transformer namespaces; add them to `shadow-cljs.edn` build.

## Implementation steps
1. Annotate `register-op!` definitions with optional `:transform` entry producing normalized ops.
2. Port existing normalization logic into transformer interceptors and remove duplicates from primitives/sugar ops.
3. Update tests to assert round-tripping through `validate-op!` returns expected normalized values.
