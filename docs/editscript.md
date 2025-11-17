# Editscript: Data Diffing and Patching Library

## Overview

Editscript is a Clojure/ClojureScript library that "extracts the differences between two data structures as an editscript, which represents the minimal modification necessary to transform one to another."

## Core Functionality

The library generates efficient diffs for nested data structures including maps, vectors, lists, sets, and custom types. It produces three types of edits:
- **Deletion (`:−`)**: Remove elements
- **Addition (`:+`)**: Insert new elements  
- **Replacement (`:r`)**: Change values
- **String edits (`:s`)**: Character-level string modifications

## Usage Pattern

```clojure
(require '[editscript.core :as e])

(def a ["Hello word" 24 22 {:a [1 2 3]}])
(def b ["Hello world" 24 23 {:a [2 3]}])

(def d (e/diff a b))
(e/get-edits d)
;; Returns: [[[0] :r "Hello world"] [[2] :r 23] [[3 :a 0] :-]]

(= b (e/patch a d)) ;; true
```

## Two Diffing Algorithms

**A\* Algorithm** (Default)
Guarantees optimal editscript size through structure-preserving diffing. Maintains ancestor-descendant relationships and leverages Clojure's immutable structure sharing. Though slower than alternatives, it's practical for real-world data containing maps.

**Quick Algorithm**  
Uses Wu et al.'s O(NP) sequence comparison for blazing speed—up to 100x faster than A\*. Trades optimality for velocity; generates larger diffs when consecutive nested deletions occur.

## Key Advantages

- **Minimal diffs**: Optimal algorithms reduce storage and transmission overhead
- **Cross-platform**: Works on JVM Clojure and ClojureScript (Node, browser environments)
- **Production-tested**: Powers Juji's core systems; adopted by Clerk, Evident Systems, and others
- **Serializable**: Edits convert to plain vectors for storage/transmission

## Production Applications

The library powers data synchronization in systems requiring efficient state deltas: UI state persistence, CRDT implementations, game state syncing, and client-server reconciliation.

## Integration with Evo's Operation Model

Editscript can complement Evo's 3-op kernel by:
1. **Auto-generating operations** from state diffs during development/testing
2. **Network sync**: Send minimal diffs instead of full state
3. **Audit logs**: Track exactly what changed in complex operations
4. **Undo/redo**: Store efficient deltas instead of full snapshots
