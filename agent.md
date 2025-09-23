# Agent Gotchas: MCP + REPL Development

## Actual Errors From This Session

### 1. ClojureScript REPL Context Confusion
**Error I Made**: Tried `(shadow/repl :frontend)` from ClojureScript REPL
```
------ WARNING - :undeclared-ns ------------------------------------------------
No such namespace: shadow, could not locate shadow.cljs
```
**Reality**: The `clojurescript_eval` tool IS the ClojureScript REPL. Don't try to connect again.

### 2. Missing Namespace Requires  
**Error I Made**: Used `r/render` without requiring the namespace
```
------ WARNING - :undeclared-var -----------------------------------------------
Use of undeclared Var r/render
```
**Fix**: Always require first
```clojure
(require '[replicant.dom :as r])
```

### 3. Wrong shadow-cljs API Attempt
**Error I Made**: Tried `(require '[shadow.cljs.devtools.api :as shadow])`
```
The required namespace "shadow.cljs.devtools.api" is not available
"shadow/cljs/devtools/api.clj" was found on the classpath. Maybe this library only supports CLJ?
```
**Reality**: That's a Clojure-only namespace. In ClojureScript REPL, just work directly.

### 5. File Write Safety Check
**Error I Made**: Tried to overwrite `agent.md` without reading it first
```
File has been modified since last read: /Users/alien/Projects/evo/agent.md
Please read the WHOLE file again with `collapse: false` before editing.
```
**Fix**: Always read file before writing to prevent overwrites.

### 6. DataScript Entity Reference Ordering
**Error Encountered**: `Nothing found for entity id [:id "span1"]` when creating nested tree structures
**Root Cause**: Transaction ordering issues with `:db/isComponent true` and entity references

**Problem**: When creating parent entities with `:children [[:id "child"]]` references, DataScript validates the reference before the child entity exists in the same transaction.

**Architecture Insight**: Bidirectional tree with `:db/isComponent true` on `:children` for automatic cascading delete:
```clojure
{:parent {:db/valueType :db.type/ref}                    ; Child→parent  
 :children {:db/valueType :db.type/ref                   ; Parent→children
            :db/cardinality :db.cardinality/many
            :db/isComponent true}}                        ; Cascade delete
```

**Solution**: Two-phase transaction approach:
1. **Phase 1**: Create all entities with only `:parent` relationships  
2. **Phase 2**: Add `:children` relationships after entities exist
```clojure
;; Phase 1: entities only  
(d/transact! conn entity-txns)
;; Phase 2: children refs for cascade delete
(d/transact! conn (mapv #([:db/add parent :children [:id %]]) child-ids))
```

### 7. Missing Function Implementation
**Error**: `Unable to resolve symbol: mapcat-indexed`
**Fix**: Replace with standard library equivalent:
```clojure
;; Before (doesn't exist)
(mapcat-indexed fn coll)

;; After (works)  
(mapcat (fn [[i item]] (fn i item)) (map-indexed vector coll))
```

## Key Takeaways

1. **Don't try to "connect" to ClojureScript REPL** - You're already in it
2. **Require namespaces before using** - Even obvious ones like replicant  
3. **Check atom state first** - `@store` before assuming values
4. **Read files before editing** - MCP safety mechanism
5. **ClojureScript ≠ Clojure** - Different available namespaces

## DataScript + Tree Architecture Lessons

6. **`:db/isComponent true` goes on parent→child refs** - Use on `:children` not `:parent` for cascading delete
7. **Entity reference validation is immediate** - References must exist in transaction order, use two-phase approach
8. **Bidirectional trees trade storage for functionality** - Redundant `:parent`/`:children` enables efficient queries + cascade delete
9. **Fractional ordering with `:order` attribute** - Enables stable positioning without renumbering siblings

## DataScript `:db/isComponent` Investigation (Latest Session)

### 10. Misunderstanding `:db/isComponent` Semantics
**Error I Made**: Incorrectly interpreted which entity gets deleted in component relationships
```
My wrong interpretation: ":db/isComponent on :parent means parent gets deleted when child is deleted"
```
**Reality**: Component relationship means the entity WITH the `:db/isComponent` attribute is a component of the entity it REFERENCES.

**Correct Semantics**:
- `:parent {:db/isComponent true}` = "This entity is a component of its parent"
- When parent is deleted, children (components) should cascade delete
- NOT the reverse (child deletion shouldn't affect parent)

### 11. DataScript vs Datomic Behavior Discrepancy
**Discovery**: DataScript's `:db/isComponent` implementation appears broken or different from Datomic
**Test Results**: 
```clojure
;; Schema: {:parent {:db/valueType :db.type/ref :db/isComponent true}}
;; Tree: root <- child1, root <- child2 <- grandchild
;; Action: Delete child2
;; Expected: child2 deleted, grandchild cascades delete, root untouched
;; Actual: child2 AND root deleted, grandchild orphaned
```
**Conclusion**: DataScript either has a bug or implements `:db/isComponent` differently than Datomic

### 12. Schema Design Implications
**Theoretical Advantage of `:parent` component approach**:
- ✅ Single-phase transactions (no complex two-phase needed)
- ✅ Cleaner entity creation code
- ✅ Correct semantic ownership (child is component of parent)

**Practical Reality in DataScript**:
- ❌ Cascading delete doesn't work correctly
- ❌ Unpredictable deletion behavior
- ❌ Forces manual cascade implementation

**Decision**: Current complex two-phase approach exists as workaround for DataScript limitations, not because the alternative schema is wrong.