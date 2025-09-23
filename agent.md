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

## DataScript Entity Reference Resolution (Latest Bug Fix Session)

### 13. DataScript Entity Reference Validation Order
**Error Encountered**: `Nothing found for entity id [:id "main"]` when creating nested entities
**Root Cause**: DataScript validates entity references **immediately** within the same transaction, even before all entities in that transaction are created.

**Problem Details**:
- When creating child entities with `:parent [:id "parent-id"]` references
- DataScript tries to resolve `[:id "parent-id"]` immediately 
- If the parent entity doesn't exist yet in the same transaction, the transaction fails
- This happens even with the `:db/unique :db.unique/identity` schema on `:id`

### 14. The Two-Phase Approach Limitation
**What I Tried**: Complex two-phase transaction approach:
1. Phase 1: Create entities with only `:parent` relationships
2. Phase 2: Add `:children` relationships for cascade delete

**Problems**:
- Overly complex code (went from 8 lines to 20+ lines in `create!`)
- Still had entity reference issues between phases
- Lost the clean, simple API design
- Added debugging complexity

### 15. Temporary IDs: The Correct Solution
**Discovery**: DataScript supports temporary IDs for intra-transaction references
**Solution**: Use string temporary IDs instead of lookup references within transactions

**Before (Broken)**:
```clojure
{:db/id [:id "child-id"]
 :id "child-id"
 :parent [:id "parent-id"]  ; ← Fails if parent doesn't exist yet
 :order 1.0}
```

**After (Working)**:
```clojure
{:db/id "temp-child"       ; ← Temporary ID
 :id "child-id" 
 :parent "temp-parent"     ; ← Reference to parent's temp ID
 :order 1.0}
```

### 16. Implementation Pattern for Nested Entities
**Pattern**: Generate consistent temporary IDs for both entity creation and references
```clojure
(defn- tree->txns [entity-map parent-ref order]
  (let [entity-id (or (:id entity-map) (str (random-uuid)))
        temp-id (str "temp-" entity-id)  ; Consistent temp ID generation
        child-temp-ids (mapv #(str "temp-" (or (:id %) (str (random-uuid)))) children)]
    ;; Create entity with temp-id and reference children by their temp-ids
    {:db/id temp-id
     :id entity-id
     :parent parent-ref
     :children child-temp-ids}))  ; References match child entities' temp-ids
```

### 17. Clean API Restoration 
**Result**: Restored the original clean `create!` function:
```clojure
(defn create!
  "Creates a new, potentially nested, entity at a specified position."
  [conn entity-map position-spec]
  (let [db @conn
        parent-id (get-parent-id db position-spec)
        parent-ref [:id parent-id]
        order (calculate-order db parent-ref position-spec)
        tx-data (tree->txns entity-map parent-ref order)]
    (d/transact! conn tx-data)))
```
- ✅ Single transaction (no complex phases)
- ✅ Simple, readable code (8 lines)
- ✅ Maintains cascade delete functionality
- ✅ All tests pass

### 18. Key DataScript Transaction Lessons
1. **Use temporary IDs for intra-transaction references** - Never use lookup references like `[:id "entity-id"]` for entities being created in the same transaction
2. **Temporary ID consistency is critical** - The temp ID used in references must exactly match the temp ID used in entity creation
3. **DataScript validates references immediately** - Even within the same transaction, references are validated as soon as they're encountered
4. **Avoid nil values in attributes** - Use `cond->` or conditional logic to avoid storing `nil` values (DataScript rejects them)
5. **Test incrementally** - Add debug output and test simple cases first before complex nested structures

### 19. Debug Testing Pattern
**Pattern**: Add informative test cases to understand failures:
```clojure
(deftest debug-tests
  ;; Test simple entities first
  (println "=== Testing simple entity ===")
  (create! conn {:id "simple"} {:rel :first :target "root"})
  
  ;; Test entities with empty children
  (println "=== Testing empty children ===") 
  (create! conn {:id "parent" :children []} {:rel :first :target "root"})
  
  ;; Test nested entities last
  (println "=== Testing nested entity ===")
  (create! conn nested-entity {:rel :first :target "root"}))
```

This incremental approach helps isolate exactly where failures occur and what DataScript can/cannot handle.

## DataScript `:db/isComponent` vs Manual Cascade Deletion (Latest Session)

### 20. The Pragmatic Solution: Skip `:db/isComponent` Entirely
**Discovery**: After attempting multiple approaches to make `:db/isComponent` work with nested entity creation, the simplest and most reliable solution is to avoid it completely.

**Problems with `:db/isComponent` in DataScript**:
- Requires establishing parent→child references via `:children` attributes
- Intra-transaction reference resolution fails when trying to reference entities being created in the same transaction
- Two-phase transactions add significant complexity to `position!` 
- Behavior differences from Datomic create confusion

