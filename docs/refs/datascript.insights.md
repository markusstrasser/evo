# DataScript - Ingenious Patterns Analysis

## Repository: tonsky/datascript
**Category**: Immutable In-Memory Database
**Language**: Clojure/ClojureScript
**Paradigm**: Functional Database + EAV Data Model

## 📁 Key Namespaces to Study
- `datascript.core` - Main database API and operations
- `datascript.db` - Core DB record and datom operations
- `datascript.query` - Datalog query engine implementation
- `datascript.pull` - Pull API for nested data retrieval
- `datascript.storage` - Incremental storage mechanisms
- `datascript.btset` - Persistent sorted set implementation

---

## 🧠 The 10 Most Ingenious Patterns

### 1. **Immutable Database with Persistent Data Structures** ⭐⭐⭐⭐⭐
**Location**: `src/datascript/db.cljc`
**Key Functions**: `db-with`, `transact!`, `DB` record

Every database operation returns a new database instance while efficiently sharing unchanged data:

```clojure
(ns datascript.db
  (:require [datascript.btset :as btset]))

(defrecord DB [eavt aevt avet schema max-eid max-tx]
  ;; Three persistent sorted sets as indexes
  ;; - eavt: Entity-Attribute-Value-Transaction
  ;; - aevt: Attribute-Entity-Value-Transaction
  ;; - avet: Attribute-Value-Entity-Transaction (selective)
  )

;; Creating new immutable database versions
(defn db-with [db tx-data]
  (let [report (resolve-tx-data db tx-data)]
    (reduce add-datom (:db-before report) (:tx-data report))))

;; Example usage:
(let [db1 (d/empty-db)
      db2 (d/db-with db1 [{:name "Alice" :age 30}])
      db3 (d/db-with db2 [{:name "Bob" :age 25}])]
  ;; db1, db2, db3 all remain valid and share structure
  (count (d/datoms db1 :eavt)) ; => 0
  (count (d/datoms db2 :eavt)) ; => 2 (name + age)
  (count (d/datoms db3 :eavt))) ; => 4
```

**Innovation**: Uses Clojure's persistent data structures to create truly immutable databases where old versions remain accessible and structural sharing makes operations efficient.

### 2. **Triple Index Strategy with Smart Index Selection**
**Location**: `src/datascript/db.cljc`
**Key Functions**: `-search`, `datoms`, `seek-datoms`

Maintains three sorted indexes (EAVT, AEVT, AVET) and automatically chooses the optimal one for queries:

```clojure
;; In datascript.db
(defn -search [db [e a v tx]]
  (cond
    ;; Entity first - use EAVT index
    (some? e)
    (let [from (Datom. e a v tx true)
          to   (Datom. e a v tx false)]
      (btset/slice (:eavt db) from to))

    ;; Attribute + Value - use AVET index
    (and (some? a) (some? v) (is-attr-indexed? db a))
    (let [from (Datom. e a v tx true)
          to   (Datom. e a v tx false)]
      (btset/slice (:avet db) from to))

    ;; Attribute only - use AEVT index
    (some? a)
    (let [from (Datom. e a v tx true)
          to   (Datom. (inc max-eid) a v tx false)]
      (btset/slice (:aevt db) from to))))

;; Usage examples showing automatic optimization:
(d/datoms db :eavt 123)           ; Fast: uses EAVT index
(d/datoms db :avet :name "Alice") ; Fast: uses AVET index (if indexed)
(d/datoms db :aevt :age)          ; Fast: uses AEVT index
```

**Innovation**: Query planner automatically selects the most efficient index based on query pattern, making range queries and lookups extremely fast.

### 3. **Datom as Universal Data Primitive**
**Location**: `src/datascript/db.cljc`
**Key Type**: `Datom` record

Everything is represented as immutable 5-tuples [E A V T added?]:

```clojure
(defrecord Datom [e a v tx added]
  ;; e: entity id
  ;; a: attribute keyword
  ;; v: value
  ;; tx: transaction id
  ;; added: boolean (true = assert, false = retract)
  )

;; All data becomes datoms:
{:user/name "Alice" :user/age 30 :db/id 123}

;; Becomes:
;; [123 :user/name "Alice" 1001 true]
;; [123 :user/age 30 1001 true]

;; Retractions are explicit:
;; [123 :user/age 30 1002 false]  ; Retract age
;; [123 :user/age 31 1002 true]   ; Assert new age
```

