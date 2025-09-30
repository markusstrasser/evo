```markdown
╔══════════════════════════════════════════════════════════════════════════════╗
║                     THREE-OP KERNEL ARCHITECTURE                             ║
║              Tree Database with Transaction Pipeline                         ║
╚══════════════════════════════════════════════════════════════════════════════╝

┌─ Design Principles ──────────────────────────────────────────────────────────┐
│                                                                             │
│ • Canonical State: A minimal, normalized data model is the source of truth. │
│ • Derived Indexes: All other views (parent pointers, siblings) are derived. │
│ • Pure Operations: All state changes are pure functions: `(db, op) → db'`.   │
│ • Closed Instruction Set: Only three operations manipulate canonical state. │
│ • Transactional Pipeline: Ops are processed in a strict, multi-phase pipe.  │
│ • Extensibility via Policy: Higher-level features are compiled into core ops.│
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

═════════════════════════════════════════════════════════════════════════════
 1. Data Model (core.db)
═════════════════════════════════════════════════════════════════════════════
The database has a minimal canonical shape. All other data is derived from it,
ensuring consistency.

┌─ Canonical State ────────────────────────────────────────────────────────────┐
│                                                                             │
│  Db := {:nodes               {Id → Node}                                    │
│         :children-by-parent  {Parent → [Id]}                                │
│         :roots               #{:doc :trash ...}                             │
│         :derived             {...}}                                         │
│                                                                             │
│  • :nodes: A map of all nodes in the system, indexed by their unique ID.    │
│  • :children-by-parent: The core tree structure. Maps a parent ID to an     │
│    ordered vector of its child IDs.                                         │
│  • :roots: A set of keywords representing the top-level entry points.       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ Derived Indexes ────────────────────────────────────────────────────────────┐
│                                                                             │
│  All derived data is computed from the canonical state via `derive-indexes`.│
│                                                                             │
│  1. :parent-of {Id → Parent}                                                │
│     • Purpose: Fast parent lookup for any node. (O(1))                      │
│     • Inverts the `:children-by-parent` relationship.                       │
│                                                                             │
│  2. :index-of {Id → Int}                                                    │
│     • Purpose: Fast index lookup for a node within its sibling list. (O(1)) │
│                                                                             │
│  3. :prev-id-of {Id → Id | nil}                                             │
│     • Purpose: Fast lookup of the previous sibling. (O(1))                  │
│                                                                             │
│  4. :next-id-of {Id → Id | nil}                                             │
│     • Purpose: Fast lookup of the next sibling. (O(1))                      │
│                                                                             │
│  5. :pre {Id → Int}                                                         │
│     • Purpose: Pre-order traversal index for a node. (O(1))                 │
│                                                                             │
│  6. :post {Id → Int}                                                        │
│     • Purpose: Post-order traversal index for a node. (O(1))                │
│                                                                             │
│  7. :id-by-pre {Int → Id}                                                   │
│     • Purpose: Fast lookup of a node by its pre-order index. (O(1))         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ Threading Patterns ─────────────────────────────────────────────────────────┐
│                                                                             │
│  `derive-indexes` computes all derived data in a single pass.               │
│                                                                             │
│  db ─→ let [{:keys [children-by-parent roots]} db]                          │
│      ├─→ compute-parent-of   → {:parent-of ...}                             │
│      ├─→ compute-index-of    → {:index-of ...}                              │
│      ├─→ compute-siblings    → {:prev-id-of ... :next-id-of ...}            │
│      ├─→ compute-traversal   → {:pre ... :post ... :id-by-pre ...}          │
│      │     └─→ (letfn [(visit-node [state id] ...)] ...                      │
│      │           (reduce visit-node state-with-pre children)) ; Recursive   │
│      └─→ plugins/run-all     → {:plugin-data ...}                           │
│                                                                             │
│      ... then merge all results into db[:derived]                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

