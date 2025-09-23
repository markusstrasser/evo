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

## Fractional Ordering Bug Fixes (Latest Debugging Session)

### 26. DataScript Query Variable Binding Bug
**Error Encountered**: `get-ordered-siblings` returning incorrect order values from unrelated entities
**Root Cause**: Query used wildcard `_` instead of proper variable binding

**Broken Query**:
```clojure
'[:find ?o :in $ ?p :where [_ :parent ?p] [_ :order ?o]]
```
**Problem**: The `_` wildcards match different entities, so `?o` can come from any entity with an `:order` attribute, not just children of `?p`.

**Fixed Query**:
```clojure  
'[:find ?o :in $ ?p :where [?e :parent ?p] [?e :order ?o]]
```
**Solution**: Use the same variable `?e` to ensure we get order values only from entities that are actually children of `?p`.

### 27. Fractional Ordering Edge Cases
**Error Encountered**: `get-mid-string(nil, "m")` returning "m" instead of a string that comes before "m"
**Root Cause**: Faulty logic handling empty prefix cases and different string lengths

**Key Fixes Made**:
1. **Empty prefix case**: When `prev=nil` and `next` exists, return `char(max(int('a'), dec(int(first(next)))))`
2. **Different length strings**: When strings share a prefix but have different lengths (e.g., "m" vs "mm"), calculate the middle character correctly
3. **Bounds checking**: Added proper bounds checking for character arithmetic

**Before Fix**: `get-mid-string("m", "mm")` → `"mm"` (incorrect, not between "m" and "mm")
**After Fix**: `get-mid-string("m", "mm")` → `"mg"` (correct, sorts between "m" and "mm")

### 28. Lazy Sequence Indexing Bug  
**Error Encountered**: `get` returning `nil` on lazy sequences from `sort-by`
**Root Cause**: `get` function doesn't work on lazy sequences, only on indexed collections

**Broken Code**:
```clojure
(let [siblings (sort-by str (mapv first siblings-raw))  ; Returns lazy seq
      next-order (get siblings (inc target-idx))]       ; get returns nil!
```

**Fixed Code**:
```clojure  
(let [siblings (sort-by str (mapv first siblings-raw))
      next-order (when (< (inc target-idx) (count siblings))
                   (nth siblings (inc target-idx)))]     ; nth works on lazy seqs
```

### 29. Shadow-cljs REPL vs Build Process State Issues
**Symptom**: Tests pass in REPL after fixes, but fail until `bun dev` is restarted
**Root Cause**: Potential state divergence between REPL reloading and Shadow-cljs build process

**Likely Explanations**:
- Shadow-cljs compilation caching of old JavaScript
- File watcher compilation timing vs REPL `:reload` 
- Hot reload state not fully reset with namespace reload
- Browser caching compiled JavaScript (if serving to browser)

**Debug Commands for Future**:
```clojure  
;; Force full recompilation instead of just :reload
(require '[namespace :as alias] :reload-all)

;; Check what version of code is actually loaded
(meta #'namespace/function-name)
```

**Practical Solution**: When in doubt, restart the build process to ensure clean state.

### 30. Systematic Debugging for Fractional Ordering
**Effective Pattern**: Test fractional ordering functions in isolation before testing full tree operations

```clojure
;; Test fractional ordering edge cases directly  
(#'fractional-ordering/get-mid-string nil "m")     ; Should be < "m"
(#'fractional-ordering/get-mid-string "m" "mm")    ; Should be between "m" and "mm" 
(#'fractional-ordering/get-mid-string "mm" nil)    ; Should be > "mm"

;; Test query results before using them
(let [siblings-raw (d/q '[:find ?o :in $ ?p :where [?e :parent ?p] [?e :order ?o]] db parent-ref)]
  (println "Raw query result:" siblings-raw)
  (println "After processing:" (sort-by str (mapv first siblings-raw))))

;; Test ordering with actual string comparisons
(let [result (calculate-order db parent-ref position)]
  (println "Calculated order sorts correctly:"
    (= (sort ["existing1" result "existing2"]) 
       ["existing1" result "existing2"])))
```

**Key Insight**: Fractional ordering bugs often compound - a small error in string generation gets magnified when used in tree operations. Testing the math functions in isolation catches issues before they become complex tree state problems.

**Key Lesson**: Simple, explicit approaches often outperform "clever" database features that have unpredictable edge cases.