**Innovation**: Single uniform representation for all database operations enables elegant algorithms and complete audit trails.

### 4. **Datalog Query Engine with Implicit Joins**
**Location**: `src/datascript/query.cljc`
**Key Functions**: `q`, `parse-query`, `resolve-clause`

Declarative queries with automatic join optimization:

```clojure
;; Complex multi-way joins written declaratively
(d/q '[:find ?user ?friend-name ?common-interest
       :where
       [?user :user/name ?name]
       [?user :user/friends ?friend]
       [?friend :user/name ?friend-name]
       [?user :user/interests ?interest]
       [?friend :user/interests ?interest]
       [?interest :interest/name ?common-interest]]
     db)

;; The query engine automatically:
;; 1. Determines optimal join order
;; 2. Uses appropriate indexes for each clause
;; 3. Implements efficient constraint propagation
;; 4. Handles negation and disjunction

;; Advanced features:
(d/q '[:find ?e ?a ?v
       :where
       [?e ?a ?v]
       [(> ?v 100)]           ; Predicate filters
       [(.startsWith ?a "user")]] ; Java method calls
     db)
```

**Innovation**: Full Datalog implementation with implicit joins, constraint propagation, and extensible with arbitrary Clojure predicates.

### 5. **Pull API for Nested Data Retrieval**
**Location**: `src/datascript/pull.cljc`
**Key Functions**: `pull`, `pull-many`

GraphQL-like nested data fetching with a simple syntax:

```clojure
;; Pull nested data in one operation
(d/pull db '[:user/name
             :user/email
             {:user/friends [:user/name :user/age]}
             {:user/posts [:post/title :post/content
                          {:post/comments [:comment/text
                                         {:comment/author [:user/name]}]}]}]
        user-id)

;; Returns nested map:
;; {:user/name "Alice"
;;  :user/email "alice@example.com"
;;  :user/friends [{:user/name "Bob" :user/age 25}
;;                 {:user/name "Carol" :user/age 28}]
;;  :user/posts [{:post/title "Hello World"
;;                :post/content "..."
;;                :post/comments [{:comment/text "Great post!"
;;                               :comment/author {:user/name "David"}}]}]}

;; With wildcards and limits:
(d/pull db '[* {:user/friends 5}] user-id) ; All attrs + first 5 friends
```

**Innovation**: Eliminates N+1 query problems with a single declarative specification for complex nested data retrieval.

### 6. **Lazy Entity Maps with Automatic Navigation**
**Location**: `src/datascript/core.cljc`
**Key Functions**: `entity`, `Entity` type

Entities behave like maps but lazily load data and follow references:

```clojure
;; Entity acts like a map but with special powers
(let [user (d/entity db user-id)]
  (:user/name user)                    ; => "Alice"
  (:user/friends user)                 ; => [#Entity{:db/id 124} #Entity{:db/id 125}]
  (-> user :user/friends first :user/name) ; => "Bob" (auto-navigation!)

  ;; Touch to realize all attributes
  (d/touch user)                       ; => {:db/id 123 :user/name "Alice" ...}

  ;; Entities implement map interfaces
  (keys user)                          ; => (:db/id :user/name :user/email ...)
  (get user :user/age)                 ; => 30
  (assoc user :temp/data "value"))     ; => new map (not persisted)
```

**Innovation**: Makes graph navigation feel natural while maintaining laziness and providing full map compatibility.

### 7. **Filtered Database Views**
**Location**: `src/datascript/core.cljc`
**Key Functions**: `filter`, `FilteredDB` type

Create dynamic database views without copying data:

```clojure
;; Create filtered views based on predicates
(defn published-only [db datom]
  (or (not= (:a datom) :post/published)
      (true? (:v datom))))

(defn user-scoped [user-id]
  (fn [db datom]
    (or (not= (:a datom) :post/author)
        (= (:v datom) user-id))))

;; Apply filters
(let [published-db (d/filter db published-only)
      user-db      (d/filter db (user-scoped 123))]

  ;; Queries on filtered DBs only see allowed data
  (d/q '[:find ?title :where [_ :post/title ?title]] published-db)
  (d/q '[:find ?post :where [?post :post/author 123]] user-db))
```

