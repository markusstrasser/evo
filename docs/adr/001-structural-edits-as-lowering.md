# ADR-001: Structural Edits as Lowering

## Status
Accepted

## Problem
Need structural editing operations (wrap, split, merge, move-section) without expanding kernel surface. These are compound operations that should decompose to atomic kernel ops rather than adding new fundamental operations.

## Constraints
- No new kernel operations
- Structural edits must be atomic (all-or-nothing)
- Must preserve tree invariants during compound operations
- Operations should be reversible

## Proposed Algebra

### Core Law: Structural Operations are Compound
Complex edits decompose to sequences of {create-node, place, update-node}.

```clojure
;; Wrap operation
(wrap-range {:parent "root" :ids ["a" "b"] :new-id "wrapper"})
;; Lowers to:
[{:op :create-node :id "wrapper" :type :div :under "root" :at {:before "a"}}
 {:op :place :id "a" :under "wrapper" :at :last}
 {:op :place :id "b" :under "wrapper" :at :last}]
```

### Operations

#### Wrap Range
Groups sibling nodes under new parent.
- **Precondition**: All nodes share same parent
- **Lowering**: Create wrapper, place children under wrapper
- **Invariant**: Tree structure preserved, no orphans

#### Unwrap
Removes wrapper, promotes children to wrapper's parent.
- **Precondition**: Wrapper exists and has parent  
- **Lowering**: Place children at wrapper's level, trash wrapper
- **Invariant**: No data loss, children maintain order

#### Split Node  
Divides node at cursor position.
- **Precondition**: Node exists and is splittable
- **Lowering**: Update original with split marker, create sibling with split data
- **Invariant**: Content preserved across both nodes

## Lowering Examples

```clojure
;; Split at cursor position
{:op :split-node :id "text1" :at 5}
;; Becomes:
[{:op :update-node :id "text1" :props {:split-at 5}}
 {:op :create-node :id "text1-split" :type :text}  
 {:op :place :id "text1-split" :under :same-parent :at {:after "text1"}}]

;; Merge nodes
{:op :merge-nodes :first "a" :second "b"}
;; Becomes:  
[{:op :update-node :id "a" :props {:merge-from "b"}}
 {:op :place :id "b" :under :trash :at :last}]
```

## Properties/Laws

1. **Locality**: Operations affect minimal node set
2. **Reversibility**: Each operation has defined inverse
3. **Atomicity**: Full sequence succeeds or fails together
4. **Tree Preservation**: Never creates invalid tree states

## Tradeoffs

### Benefits
- No kernel complexity increase  
- Operations composable with kernel ops
- Easy to test and reason about
- Adapter-level content handling

### Costs
- Multi-step operations need transaction boundaries
- Some compound operations may be verbose
- Content-level operations need adapter coordination