## Datalog Rules for Transitive Closure (Latest Refactoring Session)

### 31. Unifying Imperative and Declarative Query Patterns
**Discovery**: Mixed imperative/declarative approaches create cognitive dissonance and maintenance burden

**Problem Identified**:
- Simple queries used declarative Datalog: `'[:find [?cid ...] :in $ ?pid :where ...]`
- Complex queries dropped back to imperative Clojure recursion: `collect-descendant-ids`
- Developers had to reason about two different mental models and execution patterns
- Recursive tree traversal logic was scattered between query layer and application code

**Root Cause**: **Transitive Closure** is a classic graph problem that Datalog is explicitly designed to solve through recursive rules, but many developers default to programmatic recursion in the host language.

### 32. Datalog Recursive Rules Implementation
**Solution**: Define recursive rules that capture the transitive closure of parent-child relationships entirely within the query language.

**Rules Definition**:
```clojure
(def rules
  '[[(subtree-member ?ancestor ?descendant)
     [?descendant :parent ?ancestor]]           ; Direct parent-child
    [(subtree-member ?ancestor ?descendant)
     [?descendant :parent ?intermediate]        ; Indirect through chain
     (subtree-member ?ancestor ?intermediate)]])
```

**Usage in Queries**:
```clojure
;; Before: Imperative recursion mixed with declarative query
(defn- collect-descendant-ids [db parent-id]
  (let [child-ids (d/q '[:find [?cid ...] :in $ ?pid :where
                         [?p :id ?pid] [?c :parent ?p] [?c :id ?cid]]
                       db parent-id)]
    (concat child-ids (mapcat #(collect-descendant-ids db %) child-ids))))

;; After: Pure declarative query using rules
descendant-ids (d/q '[:find [?did ...]
                      :in $ % ?pref
                      :where
                      [?d :id ?did]
                      (subtree-member ?pref ?d)]
                    db rules parent-ref)
```

### 33. Benefits of Unified Declarative Approach
**Achieved Results**:
- ✅ **Cognitive Consistency**: All queries now use the same declarative Datalog pattern
- ✅ **Encapsulation**: Tree traversal logic is fully contained within the data layer (schema + rules)
- ✅ **Reduced Complexity**: Single query replaces multi-line recursive function  
- ✅ **Performance**: Datalog engine optimizes recursive rule evaluation
- ✅ **Maintainability**: Logic changes only require updating rules, not scattered application code

**Code Density Improvement**:
- **Before**: 8 lines of imperative recursion + separate query logic
- **After**: 4 lines of declarative rules + single unified query pattern

### 34. Datalog Rules Testing Pattern  
**Verification Approach**: Test rules independently before using in application logic

```clojure
;; Test transitive closure rules directly
(def test-conn (d/create-conn schema))
(d/transact! test-conn [{:id "root"}
                        {:id "child1" :parent [:id "root"]}
                        {:id "grandchild1" :parent [:id "child1"]}])

;; Verify all descendants found correctly
(d/q '[:find [?did ...] :in $ % ?pref :where
       [?d :id ?did] (subtree-member ?pref ?d)]
     @test-conn rules [:id "root"])
;; => ["child1" "grandchild1"] ✅

;; Verify partial subtree queries work  
(d/q '[:find [?did ...] :in $ % ?pref :where
       [?d :id ?did] (subtree-member ?pref ?d)]
     @test-conn rules [:id "child1"])
;; => ["grandchild1"] ✅
```

### 35. Architecture Principle: Query Language Unification
**Design Guideline**: **When your data operations mix declarative and imperative approaches, look for opportunities to express the complex operations declaratively through the query language's built-in features.**

**Application**: 
- Recursive tree operations → Datalog recursive rules
- Complex filtering → Advanced Datalog pattern matching  
- Aggregations across relationships → Datalog aggregation functions
- Graph traversals → Datalog path finding rules

**Decision Framework**:
- ✅ Can the operation be expressed as "what data relationships exist?" → Use declarative query
- ❌ Does the operation require complex business logic, formatting, or external I/O? → Use imperative code

**Result**: More consistent, maintainable, and debuggable data access patterns throughout the application.

### 36. Datalog Rules Integration Best Practices
**Pattern**: Define rules alongside schema as foundational data layer constructs

**File Organization**:
```clojure
;; ## 1. SCHEMA ##
(def schema {...})

;; ## 2. DATALOG RULES ##  
(def rules [...])

;; ## 3. CORE LOGIC ##
(defn command->tx [...])  ; Uses both schema and rules
```