**Simple Alternative**: Manual cascade deletion in `delete!` function
```clojure
(defn- collect-descendant-ids [db parent-id]
  (let [child-ids (d/q '[:find [?cid ...] :in $ ?pid :where
                         [?p :id ?pid] [?c :parent ?p] [?c :id ?cid]]
                       db parent-id)]
    (concat child-ids (mapcat #(collect-descendant-ids db %) child-ids))))

(defn delete! [conn entity-id]
  (let [db @conn
        all-to-delete (cons entity-id (collect-descendant-ids db entity-id))
        tx-data (mapv #(vector :db/retractEntity [:id %]) all-to-delete)]
    (d/transact! conn tx-data)))
```

**Benefits of Manual Approach**:
- ✅ `position!` stays simple (4 lines)
- ✅ No complex transaction ordering
- ✅ Explicit and predictable behavior  
- ✅ No dependency on DataScript's component semantics
- ✅ Easy to understand and debug

**Schema Simplification**:
```clojure
;; Simple schema - no :children or :db/isComponent
(def schema {:id {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}
             :order {:db/index true}})
```

### 21. Key Lesson: Pragmatism Over Purity
**Principle**: When database features create more complexity than they solve, implement the functionality manually.

**Application**: Rather than fighting DataScript's `:db/isComponent` limitations, we achieved the same cascade deletion behavior through explicit traversal. This kept both `position!` and `delete!` simple and readable.

**Pattern**: Use direct Datalog queries for tree traversal instead of relying on component relationships that may not work as expected across different database implementations.

## Test-Driven DataScript Entity Reference Resolution (Latest Session)

### 22. Missing Root Entity - Immediate Reference Validation
**Error Encountered**: `Nothing found for entity id [:id "root"]` when running tests
**Root Cause**: DataScript validates entity references **immediately**, even for existing entities outside the current transaction.

**Problem Details**:
- Test was trying to create children with `:parent [:id "root"]` reference
- But no entity with `:id "root"` existed in the database
- DataScript's reference validation fails before the transaction can complete
- This is different from referencing entities being created in the same transaction (where temp IDs work)

**Simple Fix**: Create the referenced entity first
```clojure
;; Before (fails)
(deftest revised-kernel-test
  (let [conn (d/create-conn schema)]
    (position! conn {:id "child1"} {:rel :first :target "root"}))) ; ← "root" doesn't exist

;; After (works)
(deftest revised-kernel-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root" :name "Root"}])  ; ← Create root first
    (position! conn {:id "child1"} {:rel :first :target "root"})))
```

### 23. Subtree Operations Work Seamlessly 
**Discovery**: Once entity references are properly handled, all subtree operations work correctly without additional complexity.

**Comprehensive Subtree Operations Verified**:
1. **Deep nesting** - 3+ level hierarchies work without transaction issues
2. **Moving subtrees** - Parent changes correctly, children maintain their parent relationships
3. **Cascade deletion** - Manual cascade deletion works reliably at any depth
4. **Complex positioning** - All positioning operations (`:first`, `:last`, `:after`, `:before`) work across multiple levels

**Key Insight**: The fractional ordering system (`:order` attribute) and manual cascade deletion provide all the functionality needed for complex tree operations without DataScript's problematic `:db/isComponent` feature.

### 24. Test Strategy for Tree Operations
**Pattern**: Test progressively from simple to complex operations
```clojure
;; 1. Simple parent-child relationships
(position! conn {:id "child"} {:rel :first :target "parent"})

;; 2. Multiple children with ordering  
(position! conn {:id "child2"} {:rel :after :target "child1"})

;; 3. Deep nesting (3+ levels)
(position! conn {:id "grandchild"} {:rel :first :target "child"})

;; 4. Moving subtrees
(position! conn {:id "child"} {:rel :first :target "new-parent"})

;; 5. Cascade deletion
(delete! conn "parent") ; Should delete all descendants
```

**Testing Benefits**:
- ✅ Catches reference resolution issues early
- ✅ Verifies parent-child relationships are maintained during moves
- ✅ Confirms cascade deletion works at all levels
- ✅ Validates ordering is preserved across operations

### 25. Final Architecture Validation
**Result**: The simplified schema + manual cascade deletion approach handles all real-world tree operations:

```clojure
;; Simple, reliable schema
(def schema {:id {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}  
             :order {:db/index true}})

;; Single function handles create + move + positioning
(defn position! [conn entity-map position-spec] ...)

;; Manual cascade deletion works reliably  
(defn delete! [conn entity-id] ...)
```

**Architectural Benefits**:
- ✅ No complex transaction phases needed
- ✅ No dependency on DataScript's `:db/isComponent` quirks
- ✅ Predictable behavior for all tree operations
- ✅ Easy to test and debug
- ✅ Works with arbitrarily deep nesting

**Key Lesson**: Simple, explicit approaches often outperform "clever" database features that have unpredictable edge cases.