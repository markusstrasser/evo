# DataScript Developer's Guide: Quirks, Gotchas, and Best Practices

## Overview

DataScript presents itself as a Datomic-compatible database, but it has significant differences, limitations, and quirks that can cause major issues. This guide documents real-world problems encountered during development and their solutions.

## Critical DataScript Quirks

### 1. Lookup Reference Syntax is Strict and Unintuitive

**The Problem:** DataScript's lookup reference syntax is very specific and easy to get wrong.

**❌ Common Mistake:**
```clojure
;; This seems logical but FAILS
(d/q '[:find ?id ?p :in $ ?pid
       :where [?par :id ?pid] [?c :parent ?par] [?c :id ?id] [?c :pos ?p]]
     db "parent-id")
```

**✅ Correct Approach:**
```clojure
;; Must pass lookup reference as vector and use directly
(d/q '[:find ?id ?p :in $ ?parent-lookup-ref
       :where [?c :parent ?parent-lookup-ref] [?c :id ?id] [?c :pos ?p]]
     db [:id "parent-id"])
```

**Key Insight:** You cannot "find" a lookup reference within a query - you must pass it as a parameter and use it directly.

### 2. Constraint Validation Happens During Transactions, Not After

**The Problem:** DataScript validates constraints during transaction processing, not after completion. This creates "impossible" states where intermediate operations violate constraints.

**Example Scenario:**
```clojure
;; Schema with unique tuple constraint
{:parent+pos {:db/tupleAttrs [:parent :pos] :db/unique :db.unique/value}}

;; This FAILS even though end state would be valid
(d/transact! conn [[:db/add [:id "a"] :pos 0]
                   [:db/add [:id "b"] :pos 1]  ; Temporarily both have pos 0!
                   [:db/add [:id "c"] :pos 2]])
```

**Error:** `Cannot add #datascript/Datom [entity :parent+pos [parent 0]] because of unique constraint`

**Solution - Two-Phase Approach:**
```clojure
;; Phase 1: Move to safe temporary values
(d/transact! conn [[:db/add [:id "a"] :pos -1000]
                   [:db/add [:id "b"] :pos -1001]
                   [:db/add [:id "c"] :pos -1002]])

;; Phase 2: Set final values
(d/transact! conn [[:db/add [:id "a"] :pos 0]
                   [:db/add [:id "b"] :pos 1]
                   [:db/add [:id "c"] :pos 2]])
```

### 3. `:db/isComponent` Behavior Differs from Datomic

**The Problem:** DataScript's cascade deletion with `:db/isComponent` behaves unpredictably and differently from Datomic.

**❌ What Doesn't Work:**
```clojure
;; Schema that should work but doesn't behave correctly
{:parent {:db/valueType :db.type/ref :db/isComponent true}}

;; Deleting a child can incorrectly delete parents or orphan grandchildren
;; Behavior is inconsistent and unreliable
```

**✅ Reliable Alternative - Manual Cascade:**
```clojure
;; Define recursive rules for tree traversal
(def rules
  '[[(subtree-member ?ancestor ?descendant)
     [?descendant :parent ?ancestor]]
    [(subtree-member ?ancestor ?descendant)
     [?descendant :parent ?intermediate]
     (subtree-member ?ancestor ?intermediate)]])

;; Implement manual cascade deletion
(defn delete! [conn entity-id]
  (let [descendants (d/q '[:find [?id ...] :in $ % ?parent-ref
                           :where [?d :id ?id] (subtree-member ?parent-ref ?d)]
                         @conn rules [:id entity-id])
        tx-data (mapv #(vector :db/retractEntity [:id %]) 
                     (cons entity-id descendants))]
    (d/transact! conn tx-data)))
```

### 4. Entity Reference Validation is Immediate and Strict

**The Problem:** DataScript validates entity references immediately, even within the same transaction.

**❌ This Fails:**
```clojure
;; Even though both entities are in same transaction, parent reference fails
(d/transact! conn [{:id "parent" :name "Parent"}
                   {:id "child" :parent [:id "parent"]}])
; Error: Nothing found for entity id [:id "parent"]
```

**✅ Use Temporary IDs:**
```clojure
;; Use string temp IDs for intra-transaction references
(d/transact! conn [{:db/id "temp-parent" :id "parent" :name "Parent"}
                   {:db/id "temp-child" :id "child" :parent "temp-parent"}])
```

### 5. Query Variable Binding Wildcards Are Dangerous

**The Problem:** Using `_` wildcards in queries can match different entities, leading to incorrect results.

**❌ Broken Query:**
```clojure
;; This can return orders from different parents!
(d/q '[:find ?o :in $ ?p 
       :where [_ :parent ?p] [_ :order ?o]]
     db parent-ref)
```