API Surface:
  (empty-db [] → Db)
    Returns a database with the correct canonical shape and empty collections.

  (derive-indexes [db] → Db)
    Recomputes all derived indexes from the canonical state.

  (validate [db] → ValidateResult)
    Checks all database invariants. Enforces:
      • All children exist in :nodes.
      • No duplicate children under a parent.
      • Each child has exactly one parent.
      • All parents are either roots or existing nodes.
      • No cycles in the parent graph.
      • No node is its own parent.
      • Derived indexes are fresh.

═════════════════════════════════════════════════════════════════════════════
 2. Core Operations (core.ops)
═════════════════════════════════════════════════════════════════════════════
The kernel has a closed instruction set of three pure operations.

┌─ create-node ────────────────────────────────────────────────────────────────┐
│                                                                             │
│  (create-node [db id type props] → Db)                                      │
│                                                                             │
│  • Semantics: Creates a new node shell in `:nodes`. It does not place it in │
│    the tree.                                                                │
│  • Guarantees: Idempotent. If a node with the given `id` already exists,    │
│    the operation is a no-op.                                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ update-node ────────────────────────────────────────────────────────────────┐
│                                                                             │
│  (update-node [db id props] → Db)                                           │
│                                                                             │
│  • Semantics: Merges new `props` into an existing node's properties.        │
│  • Algorithm: Uses a recursive deep-merge. Nested maps are merged, while   │
│    scalar values in `props` overwrite existing values.                      │
│  • Guarantees: If the node `id` does not exist, it is a no-op.              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ place ──────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  (place [db id under at] → Db)                                              │
│                                                                             │
│  • Semantics: Moves a node to a new parent at a specified position.         │
│  • Algorithm: A strict three-phase process:                                 │
│      1. REMOVE: The node is unconditionally removed from its current parent.│
│         This is a full scan of `:children-by-parent`.                       │
│      2. RESOLVE: The `:at` anchor is resolved to a concrete index within    │
│         the target parent's children list *after* the removal. This         │
│         prevents ambiguity if the anchor refers to the node being moved.    │
│      3. INSERT: The node is inserted into the target parent's children at   │
│         the resolved index.                                                 │
│                                                                             │
│  • Anchor Resolution: The `:at` anchor can be:                              │
│      • :first | :last          → index 0 or n                               │
│      • <integer>               → literal index (clamped)                    │
│      • {:before target-id}     → index of target-id                         │
│      • {:after target-id}      → index of target-id + 1                     │
│      If a relative anchor's target is not found, it resolves to the end.    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

═════════════════════════════════════════════════════════════════════════════
 3. Transaction Pipeline (core.interpret)
