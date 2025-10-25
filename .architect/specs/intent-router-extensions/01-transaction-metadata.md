# Proposal: Transaction Metadata for Intent Router

**Status:** Discussion
**Created:** 2025-10-24
**Related:** ADR-016 (Intent Router)

## Problem

Currently, operations carry no contextual information beyond their semantic content. This makes it difficult to:

1. **Group operations for undo/redo** - Each operation creates a separate undo point
2. **Track operation source** - Can't distinguish local vs remote, keyboard vs API
3. **Debug complex flows** - No way to trace where an operation originated

Example: Multi-select delete compiles to 3 separate delete ops, but they should undo as one unit.

## Proposed Solution

Add optional `meta` parameter to `apply-intent` that flows through the pipeline without affecting core logic:

```clojure
;; API
(apply-intent db intent {:meta {:undo-boundary :atomic
                                 :source :keyboard
                                 :trigger "Cmd+Shift+D"}})

;; Returns
{:db db' :ops [...] :path :ops :meta {...}}

;; interpret can access metadata
(defn interpret [db ops {:keys [meta]}]
  (when (:undo-boundary meta)
    (create-undo-snapshot! db))
  ;; ... apply ops ...
  )
```

## Use Cases

### 1. Undo/Redo Boundaries
```clojure
;; Group related operations
(apply-intent! {:type :indent-selected}
               {:meta {:undo-boundary :atomic}})
;; All 3 indent ops undo together
```

### 2. Collaborative Editing
```clojure
;; Track operation source
{:meta {:source :remote
        :client-id "alice"
        :timestamp 1234567890}}

;; Different conflict resolution for remote ops
(if (= :remote (:source meta))
  (merge-remote-op op)
  (broadcast-local-op op))
```

### 3. Debugging
```clojure
;; Track operation origin
{:meta {:source :keyboard
        :trigger "Ctrl+Shift+D"
        :stack-trace ...}}
```

## Implementation

1. Add optional `opts` map to `apply-intent` (15 lines)
2. Thread `meta` through result (5 lines)
3. Update `interpret` to accept `meta` (10 lines)

Total: ~30 lines, non-breaking change (opts is optional)

## When to Implement

- ✅ When implementing undo/redo
- ✅ When adding collaborative editing
- ✅ When debugging complex operation flows
- ❌ Not needed for simple single-user app

## Tradeoffs

**Pros:**
- Clean separation of semantics from context
- Non-breaking addition
- Enables undo/redo grouping
- Useful for debugging

**Cons:**
- Slight API complexity (optional param)
- Need to decide what metadata is "standard"
- Must ensure metadata doesn't affect operation semantics

## Decision

**Defer until needed.** The architecture supports adding this cleanly when we implement undo/redo or collaborative editing.
