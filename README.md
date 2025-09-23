# Evolver

## Design: Semantic UI REPL
You're designing a conversational interface canvas. The product is a tool where a user sculpts a fully reactive application by issuing natural language commands to an AI. Its core design principle moves beyond static layout tools by treating the interface not as a rigid tree of visual elements, but as a dynamic graph of interconnected components. A user can direct the AI to forge not just structural parent-child relationships, but also behavioral triggers, data-binding links, and semantic connections. This enables the rapid, iterative construction of both complex application logic and visual appearance from a single, unified conversational prompt, creating a fluid and deeply inspectable design environment.

## Design Decisions

### Tree Operations in DataScript
Core insight: Trees in DataScript require fighting the tool - it's a flat EAV store pretending to do hierarchies. But if committed to this approach, the API must prioritize atomic operations that maintain tree coherence.
What we learned:

- Position isn't conflation, it's a requirement. Creating an entity without position leaves the tree incoherent. Atomic create-with-position is a legitimate compound operation, not bad design.
- Order calculation belongs in the system, not client. The LLM/client should declare intent ({:after "x"}) not implementation details (order strings). This keeps fractional indexing mechanics hidden.
- Manual cascade deletion was the right call. DataScript's :db/isComponent is broken. Explicit descendant collection is more predictable.

The entire complexity stems from supporting operations like :after and :before.

### Fractional Ordering Implementation 
**Decision**: Replaced verbose ~70 LOC fractional indexing with canonical Greenspan-style implementation in ~25 LOC.

**Reasoning**: 
- Original implementation was overcomplicated for a toy problem
- New version uses standard 62-character alphabet (0-9, A-Z, a-z) for maximum density
- Returns lexicographically sortable strings without numeric conversion
- Handles edge cases (nil boundaries, gap exhaustion) with mathematical precision
- Maintains same API (`calculate-order`) for backward compatibility

**Key insight**: Fractional indexing is a solved problem. Use the canonical algorithm rather than reinventing string interpolation logic. The ~25 LOC version is more maintainable and follows established patterns from systems like Jira's LexoRank.

### Functional Naming and Pipeline Architecture 
**Decision**: Transformed procedural naming and coupled recursion into functional, declarative design.

**Changes**:
- Renamed functions to describe transformations not temporal roles (`calculate-order` → `resolve-rank`, `prepare-put-tx` → `tree->tx-data`)
- Replaced mutual recursion with decoupled data pipeline (`linearize-subtree` → `map linearized-node->tx`)
- Consolidated duplicate logic (added `find-surrounding-orders` helper)
- Used `reductions` instead of imperative `loop/recur`

**Reasoning**: Functions named after "when they execute" (`prepare-*`, `calculate-*`) reveal procedural thinking. Pure functions should describe **what they transform**, not their temporal role in a sequence. The pipeline separates tree traversal from transaction formatting, making the data flow explicit and each component independently testable.

### Integer-Based Ordering Architecture
**Decision**: Replaced complex fractional string ordering with simple integer system + automatic renumbering.

**Migration from Fractional to Integer**:
- **Before**: 70+ lines of string manipulation, base-62 encoding, complex gap detection
- **After**: ~25 lines with simple midpoint calculation: `(quot (+ lo hi) 2)`
- **Renumbering**: When gaps become too small (`o = lo`), automatically renumber siblings with 1000-unit spacing

**Key Trade-off Analysis**:
- ✅ **Algorithmic Complexity**: Dramatically reduced (no string manipulation)
- ✅ **Debuggability**: Integer orders much easier to inspect and reason about
- ✅ **Maintainability**: Fewer edge cases, clearer logic flow
- ❌ **Infrastructure Complexity**: Added renumbering system requiring connection access
- ≈ **LoC**: Similar total lines (~11 vs ~13) due to infrastructure additions

**Architecture Insight**: The win was **maintainability over code size**. While LoC didn't decrease significantly, the integer system trades mathematical complexity for infrastructural complexity, resulting in much more debuggable and understandable code.

### Schema Minimalism and Explicit Operations
**Decision**: Avoid DataScript's complex features in favor of explicit, predictable operations.

**Schema Evolution**:
```clojure
;; Final minimal schema
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref}
   :order {:db/index true}
   :parent+order {:db/tupleAttrs [:parent :order]
                  :db/unique :db.unique/value}})
```

**Rejected Approach**: `:db/isComponent` for automatic cascade deletion
- **Problem**: DataScript's `:db/isComponent` behaves unpredictably (different from Datomic)
- **Evidence**: Deleting child entities incorrectly deletes parent entities, orphans grandchildren
- **Solution**: Manual cascade deletion via Datalog rules

**Manual Cascade Implementation**:
```clojure
(def rules
  '[[(subtree-member ?ancestor ?descendant)
     [?descendant :parent ?ancestor]]           ; Direct parent-child
    [(subtree-member ?ancestor ?descendant)
     [?descendant :parent ?intermediate]        ; Transitive closure
     (subtree-member ?ancestor ?intermediate)]])

(defn delete! [conn entity-id]
  (let [descendant-ids (d/q '[:find [?did ...] :in $ % ?pref :where
                             [?d :id ?did] (subtree-member ?pref ?d)]
                           @conn rules [:id entity-id])]
    ;; Explicit retraction of all descendants
    ))
```

**Benefits of Explicit Approach**:
- ✅ **Predictable behavior** - no database feature surprises
- ✅ **Unified query patterns** - all operations use same Datalog approach  
- ✅ **Better debuggability** - cascade logic is visible and testable
- ✅ **Database independence** - doesn't rely on DataScript-specific behaviors