**Innovation**: Zero-copy database views that dynamically filter data at query time, enabling security and multi-tenancy patterns.

### 8. **Incremental Storage with B-Tree Persistence**
**Location**: `src/datascript/storage.cljc`
**Key Protocol**: `IStorage`

Efficient persistence by only storing changed B-tree nodes:

```clojure
(ns datascript.storage)

;; Storage protocol for persistence backends
(defprotocol IStorage
  (-store [storage addr node])
  (-restore [storage addr]))

;; Incremental storage tracks changes
(defn store-db [storage db]
  (let [old-root (get-stored-root storage)
        new-nodes (collect-new-nodes (:eavt db) old-root)]
    ;; Only store changed B-tree nodes
    (doseq [[addr node] new-nodes]
      (-store storage addr node))
    ;; Update root pointer
    (store-root storage (get-root-addr (:eavt db)))))

;; Garbage collection for old nodes
(defn gc-storage [storage]
  (let [reachable (find-reachable-nodes storage)]
    (remove-unreachable-nodes storage reachable)))
```

**Innovation**: Persistent storage that only writes changed B-tree nodes, enabling efficient incremental backups and version history.

### 9. **Transaction Functions and Tempid Resolution**
**Location**: `src/datascript/core.cljc`
**Key Functions**: `transact!`, `resolve-tempid`

Atomic transactions with temporary ID resolution and transaction functions:

```clojure
;; Tempids automatically resolved to real entity IDs
(let [tempid (d/tempid :db.part/user)
      result (d/transact! conn [{:db/id tempid
                                :user/name "Alice"
                                :user/email "alice@example.com"}
                               [:db/add tempid :user/friends another-tempid]])]
  ;; Tempids become real IDs atomically
  (d/resolve-tempid (:db-after result) (:tempids result) tempid))

;; Transaction functions for complex operations
(defn add-friend [db user-a user-b]
  [{:db/id user-a :user/friends user-b}
   {:db/id user-b :user/friends user-a}])

(d/transact! conn [[:db.fn/call add-friend user-1 user-2]])
```

**Innovation**: Automatic temporary ID resolution and transaction functions enable complex atomic operations while maintaining referential integrity.

### 10. **Schema-Optional Design with Flexible Constraints**
**Location**: `src/datascript/core.cljc`
**Key Functions**: `empty-db`, schema definition

Schema is optional but enables powerful optimizations when provided:

```clojure
;; Schemaless - everything works
(let [db (d/empty-db)]
  (d/db-with db [{:anything/goes "value"
                  :numbers/work 42
                  :refs/too      [:db/add "tempid" :other/attr "val"]}]))

;; Schema enables optimizations and constraints
(let [schema {:user/email    {:db/unique :db.unique/identity}
              :user/friends  {:db/valueType   :db.type/ref
                             :db/cardinality :db.cardinality/many}
              :user/age      {:db/index true}  ; Enable AVET index
              :user/settings {:db/valueType :db.type/ref
                             :db/isComponent true}}] ; Cascade operations
      db (d/empty-db schema)]
  ;; Now queries on :user/age and :user/email are optimized
  ;; Unique constraints are enforced
  ;; Component entities cascade on retraction
  )
```

**Innovation**: Progressive enhancement through optional schema - start simple, add optimizations and constraints as needed without changing code.

---

## 🎯 Key Takeaways

1. **Immutability as Foundation**: Every operation preserves old state while efficiently creating new state
2. **Index Strategy**: Multiple sorted indexes with automatic selection for optimal query performance
3. **Uniform Representation**: Everything is datoms, enabling elegant algorithms and complete auditability
4. **Declarative Queries**: Datalog provides powerful querying without manual join optimization
5. **Graph Navigation**: Lazy entities make reference following natural and efficient
6. **Zero-Copy Views**: Filtered databases enable security and multi-tenancy without data duplication
7. **Incremental Everything**: From compilation to storage, minimize work by tracking changes
8. **Schema Optional**: Start simple, add constraints and optimizations progressively
9. **Client-Side Database**: Brings database semantics to client-side applications
10. **Functional Database**: Applies functional programming principles to database design

DataScript demonstrates how functional programming principles can create a database that's both powerful and simple, suitable for client-side applications while maintaining the full power of a graph database.