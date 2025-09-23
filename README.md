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