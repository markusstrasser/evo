# ADR 010: Order Maintenance via Anchor Algebra and Permutations

## Status
Accepted

## Context
The three-op kernel uses `:place` operations to move nodes within the tree. However, complex reordering operations (like multi-select moves, drag-and-drop, and structural editing commands) require careful sequencing of multiple `:place` operations to achieve deterministic results.

Without a formal model for position resolution and reordering, we risk:
- Non-deterministic behavior when anchors reference moving nodes
- Difficult-to-debug ordering bugs
- Inability to prove correctness of complex reorder operations
- Fragile structural editing implementations

## Decision
We introduce two complementary algebras for order maintenance:

### 1. Anchor Algebra (`kernel.anchor`)
Provides explicit, total resolution of positioning specifications:
- **Anchors**: Closed set of position specifications (`:first`, `:last`, `{:before id}`, `{:after id}`, `{:at-index i}`)
- **Total Resolution**: Every anchor resolves to a concrete index or throws with machine-parsable error
- **Separation of Concerns**: Intent-level anchors (`:at-start/:at-end`) normalize to op-level anchors (`:first/:last`)
- **Error Recovery**: All errors include `:suggest` field with recovery hints

### 2. Permutation Engine (`plugins.permute`)
Lowers high-level reorder/move intents to minimal sequences of `:place` operations:
- **Planned Positions**: Computes target sibling order before emitting any ops
- **Sequential Lowering**: Emits one `:place` per selected node, using relative anchors
- **Deterministic**: Same intent + same DB state = same op sequence
- **Cross-Parent**: Handles moves across parents atomically

## Key Insights
1. **Remove → Resolve → Insert**: Anchor resolution always happens on the state *after* removing the moving node. This matches `:place` semantics and prevents self-reference bugs.

2. **Intent vs Op Level**: Intents use readable names (`:at-start`, `:reorder`), ops use schema-compliant names (`:first`, `:place`). The permute plugin bridges this gap.

3. **Anchors as Data**: Position specifications are pure data, making them serializable, inspectable, and testable.

4. **Permutations as Proofs**: By computing target order first, we can verify correctness before emitting any ops.

## Consequences

### Positive
- **Determinism**: Reorder operations produce identical results given identical input
- **Debuggability**: Every error includes concrete index, reason, and suggested fix
- **Composability**: Complex edits compose from simple anchors
- **Testability**: Golden tests can verify reorder behavior exactly
- **Provability**: Permutation properties (idempotence, inverse) are property-testable

### Negative
- **Indirection**: Structural edits require intent → ops lowering step
- **Complexity**: Two algebras (anchor + permutation) instead of ad-hoc positioning
- **Learning Curve**: Developers must understand anchor semantics

### Mitigations
- Anchor errors include human-readable hints
- Golden tests document expected behavior
- Plugin layer hides complexity from kernel

## Examples

### Simple Reorder
```clojure
;; Intent: move B and D after A
{:intent :reorder
 :selection ["B" "D"]
 :parent "P"
 :anchor {:after "A"}}

;; Lowers to:
[{:op :place :id "B" :under "P" :at {:after "A"}}
 {:op :place :id "D" :under "P" :at {:after "B"}}]
```

### Cross-Parent Move
```clojure
;; Intent: move B and C from P to Q
{:intent :move
 :selection ["B" "C"]
 :parent "Q"
 :anchor :last}

;; Lowers to same ops as reorder
;; :place automatically handles cross-parent
```

## References
- Three-op kernel architecture (ADR 000)
- Electric/Hyperfiddle incseq permutation algebra (inspiration)
- `kernel.permutation` module (pure permutation algebra)
