### 1. Core Idea

The current three-operation kernel (create-node, update-node, place) is elegantly minimal but can be simplified further by unifying create-node and update-node into a single "upsert-node" operation, reducing the instruction set to just two core ops: upsert-node (for node existence and content) and place (for structural positioning). Upsert-node would handle creation idempotently (creating a new node with type and props if it doesn't exist) and updates via deep-merge (overwriting or merging props if it does exist), while ignoring type changes on existing nodes to preserve immutability of node type. To address a missing primitive that complicates higher-level features like deletion or cleanup, add a lightweight "prune-node" op as a third (but optional) primitive, which removes a node from :nodes and automatically places its direct children under :trash at :last (non-recursive, to keep it simple). The canonical state remains largely unchanged but is simplified by folding :roots into :children-by-parent as special keyword-keyed entries, eliminating the separate :roots set—this treats roots uniformly as "virtual nodes" without special casing in ops or validation.

This unification emphasizes architectural elegance by reducing the op surface (from three to two core, with prune as an extension hook) while making the kernel more expressive for common patterns like "create-or-update" in one step. It maintains the closed instruction set principle but abstracts redundancy, allowing higher-level extensions (e.g., structural editing) to compile into fewer ops without losing purity or transactional guarantees.

### 2. Key Benefits

- **Simplicity**: Merging create and update eliminates redundant ops that differ only in existence checks, reducing the kernel's instruction set by 33% and simplifying the validation phase (fewer schemas and rules). Folding :roots into :children-by-parent removes a separate collection, unifying tree representation and reducing special cases in derive-indexes and validation (e.g., no need to check "all parents are roots or nodes").
  
- **Readability**: Ops become more intuitive—upsert-node reads as "ensure this node exists with these props," aligning with common data patterns (e.g., like SQL UPSERT). Prune-node provides a clear, built-in way to handle removal without forcing extensions to misuse place (e.g., moving to :trash manually), making intent obvious in transaction logs.

- **Debuggability**: With fewer ops, trace outputs in the pipeline are less noisy; enhancing interpret to always include per-op before/after snapshots (even on success) allows developers to inspect state evolution without custom logging. Prune-node's automatic child handling adds transparency to side effects, reducing hidden complexity in tree cleanup.

- **Expressiveness**: The upsert abstraction enables concise higher-level features (e.g., a single op for "create and initialize" instead of create + update). Prune-node fills a gap for destructive operations, easing extensions like batch deletion or garbage collection, while keeping the kernel extensible via policy (e.g., custom prune behaviors compiled to core ops).

### 3. Implementation Sketch

**Revised Canonical State** (in core.db):
```clojure
Db := {:nodes {Id → Node}  ; Unchanged: Node = {:type :keyword, :props :map}
       :children-by-parent {Parent → [Id]}}  ; Parent now uniformly [:or Id :keyword], absorbing :roots
       ; No separate :roots set—roots like :doc are just keys in :children-by-parent
```
Derived indexes remain similar, but parent-of and validation adapt to treat keyword parents as valid "virtual" nodes (no cycle checks needed for them).

**Revised Core Operations** (in core.ops):
```clojure
(upsert-node [db id type props] → Db)
  ; If id not in :nodes, add {:type type :props props}
  ; Else, deep-merge props into existing :nodes[id][:props] (ignore type if provided)
  ; Idempotent: no-op if exists and props unchanged

(place [db id under at] → Db)  ; Unchanged from original

(prune-node [db id] → Db)  ; New optional primitive
  ; Remove id from :nodes
  ; For each child in (:children-by-parent id), emit implicit place ops to :trash :last
  ; Remove (:children-by-parent id)
  ; No-op if id not exists; non-recursive (subtree children stay attached to direct children)
```

**Revised Transaction Pipeline** (in core.interpret, pseudocode):
```clojure
(interpret [db txs] → InterpretResult)
  ; 1. NORMALIZE: unchanged, but with merged upsert detection (e.g., combine adjacent upserts on same id)
  ; 2. VALIDATE: adapted schemas—Op now [:or Op-Upsert Op-Place Op-Prune]
  ;    Rules: for upsert, add :type-mismatch if type provided on existing node
  ;    for prune, add :has-descendants warning (not error, for expressiveness)
  ; 3. APPLY: thread db through reduce as before
  ; 4. DERIVE: unchanged
  ; Always return {:db final-db, :issues [...], :trace [{:op op, :before (select-keys db [...]), :after db'} ...]}
```

**Extension Example** (in core.struct, for delete intent):
```clojure
(compile-intent [db {:type :delete :id id}])
  ; Emits [{:op :prune-node :id id}]  ; Simpler than original place-to-trash
```

### 4. Tradeoffs and Risks

- **Tradeoffs**: Unifying create/update into upsert slightly increases op complexity (e.g., handling type ignoring), potentially making pure functions less "atomic" and requiring more validation logic. Adding prune-node expands the kernel slightly (back to three ops), trading minimalism for expressiveness—without it, deletions remain extension-level, which might be preferable for a truly minimal kernel. Eliminating :roots simplifies state but requires minor tweaks to derive-indexes (e.g., skipping virtual nodes in traversal), which could introduce edge cases in plugins.

- **Risks**: Idempotency in upsert might mask errors (e.g., accidental type ignores), leading to subtle bugs if developers expect strict creation. Prune-node's automatic child handling could surprise users expecting full recursive deletion, risking data loss if not documented well. Overall, the changes preserve invariants but might complicate schema evolution if future ops need to distinguish create vs. update semantics.

### 5. How It Improves Developer Experience

- **REPL**: Developers can experiment with fewer ops—e.g., `(upsert-node db "a" :bullet {:text "foo"})` handles create-or-update in one REPL call, reducing boilerplate vs. separate create + update. The always-on trace in interpret allows quick inspection like `(-> (interpret db ops) :trace last :after)`, making iterative prototyping faster without adding print statements.

- **Debugging**: Per-op before/after snapshots in :trace enable step-through debugging (e.g., via tools like cider-inspect), highlighting exactly how an upsert or prune affects state. Fewer ops mean shorter transaction logs, and unified state (no :roots) reduces confusion when dumping db for inspection.

- **Testing**: Property-based testing becomes easier with fewer schemas—e.g., generate upserts via Malli and test idempotency directly. The prune primitive allows testing deletion invariants in isolation, and the simplified state model reduces test setup (no need to populate :roots separately). Overall, this leads to more concise tests, like asserting `(= expected-db (:db (interpret db [upsert-op prune-op])))`, focusing on elegance over exhaustive case coverage.