**Testing Strategy**: 
1. **Schema validation**: Ensure entities can be created/stored correctly
2. **Rules validation**: Test rules with simple test data independently  
3. **Integration testing**: Verify rules work correctly within application transactions
4. **Regression testing**: Ensure all existing functionality remains working

**Key Insight**: Datalog rules are as foundational to the data layer as the schema itself. They should be defined early, tested independently, and used consistently throughout the application rather than treated as an advanced or optional feature.

## Clojure Tree CRUD Architecture (Latest Kernel Development)

### 37. DataScript Schema Evolution for Ordered Trees
**Final Schema Design**: Minimal but complete schema for ordered tree operations
```clojure
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref}
   :order {:db/index true}
   :parent+order {:db/tupleAttrs [:parent :order]
                  :db/unique :db.unique/value}})
```

**Key Design Decisions**:
- `:id` as identity for stable entity references across operations
- `:parent` ref for tree hierarchy - enables efficient parent→child queries  
- `:order` indexed for fast sibling ordering operations
- `:parent+order` tuple constraint prevents ordering conflicts (each position unique within parent)

### 38. Integer vs Fractional Ordering Trade-offs
**Migration**: Replaced complex fractional string ordering with simple integer-based system

**Fractional Ordering (Previous)**:
```clojure
(defn- rank-between [a b]
  (loop [i 0, acc ""]
    (let [lo (code a i)
          hi (let [x (code b i)] (if (pos? x) x (inc base)))]
      ;; Complex string manipulation for infinite precision
      )))
```

**Integer Ordering (Current)**:
```clojure
(defn- between [conn p lo hi]
  (let [o (if (and lo hi)
            (quot (+ lo hi) 2)  ; Simple midpoint calculation
            (if lo (+ lo 1000) (if hi (- hi 1000) 1000)))]
    (if (and lo hi (= o lo))
      (do (renumber! conn p) (between conn p lo hi))  ; Renumber when gaps too small
      o)))
```

**Trade-off Analysis**:
- **Algorithmic Complexity**: Dramatically reduced (no string manipulation)
- **Conceptual Simplicity**: Much easier to debug and reason about
- **Performance**: Similar practical performance, but requires occasional renumbering
- **LoC**: Minimal reduction (~2 lines) because infrastructure complexity increased

**Key Insight**: The win was **maintainability** and **debuggability**, not code size reduction.

### 39. Position Resolution Architecture
**Unified Position System**: Single function handles all positioning operations (:first, :last, :before, :after)

```clojure
(defn- resolve-position [conn {:keys [parent sibling rel] :as pos}]
  (let [db @conn
        parent-id (or parent (when sibling (pid db sibling)))
        pref [:id parent-id]
        os (orders db pref)  ; Get existing sibling orders
        ord (case rel
              :first (between conn pref nil (first os))
              :last (between conn pref (last os) nil)  
              :before (let [t (:order (e db sibling))
                           [bef _] (neighbors os t)]
                       (between conn pref bef t))
              :after (let [t (:order (e db sibling))
                          [_ aft] (neighbors os t)]
                      (between conn pref t aft))
              ;; default append
              (between conn pref (last os) nil))]
    [pref ord]))
```

**Benefits**:
- ✅ **Unified API**: All positioning operations use same interface
- ✅ **Automatic Renumbering**: Handles dense insertions transparently  
- ✅ **Sibling-Relative Positioning**: Can position relative to any sibling
- ✅ **Stable Ordering**: Order values persist across operations

### 40. CRUD Operations with Position Support
**Tree Insertion**: Single operation handles both entity creation and positioning
```clojure
(defn insert! [conn entity {:keys [parent sibling] :as position}]
  (ensure-new-ids! @conn entity)  ; Prevent ID conflicts
  (let [[pref root-order] (resolve-position conn position)]
    (d/transact! conn (walk->tx conn entity pref root-order))))
```

**Tree Movement**: Atomic operation preserves subtree structure
```clojure
(defn move! [conn entity-id {:keys [parent sibling] :as position}]
  (let [db @conn
        [pref new-o] (resolve-position conn position)
        ent (e db entity-id)
        old-parent-ref [:id (-> ent :parent :id)]]
    (d/transact! conn
                 [[:db/retract [:id entity-id] :parent old-parent-ref]
                  [:db/retract [:id entity-id] :order (:order ent)]
                  [:db/add [:id entity-id] :parent pref]
                  [:db/add [:id entity-id] :order new-o]])))
```

