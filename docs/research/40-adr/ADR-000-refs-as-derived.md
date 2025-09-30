# ADR-000: References as Derived Policy

## Status
Accepted

## Problem
Need cross-node relations (backlinks, mentions, dependencies) without polluting the 3-op kernel algebra. Adding ref operations to core would expand the algebra from "tree + three ops" to general graphs, inheriting edge registries, cycle detection, scrub-on-delete semantics, and multi-relation constraints.

## Constraints
- Preserve kernel closure: tree + {create-node, place, update-node}
- Single source of truth for node data
- Graph queries (neighbors, backrefs, reachable) must be efficient
- Refs must be rebuildable from canonical data

## Proposed Algebra

### Core Law: Edges are Derived
Edges exist as rebuildable indexes over node properties, not as materialized state.

```clojure
;; Source of truth: node props
{:nodes {"a" {:props {:refs {:mentions #{"b" "c"}
                           :depends-on #{"x"}}}}}}

;; Derived: adjacency index  
{:edges {:mentions {"a" #{"b" "c"}}
         :depends-on {"a" #{"x"}}}}
```

### Operations
- **Add ref**: `{:op :update-node :id src :props {:refs {rel #{dst}}}}`
- **Remove ref**: `{:op :update-node :id src :props {:refs {rel #{}}}}`
- **Query**: Compute adjacency from current nodes; no separate state

### Lowering to 3-op
```clojure
;; Legacy sugar
{:op :add-ref :src "a" :dst "b" :relation :mentions}

;; Lowered to core  
{:op :update-node :id "a" :props {:refs {:mentions #{"b"}}}}
```

## Properties/Laws

1. **Rebuildable**: `(derive-edges db) = (derive-edges (interpret db []))`
2. **Scrub on delete**: Dangling refs detected in validation, not enforced in ops
3. **Idempotent**: `add-ref(add-ref(x)) = add-ref(x)`  
4. **Constraint separation**: Acyclic/unique constraints in validation, not ops

## Tradeoffs

### Benefits
- Kernel stays "tree + 3 ops"
- One source of truth (node props)
- Graph queries via derived indexes
- Constraints as findings, not mutation guards

### Costs  
- Edge queries require index rebuilding (mitigated by caching)
- Multi-edge metadata needs separate ledger
- Complex ref updates require merge logic in adapters