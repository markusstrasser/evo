# Proposal 90 · Malli Registry for Kernel Schemas

- **Date**: 2025-09-29
- **Status**: Draft

## Context & Pain
`core.schema` hand-builds a `compiled-schemas` map of validators and returns it from `describe-ops`.

```clojure
;; src/core/schema.clj:110-129
(def compiled-schemas
  {:Op-Create (m/validator Op-Create)
   :Op-Place (m/validator Op-Place)
   ...})

(defn describe-ops []
  compiled-schemas)
```

Every new schema requires updating the literal map, keeping validators and raw schemas in sync across namespaces. Labs modules (e.g. `labs.structure`) cannot extend the union without editing this file.

## Inspiration
Malli already supports mutable and composite registries. The `malli.registry/*` APIs let us register schemas once and query them anywhere.

- `/Users/alien/Projects/inspo-clones/malli/src/malli/registry.cljc` (`mutable-registry`, `set-default-registry!`, lines 1-120)

## Proposal
1. Define a dedicated registry for kernel schemas:

```clojure
(def kernel-registry (atom {}))
(malli.registry/set-default-registry! (malli.registry/mutable-registry kernel-registry))
```

2. Replace the literal `compiled-schemas` map with registration helpers:

```clojure
(defn register-schema! [k schema]
  (swap! kernel-registry assoc k schema)
  (malli.registry/schema (malli.registry/custom-default-registry) k))

(register-schema! ::op Op)
(register-schema! ::tx Transaction)
```

3. Update consumers:
   - `core.schema/valid-op?` becomes `(m/validate (registry-schema ::op) op)`.
   - `describe-ops` returns `(malli.registry/schemas (malli.registry/custom-default-registry))`.
4. Allow plugins to register extension schemas (`::graph/edge`, `::intent/payload`) without editing kernel files.

## Expected Benefits
- **Extensibility**: plugins register schemas at load time, keeping kernel closed yet configurable.
- **Less duplication**: validators created on demand; no need to keep map values and schema vars in sync.
- **Instrumentation**: registry metadata can include docstrings/examples for REPL tooling.

## Trade-offs & Risks
- Introduces dynamic registry state; ensure registration happens during deterministic init (e.g. via `integrant`/`mount`).
- Consumers must tolerate missing schemas (helpful error messages).

## Next Steps
1. Spike registry-backed `describe-ops`, update tests to assert the returned map matches expectations.
2. Port labs code to call `register-schema!` for experimental types, validating the extensibility story.
3. Document registry usage in `docs/DEV.md` to guide plugin authors.