═════════════════════════════════════════════════════════════════════════════
All operations are processed through a 4-phase pipeline. The pipeline is
transactional: it fails on the first validation error, returning all issues found
for that operation.

                      ┌────────────────────────┐
                      │      Input `txs`       │
                      │   (vector of ops)      │
                      └───────────┬────────────┘
                                  │
                   ╔══════════════╧══════════════╗
                   ║      1. NORMALIZE          ║
                   ╚══════════════╤══════════════╝
                                  │
              (normalize-ops [db ops] → ops')
                                  │
      ┌───────────────────────────┼───────────────────────────┐
      │                           │                           │
remove-noop-places      merge-adjacent-updates         (other future...)
      │                           │                           │
      └───────────────────────────┴───────────────────────────┘
                                  │
                   ╔══════════════╧══════════════╗
                   ║      2. VALIDATE           ║
                   ╚══════════════╤══════════════╝
                                  │
           (validate-ops [db ops'] → [db' issues])
                                  │
                   SHORT-CIRCUITS ON FIRST ERROR
                                  │
                   ╔══════════════╧══════════════╗
                   ║      3. APPLY (IMPLICIT)   ║
                   ╚══════════════╤══════════════╝
                                  │
         (apply-op is called inside validate-ops)
                                  │
                   ╔══════════════╧══════════════╗
                   ║      4. DERIVE             ║
                   ╚══════════════╤══════════════╝
                                  │
             (derive-indexes [db'] → final-db)
                                  │
                                  ▼
                      ┌────────────────────────┐
                      │     InterpretResult    │
                      │ {:db, :issues, :trace} │
                      └────────────────────────┘

┌─ Threading in `validate-ops` ────────────────────────────────────────────────┐
│                                                                             │
│  Validation is a stateful reduction that threads the database state forward.│
│                                                                             │
│  (reduce (fn [[db issues] op]                                               │
│            (let [op-issues (validate-op db op)]                             │
│              (if (seq op-issues)                                            │
│                (reduced [db (into issues op-issues)]) ; SHORT CIRCUIT       │
│                [(apply-op db op) issues])))            ; THREAD FORWARD      │
│          [initial-db []]                                                    │
│          ops)                                                               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

API Surface:
  (interpret [db txs] → InterpretResult)
    Runs a transaction through the full pipeline.

  (derive-db [db] → Db)
    Public alias for `core.db/derive-indexes`.

  (validate [db] → ValidateResult)
    Public alias for `core.db/validate`.

═════════════════════════════════════════════════════════════════════════════
 4. Validation Semantics
═════════════════════════════════════════════════════════════════════════════
Validation occurs per-operation against the current state of the database.

Issue Structure: `{:issue :keyword, :op Op, :at Int, :hint "..."}`

┌─ Validation Rules by Operation ──────────────────────────────────────────────┐
│                                                                             │
│ ┌─ :create-node ───────────────────────────────────────────────────────────┐ │
│ │ • :duplicate-create: Node with the same ID already exists.                │ │
│ └───────────────────────────────────────────────────────────────────────────┘ │
│ ┌─ :place ─────────────────────────────────────────────────────────────────┐ │
│ │ • :node-not-found: The node being placed does not exist.                  │ │
│ │ • :parent-not-found: The target parent is not a root or existing node.    │ │
│ │ • :cycle-detected: Placing the node would create a cycle (e.g., moving a │ │
│ │   node under one of its own descendants).                                 │ │
│ │ • :anchor-not-sibling: A relative anchor (`:before`/`:after`) refers to a │ │
│ │   node that is not a child of the target parent.                          │ │
│ └───────────────────────────────────────────────────────────────────────────┘ │
│ ┌─ :update-node ───────────────────────────────────────────────────────────┐ │
│ │ • :node-not-found: The node being updated does not exist.                 │ │
│ └───────────────────────────────────────────────────────────────────────────┘ │
│ ┌─ All Ops ────────────────────────────────────────────────────────────────┐ │
│ │ • :invalid-schema: The operation map does not conform to the Malli schema.│ │
│ │ • :unknown-op: The `:op` key is not one of the three core operations.     │ │
│ └───────────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

• Cycle Detection: `would-create-cycle?` walks up the parent chain from the
  target parent using the `:parent-of` derived index. If it encounters the node
  being moved, a cycle is detected. O(height).

• No-op Detection: `is-noop-place?` checks if a `:place` op would result in no
  change. It simulates the remove→resolve→insert logic to see if the node's
  final index matches its starting index under the same parent.

═════════════════════════════════════════════════════════════════════════════
 5. Schema Contracts (core.schema - Malli)
═════════════════════════════════════════════════════════════════════════════
All data structures and operations are defined by Malli schemas.

┌─ Core Types ─────────────────────────────────────────────────────────────────┐
│                                                                             │
│  Id:     :string                                                            │
│  Parent: [:or Id :keyword]                                                  │
│  At:     [:or :int [:= :first] [:= :last] [:map [:before Id]] [:map [:after Id]]]│
│  Node:   [:map [:type :keyword] [:props :map]]                              │
│  Db:     [:map [:nodes ...] [:children-by-parent ...] ...]                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ Operation Schemas ──────────────────────────────────────────────────────────┐
│                                                                             │
│  Op-Create: [:map [:op [:= :create-node]] [:id Id] ...]                      │
│  Op-Place:  [:map [:op [:= :place]] [:id Id] [:under Parent] [:at At]]       │
│  Op-Update: [:map [:op [:= :update-node]] [:id Id] [:props :map]]            │
│  Op:        [:or Op-Create Op-Place Op-Update]                               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ Result Schemas ─────────────────────────────────────────────────────────────┐
│                                                                             │
│  Issue:           [:map [:issue :keyword] [:op Op] ...]                      │
│  InterpretResult: [:map [:db Db] [:issues [:vector Issue]] ...]              │
│  ValidateResult:  [:map [:ok? :boolean] [:errors [:vector :string]]]         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

API Surface:
  (valid-op? [op] → boolean)
  (valid-db? [db] → boolean)
  (explain-op [op] → explanation)
  (generate-op [] → Op)
  (generate-transaction [size] → [Op])

Transformers (`api-transformer`, `strict-transformer`) are available for robust
data decoding and encoding.

═════════════════════════════════════════════════════════════════════════════
 6. Structural Editing Layer (core.struct)
═════════════════════════════════════════════════════════════════════════════
This module is an example of an extension layer built on top of the kernel. It
compiles high-level "intents" into sequences of core operations.

┌─ Design Pattern: Intent Compilation ───────────────────────────────────────┐
│                                                                             │
│  1. Define high-level intents as data (e.g., `{:type :indent :id "b"}`).    │
│  2. Use a multimethod `compile-intent` to dispatch on `:type`.              │
│  3. Each method reads from the DB's derived indexes to make decisions.      │
│  4. Each method emits a vector of zero or more core operations.             │
│  5. An empty vector is returned for impossible actions (e.g., indenting the │
│     first child), ensuring safety.                                          │
│                                                                             │
│  Threading Pattern:                                                         │
│    intents ->> (mapcat compile-intent db)                                   │
│            ->> vec                                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ Example Intents ────────────────────────────────────────────────────────────┐
│                                                                             │
│  :delete {:id "a"}                                                          │
│    • Reads: Nothing.                                                        │
│    • Emits: `[{:op :place :id "a" :under :trash :at :last}]`                 │
│    • Note: Delete is non-destructive; it moves nodes to a `:trash` root.    │
│                                                                             │
│  :indent {:id "b"}                                                          │
│    • Reads: `:prev-id-of` to find the previous sibling.                     │
│    • Emits: `[{:op :place :id "b" :under <prev-sibling-id> :at :last}]`      │
│                                                                             │
│  :outdent {:id "b"}                                                         │
│    • Reads: `:parent-of` to find parent, then grandparent.                  │
│    • Emits: `[{:op :place :id "b" :under <grandparent-id> :at {:after <p>}}]`│
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

═════════════════════════════════════════════════════════════════════════════
 7. Usage Patterns
═════════════════════════════════════════════════════════════════════════════

┌─ 1. Basic Workflow ──────────────────────────────────────────────────────────┐
│                                                                             │
│  (let [db0 (D/empty-db)                                                     │
│        ops [{:op :create-node :id "a"} {:op :place :id "a" ...}]             │
│                                                                             │
│        ;; Run the transaction through the pipeline                           │
│        {:keys [db issues]} (I/interpret db0 ops)]                           │
│                                                                             │
│    (if (seq issues)                                                         │
│      (println "Error:" issues)                                              │
│      (println "Success! New children:" (get-in db [:children-by-parent ...]))))│
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ 2. Extension Pattern (Structural Editing) ────────────────────────────────┐
│                                                                             │
│  (let [db (setup-some-db)                                                   │
│        intents [{:type :indent :id "b"}                                     │
│                 {:type :delete :id "a"}]                                    │
│                                                                             │
│        ;; 1. Compile high-level intents into low-level core ops              │
│        ops (S/compile-intents db intents)                                   │
│                                                                             │
│        ;; 2. Interpret the generated ops                                     │
│        {:keys [db issues]} (I/interpret db ops)]                            │
│                                                                             │
│    (if (seq issues)                                                         │
│      (println "Error:" issues)                                              │
│      (println "Success!")))                                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

═════════════════════════════════════════════════════════════════════════════
 8. Key Invariants & Guarantees
═════════════════════════════════════════════════════════════════════════════
The system is designed to maintain these guarantees at all times.

• ✓ Operations are pure functions.
• ✓ The database is treated as immutable.
