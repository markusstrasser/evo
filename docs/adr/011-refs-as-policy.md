# ADR 011: References as Derived Indexes + Policy

## Status
Accepted

## Context
Graph features like citations, backlinks, and reference tracking are common in knowledge management systems. The question is: should references be part of the kernel or implemented as plugins?

## Decision
References are **NOT** kernel operations. Instead, they are:
1. **Stored** as node properties (`:refs` vector in `:props`)
2. **Derived** as indexes (backlinks, citation counts)
3. **Managed** by policy operations (scrubbing, linting)

The `plugins.refs.core` plugin demonstrates this pattern.

## Rationale

### Why Not Kernel?
Adding refs to the kernel would require:
- New operation type: `:create-ref`, `:delete-ref`
- Cascade deletion logic (what happens when target deleted?)
- Cycle detection in kernel validation
- Ref-specific invariants in `core.db/validate`

This violates the three-op principle and creates coupling between graph and tree concerns.

### Why Plugin?
As a plugin, refs:
- Use existing `:update-node` operation for all ref changes
- Compute backlinks via derived indexes (invertible, recomputable)
- Define scrubbing as **policy** (configurable, not enforced)
- Keep kernel focused on tree structure

## Architecture

### Storage (Node Props)
```clojure
{:nodes
 {"A" {:type :p
       :props {:text "See X"
               :refs ["X" "Y"]}}}}
```

### Derived Indexes
```clojure
{:derived
 {:ref-outgoing {"A" #{"X" "Y"}}
  :ref-backlinks {"X" #{"A"} "Y" #{"A"}}
  :ref-citation-count {"X" 1 "Y" 1}}}
```

### Policy Operations
```clojure
;; Scrubbing: remove dangling refs
(refs/scrub-dangling-refs db)
;; => [{:op :update-node :id "A" :props {:refs ["X"]}}]

;; Linting: find issues
(refs/lint db)
;; => [{:reason ::dangling-ref :dst "Y" :srcs #{"A"}
;;      :suggest {:action :drop-refs}}]
```

## Key Insights

1. **Refs are Data**: A ref is just a node ID in a vector. No special kernel support needed.

2. **Backlinks are Inverted**: Computing backlinks is just inverting the `:ref-outgoing` map. This is pure, recomputable, and fast.

3. **Scrubbing is Policy**: Different applications have different deletion policies:
   - Drop dangling refs immediately
   - Keep dangling refs and show as warnings
   - Redirect refs to replacement nodes
   - Move ref targets to archive before removing

4. **Separation of Concerns**: The kernel maintains tree structure. Plugins handle graph features.

## Consequences

### Positive
- **Kernel Simplicity**: Three ops remain the only mutations
- **Flexibility**: Apps can choose their ref deletion policy
- **Composability**: Refs work with any tree structure
- **No Special Cases**: Refs use standard `:update-node` operations
- **Recomputable**: Derived indexes can be rebuilt from canonical state

### Negative
- **Not Enforced**: Kernel doesn't prevent dangling refs
- **Manual Scrubbing**: Apps must explicitly call scrub operations
- **Distributed Logic**: Ref behavior spans storage + derived + policy

### Mitigations
- Lint function finds issues without mutating
- Scrub operations are explicit and auditable
- Derived indexes are fast (O(n) in nodes with refs)

## Extension Points

### Custom Policies
Apps can implement domain-specific ref policies:
```clojure
(defn scrub-with-redirect [db redirect-map]
  ;; Retarget refs using custom mapping
  ...)

(defn archive-before-delete [db node-id]
  ;; Move node to archive, then scrub refs
  ...)
```

### Additional Indexes
Plugins can compute additional derived data:
```clojure
{:ref-graph {...}           ;; DAG for cycle detection
 :ref-weights {...}         ;; Edge weights for ranking
 :ref-transitive {...}}     ;; Transitive closure
```

## Examples

### Adding a Ref
```clojure
(refs/add-ref db "src" "dst")
;; => {:op :update-node
;;     :id "src"
;;     :props {:refs ["dst"]}}
```

### Finding Backlinks
```clojure
(refs/get-backlinks db "page-x")
;; => #{"page-a" "page-b" "page-c"}

(refs/citation-count db "page-x")
;; => 3
```

### Linting and Scrubbing
```clojure
;; Find issues
(refs/lint db)
;; => [{:reason ::dangling-ref :dst "missing" :srcs #{"A"}}
;;     {:reason ::circular-ref :node "B"}]

;; Generate fix ops
(refs/scrub-dangling-refs db)
;; => [{:op :update-node :id "A" :props {:refs [...]}}]
```

## References
- ADR 000: Refs as Derived (original principle)
- `plugins.refs.core` (reference implementation)
- Three-op kernel architecture