### CRUD API Design with Position Integration
**Decision**: Position specification is a core part of tree entity creation, not an optional afterthought.

**Unified Position Resolution**:
```clojure
(defn- resolve-position [conn {:keys [parent sibling rel] :as pos}]
  ;; Single function handles all positioning: :first, :last, :before, :after
  ;; Returns [parent-ref order-value] for transaction
  )
```

**API Integration**:
- `insert!` creates entities with mandatory position specification
- `move!` uses same position resolution for atomic subtree relocation
- `update!` handles only non-structural attributes (prevents accidental position corruption)

**Design Rationale**: Creating tree entities without position leaves the tree in an incoherent state. Rather than separate create/position operations, atomic create-with-position is a legitimate compound operation that maintains tree invariants.

### Hypergraph Test-Driven Development
**Decision**: Write comprehensive tests for functionality that doesn't exist yet to drive future architecture.

**Hypergraph Test Categories Added**:
1. **Cross-references**: Arbitrary entity relationships beyond parent-child (`:validates-with`, `:submits-to`, `:contains`)
2. **Referential integrity**: What happens when referenced entities are deleted (exposes dangling references)
3. **Bidirectional relationships**: Graph traversal patterns and consistency checks
4. **Disconnected subgraphs**: Entities outside tree hierarchy (floating dialogs, background services)
5. **Multiple relationship types**: Semantic relationships for UI component interactions

**Current Status**: All 66 assertions pass, demonstrating robust tree functionality while exposing exactly what's needed for hypergraph extensions.

**Architecture Insights from Test Failures**:
- DataScript query limitations: No `some`/`contains?` predicates for collection operations
- Need schema extensions for typed relationships beyond `:parent`
- Require referential integrity rules (cascade/cleanup options)
- Manual graph traversal helpers needed for complex queries

**Design Principle**: **Test what you don't have yet.** The hypergraph tests serve as both specification and acceptance criteria for future development, ensuring architectural decisions are driven by actual requirements rather than theoretical possibilities.

### Kernel Minimalism Refactoring (kernel-min.cljc)
**Decision**: Eliminate uniqueness constraint complexity by treating order as soft metadata that can be renumbered whenever touching a sibling list.

**Core Problem**: The `:parent+pos` unique constraint was forcing an "elegance tax" - complex two-phase transactions with negative temporary positions to avoid constraint violations during reordering.

**Solution Architecture**:
```clojure
;; Before: Complex schema with uniqueness constraint
{:parent+pos {:db/tupleAttrs [:parent :pos] :db/unique :db.unique/value}}

;; After: Simple schema, order as soft metadata
{:pos {:db/cardinality :db.cardinality/one :db/index true}}
```

**Key Simplifications**:
- **Single-phase transactions**: No more temp-pos counter or negative position gymnastics
- **Canonical list maintenance**: Every operation = `target → [parent-id idx] → splice vector → renumber once`
- **Rule-free cycle detection**: Simple closure instead of complex Datalog rules  
- **Direct subtree insertion**: Build entire subtree in one transaction with negative tempids

**Complexity Reduction**:
- `reorder!`: 6 lines → 1 line (`map-indexed`)
- `walk->tx`: Eliminated temp-pos counter and string-based temp IDs
- `move!`: Still 3 transactions but dramatically simpler logic
- **Overall**: Reduced moving parts, fewer transaction phases, easier debugging

**Design Insight**: The constraint was solving the wrong problem. Ordered children under mutable operations is just **list maintenance**, not constraint satisfaction. Renumbering on every touch is cheaper than constraint dance.

# ref docs

## Unknowns:

The bottleneck in UI development isn't expressing intent, it's debugging when intent doesn't match behavior.
The real challenge: ambiguity resolution at scale. Maybe I'll end up building a disambiguation UI that's more complex than just... writing code.

## TODO

```clojure
;; 1) keep these today (pure, testable)
(defn tx-insert [db entity position] (tree->tx-data db entity position))
(defn tx-delete [db entity-id]       (calc-delete-txs db entity-id))  ;; your existing logic
(defn tx-update [db entity-id attrs] (calc-update-txs entity-id attrs))
(defn tx-move   [db entity-id pos]   (calc-move-txs db entity-id pos))

;; 2) later: add the command adapter (doesn't change the above)
(defmulti command->tx (fn [_ {:keys [op]}] op))

(defmethod command->tx :insert [db {:keys [entity position]}]
(tx-insert db entity position))
(defmethod command->tx :delete [db {:keys [entity-id]}]
(tx-delete db entity-id))
(defmethod command->tx :update [db {:keys [entity-id attrs]}]
(tx-update db entity-id attrs))
(defmethod command->tx :move   [db {:keys [entity-id position]}]
(tx-move db entity-id position))
(defmethod command->tx :apply-txs [_ {:keys [tx-data]}] tx-data)
(defmethod command->tx :batch  [db {:keys [commands]}]
(mapcat #(command->tx db %) commands))

(defn execute! [conn cmd]
(d/transact! conn (vec (command->tx @conn cmd))))

;; Optional: keep today’s API, route through commands when you flip a flag.
(def ^:dynamic *use-commands* false)
(defn insert! [conn entity position]
(if *use-commands*
(execute! conn {:op :insert :entity entity :position position})
(d/transact! conn (tx-insert @conn entity position))))
```


https://code.thheller.com/blog/shadow-cljs/2024/10/18/fullstack-cljs-workflow-with-shadow-cljs.html