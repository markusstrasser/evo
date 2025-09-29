Looking at Clojure projects specifically for your edit algebra/tree transformation system:

**Tree/Data transformation masters:**

1. **Specter** (already mentioned but worth studying deeply) - The `defnav` macro and how it compiles navigators at compile-time. Your Intent→Core compiler should work similarly. The `collector` pattern for gathering context during traversal is exactly what you need for tree operations.

2. **rewrite-clj** - AST manipulation with zipper-based editing and whitespace preservation. Their "splice" and "subedit" operations are the exact edit algebra you're building. Study how they handle node insertion with automatic spacing.

3. **Meander** - The `memory variables` and `scan` patterns show how to match across tree levels. Their "substitution" vs "rewriting" distinction maps to your read vs write ops.

**Reactive/incremental elegance:**

4. **Missionary** - Leo Noel's reactive streams. The `sp` (sequential process) macro is basically your effect system. Tiny core, no magic subscriptions, explicit lifecycle. This is Electric's foundation.

5. **Javelin** (Hoplon) - Cell-based FRP with formula cells. Their deref-based dependency tracking is simpler than React yet more powerful. The "lens" concept for bidirectional data flow.

6. **re-frame** - Not for the framework but for `reg-sub` layering. Their "Layer 2/3" subscription pattern is your derived signals. The interceptor chain is how your Intent compiler should work.

**Event sourcing/Command patterns:**

7. **Datahike** - Datascript fork with proper storage. Their transaction log format and "db-with" for hypothetical states is your snapshot/replay system. The index management code is gold.

8. **Onyx** - Distributed computation platform. Their "catalog/workflow/flow-conditions" separation is exactly your Intent/Core/ViewDiff split. Study their job planning DSL.

**Edit operation primitives:**

9. **Editscript** - Optimal tree diff algorithm. Their A* search for minimal edit distance between nested structures. This is what your LLM should use to generate repair patches.

10. **clojure.zip** - Often overlooked but the zipper editing functions (`zip/edit`, `zip/insert-child`) are the canonical way to do functional tree surgery. Your Core Algebra should compile to zipper ops.

**Bonus study**: **Fulcro**'s normalized database + pessimistic mutations. Their `swap!->` macro and how they merge component queries into a single tree is basically your "progressively lower" pattern. The `tempid` system for optimistic creation with later reconciliation.

The Clojure pattern: these libraries expose data structures as values, operations as pure functions, and push effects to the edge. They're all "algebras" in disguise - Specter navigators form a monoid, re-frame subscriptions form a DAG, zippers form a comonad. That's the real Clojure way.


Looking at what makes Logseq/Obsidian/Figma tick - you need Clojure patterns for structured documents, constraints, extensibility, and direct manipulation:

**Document/Knowledge graph engines:**

1. **Logseq's own core** (DataScript-based) - Their outliner operations (`outliner.core`) and block reference system. The "block UUID as content-addressed storage" pattern. Study their macro expansion system - it's basically your Intent IR.

2. **Athens Research** (defunct but code exists) - Bidirectional links + block references in DataScript. Their "presence" system for multiplayer and the "atomic graph ops" are exactly your edit algebra. Better architecture than Logseq IMO.

3. **Roam-like systems in Clojure** (multiple attempts) - Common pattern: normalized block storage + derived view indices. The "daily notes as append-only log" is your tx-log pattern.

**Constraint/Layout systems:**

4. **Membrane** - Immediate mode GUI with layout as data. Their "bounds" calculation and "spatial index" for hit testing. No hidden state - perfect for LLM manipulation.

5. **HumbleUI** (Clojure on Skija) - Jetpack Compose-style but in Clojure. The "modifier" chain pattern for styling without inheritance. Layout nodes are just functions.

6. **Cassowary.clj** - Constraint solver (dead but instructive). Auto-layout through declarative constraints - this is how Figma's smart layout works internally.

**Extensibility/Plugin architectures:**

7. **Malli** - Schema library with pluggable transformers. Their "schema as data" with visitors/walkers/transformers is exactly how your Intent compiler should work. Function schemas = capability model for plugins.

8. **SCI** (Small Clojure Interpreter) - Sandboxed Clojure evaluation. This is how you'd implement safe plugin execution. Babashka uses this for scripting.

9. **Portal** - Extensible data inspector. Their "viewer" system where you register handlers for data types. Every domain object gets custom visualization/interaction.

**Command/Action systems:**

10. **Vlojure** (Vi in Clojure) - Modal editing with composable commands. Their command grammar and "motion + verb" algebra. This is how Figma's keyboard shortcuts work.

11. **Neanderthal/Expresso** - Symbolic math with rewrite rules. Your Intent→Core compiler is basically computer algebra - study their term rewriting strategies.

12. **Datalevin** - DataScript with storage. Their "search layer" on top of raw datoms. Important: how they index for both graph traversal AND full-text search.

**Direct manipulation patterns:**

13. **Quil/Processing** sketches - Immediate mode drawing with retained mode optimizations. The "draw loop as pure function of state" pattern.

14. **Clerk** - Notebook computing done right. Their "analyzer" that tracks dependencies between code blocks is your derived signals. The "viewer" system for custom renderers per type.

**The killer insight**:

These all share a pattern - **they separate the "algebra of operations" from the "interpretation context"**. Logseq has block-ops vs renderers. Membrane has spatial-ops vs platform bindings. Malli has schema-ops vs validators/generators/transformers.

Your system should expose:
- **Domain algebra** (blocks, cards, nodes, shapes)
- **Spatial algebra** (constraints, layout, bounds)
- **Temporal algebra** (undo, branching, versioning)
- **Reference algebra** (links, embeds, transclusions)

Then "making Obsidian" is just choosing which algebras to enable and writing 50 lines of domain rules. The LLM can mix and match these algebras to synthesize new UIs.

**Study #1 most carefully**: Athens Research's `athens.common-events` namespace. It's a masterclass in turning user intents into atomic graph operations. That's your whole system in 500 lines.