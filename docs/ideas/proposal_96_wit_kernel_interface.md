# Proposal 96 · Publish Kernel Contracts as a WIT Component Interface

## Pain Point Today
- Kernel contracts (ops, transaction results, invariants) are described in prose and ad-hoc Malli schemas. Cross-language tooling (TypeScript adapters, Rust renderers) need hand-rolled bindings, increasing drift risk.

## Inspiration
- The WebAssembly component model documents how self-describing interfaces enable composition across languages (`/Users/alien/Projects/inspo-clones/component-docs/component-model/src/design/components.md:1-80`).
- Components expose imports/exports via WIT, enabling automatic binding generation.

## Proposal
Author a `wit/kernel.wit` file that mirrors the three-op kernel surface:

```wit
package evo:kernel

world core {
  use types.{NodeId, NodeType, Props}

  export create-node: func(id: NodeId, type: NodeType, props: Props)
  export place: func(id: NodeId, under: ParentId, at: Anchor)
  export update-node: func(id: NodeId, patch: Props)
  export interpret: func(db: Db, txs: list<Op>) -> InterpretResult
}
```

Generate bindings for Clojure (via `wit-bindgen`'s type descriptions) and TypeScript to keep adapters honest.

### Before
- `core.schema` is Malli-only; other stacks must translate the spec manually.
- TypeScript shells duplicate `:children-by-parent` shape knowledge.

### After
- `kernel.core` implements a thin adapter layer `(wit/implement world core ...)` that delegates to existing functions.
- Adapters import generated bindings, ensuring DB shape and op types stay in sync.

## Payoff
- **Single source of truth**: WIT file becomes canonical spec for ops and results.
- **Multi-language safety**: adapters regenerate bindings whenever kernel evolves, preventing silent drift.
- **Future wasm adapters**: hosting the kernel in WASI (or embedding via `wasmtime`) becomes straightforward.

## Considerations
- Choose a WIT host strategy for Clojure—likely generate JSON schema from WIT and feed Malli to avoid runtime dependencies.
- Keep the WIT surface minimal (three ops + interpret + describe) to respect the kernel’s compact algebra.