**✅ Use Consistent Variables:**
```clojure
;; Same variable ensures orders come from children of the specified parent
(d/q '[:find ?o :in $ ?p 
       :where [?e :parent ?p] [?e :order ?o]]
     db parent-ref)
```

### 6. Collection Functions in Queries Are Limited

**The Problem:** DataScript doesn't support many collection operations that seem obvious.

**❌ These Don't Work:**
```clojure
;; DataScript doesn't have these predicates
[(some #(= % ?target) ?collection)]     ; Error: Unknown predicate 'some'
[(contains? ?deps ?missing)]            ; Error: Unknown predicate 'contains?'
[(get-else $ ?e :attr nil) ?value]      ; Error: nil default not supported
```

**✅ Workarounds:**
```clojure
;; Option 1: Process collections in Clojure
(let [results (d/q '[:find ?id ?deps :where [?e :id ?id] [?e :deps ?deps]] db)
      filtered (filter (fn [[id deps]] (some #(= % target) deps)) results)]
  ;; Process in host language
  )

;; Option 2: Schema changes to make queries simpler
{:has-dependency {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}}
;; Now can query: [?e :has-dependency [:id "target"]]
```

### 7. Lazy Sequences and Collection Functions Don't Mix

**The Problem:** Standard Clojure collection functions behave unexpectedly with lazy sequences from DataScript queries.

**❌ This Returns nil:**
```clojure
(let [orders (sort-by str (mapv first query-results))  ; Returns lazy seq
      next-order (get orders target-index)]             ; get returns nil!
```

**✅ Use Sequence-Aware Functions:**
```clojure
(let [orders (sort-by str (mapv first query-results))
      next-order (when (< target-index (count orders))
                   (nth orders target-index))]          ; nth works on lazy seqs
```

## DataScript vs Datomic Differences

### Transaction Behavior
- **Datomic:** Validates constraints after transaction completion
- **DataScript:** Validates constraints during transaction processing
- **Impact:** Need two-phase approaches for constraint-safe operations

### Component Relationships
- **Datomic:** `:db/isComponent` works reliably for cascade deletion
- **DataScript:** `:db/isComponent` has unpredictable behavior
- **Impact:** Must implement manual cascade deletion

### Query Engine Capabilities
- **Datomic:** Richer set of built-in predicates and functions
- **DataScript:** Limited predicate support, missing collection operations
- **Impact:** More host-language processing required

### Entity Reference Resolution
- **Datomic:** More forgiving with reference resolution timing
- **DataScript:** Immediate validation even within transactions
- **Impact:** Must use temporary IDs for intra-transaction references

## Best Practices for DataScript Development

### Schema Design

**✅ Do:**
```clojure
;; Use tuple constraints for compound uniqueness
{:parent+pos {:db/tupleAttrs [:parent :pos] :db/unique :db.unique/value}}

;; Index attributes you'll query frequently
{:order {:db/index true}}

;; Keep schemas minimal and explicit
{:id {:db/unique :db.unique/identity}
 :parent {:db/valueType :db.type/ref}
 :pos {:db/cardinality :db.cardinality/one}}
```

**❌ Don't:**
```clojure
;; Avoid :db/isComponent in DataScript
{:children {:db/valueType :db.type/ref 
            :db/cardinality :db.cardinality/many
            :db/isComponent true}}  ; Unreliable!

;; Don't rely on complex DataScript features
{:complex-attr {:db/valueType :db.type/tuple 
                :db/tupleTypes [:db.type/string :db.type/long :db.type/ref]}}
```

### Transaction Patterns

**✅ Safe Constraint Updates:**
```clojure
;; Two-phase approach for constraint-sensitive operations
(defn reorder-safely! [conn parent-id ids]
  ;; Phase 1: Move to temporary safe values
  (d/transact! conn (map-indexed #([:db/add [:id %2] :pos (- -1000 %1)]) ids))
  ;; Phase 2: Set final values
  (d/transact! conn (map-indexed #([:db/add [:id %2] :pos %1]) ids)))
```

**✅ Intra-Transaction References:**
```clojure
;; Use temporary IDs for entities created in same transaction
(defn create-tree! [conn tree-data]
  (let [temp-id (str "temp-" (random-uuid))]
    (d/transact! conn [{:db/id temp-id :id (:id tree-data)}
                       {:db/id (str temp-id "-child") 
                        :id (:child-id tree-data)
                        :parent temp-id}])))
```

### Query Patterns

**✅ Reliable Variable Binding:**
```clojure
;; Always use the same variable for related attributes
(d/q '[:find ?id ?pos :in $ ?parent-ref
       :where [?e :parent ?parent-ref] [?e :id ?id] [?e :pos ?pos]]
     db parent-lookup-ref)
```