**Cascade Deletion**: Manual traversal with Datalog rules
```clojure
(defn delete! [conn entity-id]
  (let [db @conn
        descendant-ids (d/q '[:find [?did ...] :in $ % ?pref :where
                             [?d :id ?did] (subtree-member ?pref ?d)]
                           db rules [:id entity-id])
        tx-data (mapv #(vector :db/retractEntity [:id %]) 
                     (cons entity-id descendant-ids))]
    (d/transact! conn tx-data)))
```

### 41. Tree Structure Testing Strategy
**Comprehensive Test Coverage**: Tests expose both tree and hypergraph requirements

**Tree Operations Tested**:
- Integer ordering properties with automatic renumbering
- Position resolution edge cases (empty parents, missing siblings)
- CRUD lifecycle with realistic document scenarios
- Complex restructuring (section reordering, bulk operations)
- Performance stress testing (50+ operations with ordering validation)

**Hypergraph Tests Added**:
- Cross-references beyond parent-child (`:validates-with`, `:submits-to`, `:contains`)
- Referential integrity when deleting referenced entities
- Bidirectional relationships and graph traversal patterns
- Disconnected subgraphs and orphaned components
- Multiple relationship types and reverse lookups

**Key Discovery**: Current tree system handles all ordering/positioning perfectly, but **hypergraph functionality requires schema extensions and referential integrity handling**.

### 42. DataScript Query Limitations for Hypergraph Operations
**Challenge**: DataScript's query predicates don't support collection operations needed for graph traversal

**Failed Approaches**:
```clojure
;; DataScript doesn't support 'some' predicate
[(some #(= % ?target) ?refs)]  ; ❌ Unknown predicate 'some'

;; DataScript doesn't support 'contains?' predicate  
[(contains? ?deps ?missing)]   ; ❌ Unknown predicate 'contains?'

;; get-else with nil default not supported
[(get-else $ ?e :triggered-by nil) ?triggered-by]  ; ❌ nil default not supported
```

**Working Solutions**:
```clojure
;; Manual collection processing outside query
(let [all-entities-with-deps (d/q '[:find ?id ?deps :where [?e :id ?id] [?e :depends-on ?deps]] @conn)
      broken-deps (for [[id deps] all-entities-with-deps
                       :when (some #(= % "service-x") deps)]
                   [id])]
  ;; Process results in Clojure
  )

;; Direct attribute matching where possible
(d/q '[:find ?id :where [?e :id ?id] [?e :runs-independently true]] @conn)
```

**Architecture Implication**: For true hypergraph functionality, will need either:
1. **DataScript extensions** for collection predicates
2. **Mixed query approach** (simple queries + Clojure processing)  
3. **Schema changes** to make relationships more query-friendly
4. **Custom traversal functions** for complex graph operations

### 43. Key Architecture Lessons Learned

**Schema Design**:
- ✅ **Minimal schemas are more reliable** - Avoid complex features until proven necessary
- ✅ **Explicit is better than implicit** - Manual cascade deletion vs :db/isComponent
- ✅ **Index what you query** - :order indexed for fast sibling operations
- ✅ **Unique constraints prevent bugs** - :parent+order tuple prevents positioning conflicts

**Transaction Patterns**:
- ✅ **Single transactions when possible** - Avoid complex multi-phase approaches
- ✅ **Test incrementally** - Simple entities → nested entities → complex operations
- ✅ **Validate references early** - ensure-new-ids! prevents ID conflicts
- ✅ **Use temp IDs for intra-transaction refs** - More reliable than lookup refs

**Query Strategy**:
- ✅ **Datalog rules for transitive operations** - Cleaner than imperative recursion
- ✅ **Mixed approaches for limitations** - Combine declarative queries with Clojure processing
- ✅ **Test rules independently** - Verify transitive closure logic before integration
- ✅ **Plan for query engine limitations** - Not all graph operations can be purely declarative

**Testing Philosophy**:
- ✅ **Test what you don't have** - Hypergraph tests drive future development
- ✅ **Progressive complexity** - Simple → realistic → stress testing
- ✅ **Expose limitations early** - Better to know constraints than encounter surprises
- ✅ **Document failure patterns** - Failed DataScript queries inform design decisions