**✅ Datalog Rules for Complex Operations:**
```clojure
;; Define recursive rules for tree operations
(def rules
  '[[(descendant ?ancestor ?desc)
     [?desc :parent ?ancestor]]
    [(descendant ?ancestor ?desc)
     [?desc :parent ?intermediate]
     (descendant ?ancestor ?intermediate)]])

;; Use in queries
(d/q '[:find [?id ...] :in $ % ?root
       :where [?d :id ?id] (descendant ?root ?d)]
     db rules [:id root-id])
```

### Error Handling

**✅ Validate References Before Transactions:**
```clojure
(defn safe-insert! [conn entity position]
  ;; Check if parent exists
  (when-let [parent-id (:parent position)]
    (when-not (d/entity @conn [:id parent-id])
      (throw (ex-info "Parent not found" {:parent parent-id}))))
  ;; Proceed with insert
  (d/transact! conn (build-insert-tx entity position)))
```

**✅ Check for ID Conflicts:**
```clojure
(defn ensure-new-ids! [db entity]
  (when (d/entity db [:id (:id entity)])
    (throw (ex-info "Entity ID already exists" {:id (:id entity)}))))
```

## Testing Strategies for DataScript

### Test Incrementally
```clojure
;; Start simple
(deftest basic-entity-creation
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "test"}])
    (is (d/entity @conn [:id "test"]))))

;; Add complexity gradually
(deftest entity-with-references
  ;; ... test entity references
  )

(deftest constraint-validation
  ;; ... test constraint edge cases
  )
```

### Test Constraint Edge Cases
```clojure
;; Test constraint violations explicitly
(deftest unique-constraint-enforcement
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "a" :parent [:id "root"] :pos 0}])
    (is (thrown-with-msg? Exception #"unique constraint"
          (d/transact! conn [{:id "b" :parent [:id "root"] :pos 0}])))))
```

### Isolate Query Testing
```clojure
;; Test queries independently from application logic
(deftest query-variable-binding
  (let [conn (d/create-conn schema)]
    ;; Set up test data
    (d/transact! conn test-data)
    ;; Test query returns expected results
    (is (= expected-result 
           (d/q test-query @conn)))))
```

## Common Error Messages and Solutions

### "Nothing found for entity id [:id \"...\"]"
**Cause:** Using lookup reference for entity that doesn't exist or incorrect reference timing
**Solution:** Ensure entity exists before referencing, or use temporary IDs

### "Cannot add #datascript/Datom [...] because of unique constraint"
**Cause:** Intermediate transaction state violates unique constraint
**Solution:** Use two-phase transactions with temporary values

### "Unknown predicate 'some'/'contains?'/etc."
**Cause:** DataScript doesn't support all Clojure collection functions
**Solution:** Process collections in Clojure code after query

### "get returns nil on sequence"
**Cause:** Using `get` on lazy sequence from query
**Solution:** Use `nth` with bounds checking or realize sequence first

## Migration from Datomic

If migrating from Datomic to DataScript:

1. **Remove `:db/isComponent`** - Implement manual cascade deletion
2. **Update transaction patterns** - Use two-phase approach for constraint-sensitive operations  
3. **Simplify queries** - Avoid collection predicates, process in host language
4. **Add reference validation** - DataScript is stricter about entity existence
5. **Test constraint behavior** - Constraints validate during transactions, not after

## Performance Considerations

### Query Optimization
- **Index frequently queried attributes** with `:db/index true`
- **Use lookup references** instead of manual entity resolution
- **Prefer Datalog rules** over imperative recursion for tree operations

### Transaction Optimization
- **Batch related operations** into single transactions when possible
- **Use temporary IDs** to avoid multiple lookup operations
- **Minimize constraint violations** with careful transaction ordering

### Memory Management
- **Be aware of lazy sequences** from queries - realize them if you need random access
- **Use specific queries** instead of pulling entire entities when possible
- **Clean up large datasets** periodically if using in-memory DataScript

## Debugging DataScript Issues

### Enable Query Logging
```clojure
;; Add debug output to understand query behavior
(let [result (d/q query db)]
  (println "Query result:" result)
  (println "Processed:" (process-result result))
  result)
```

### Inspect Entity State
```clojure
;; Check entity state before and after operations
(defn debug-entity [db entity-id]
  (let [entity (d/entity db [:id entity-id])]
    (println "Entity" entity-id ":" (into {} entity))))
```

### Test Constraints in Isolation
```clojure
;; Test constraint behavior with minimal data
(deftest constraint-behavior
  (let [conn (d/create-conn {:test {:db/unique :db.unique/value}})]
    ;; Test constraint enforcement
    (d/transact! conn [{:test "value1"}])
    (is (thrown? Exception
          (d/transact! conn [{:test "value1"}])))))
```

This guide represents real-world experience with DataScript's quirks and provides practical solutions for robust DataScript applications.