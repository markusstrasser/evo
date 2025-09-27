# Evolver

A ClojureScript tree editor with a command-driven architecture for AI-assisted development.


Your mistake was thinking you were building a UI framework. You're building an interpreter for a tree-manipulation DSL, and the LLM is the programmer.

* The indirection was done so that every step is data driven and can be tested and inspected separately... maybe it's not needed for this?
* There should be plugins later on ... it's an editor but also later a chatbot and genui thing where the LLM REPL-s in commands and forms

Is there anything like this that's done it better? Any utils that could remove LoC and delete custom stuff for general patterns (like CQRS)?
Also apply-transaction(state, transaction-log) ... isn't that the job of the kernel then? otheriwse the client has to implement the tree logic? or am i misunderstanding?
Also is this reinventing the wheel? Has svelte5 or whatever other community done this better?

Assume it's mostly for LLMs to patch in events and change uis on the fly generatively

Your "core algebra" is a set of rewrite rules, and the "laws" you would test with property-based testing are proofs of their desirable properties.

## WHy not datascript?
* No ordered lists! Buggy on some things.
* You should only reach for Datascript when your query logic becomes more complex than your mutation logic.
**API Integration**:
- `insert!` creates entities with mandatory position specification
- `move!` uses same position resolution for atomic subtree relocation
- `update!` handles only non-structural attributes (prevents accidental position corruption)

**Design Rationale**: Creating tree entities without position leaves the tree in an incoherent state. Rather than separate create/position operations, atomic create-with-position is a legitimate compound operation that maintains tree invariants.

## DEV
* REACTIVE UI has insane OVERHEAD TO DEVELOP
  * I am building a pure data transformation library. the core has no UI, no side effects, and no async operations.

### Hypergraph Test-Driven Development
**Decision**: Write comprehensive tests for functionality that doesn't exist yet to drive future architecture.

**Hypergraph Test Categories Added**:
1. **Cross-references**: Arbitrary entity relationships beyond parent-child (`:validates-with`, `:submits-to`, `:contains`)
2. **Referential integrity**: What happens when referenced entities are deleted (exposes dangling references)
3. **Bidirectional relationships**: Graph traversal patterns and consistency checks
4. **Disconnected subgraphs**: Entities outside tree hierarchy (floating dialogs, background services)
5. **Multiple relationship types**: Semantic relationships for UI component interactions

You’re not building a UI framework; you’re carving a UI IR + interpreter that’s LLM-native. That is different. React/Svelte/Elm optimize for human authors (ergonomics, escape hatches); you need determinism, reversibility, locality, and introspection so an LLM can synthesize, diff, and repair without guessing hidden runtime state. FRP is useful, but only as a thin calculus the interpreter can reason about—signals/derivations/effects with explicit lifetimes and zero “magical” subscriptions. The novelty isn’t widgets; it’s a small algebra of intents + a reactivity kernel + self-describing errors that lets models compose interfaces like they compose SQL.

Give the LLM “the absolute right tools” by freezing a tiny set of primitives and nothing else:
•	Model: persistent document (tree+refs), workspace (user-persistent view prefs), session (selection/undo).
•	Intents (IR): total, idempotent EDN ops (ins/mv/reorder/patch/del/ref, tx), stable IDs, deterministic ordering.
•	Reactivity: signal, derived, effect—no other FRP. Effects are capability-scoped and named (debuggable).
•	Layout: declarative boxes + a minimal constraint set (stack/grid + “hug/fill/align”); no custom render logic in the core.
•	Interpreter: single entry, stepwise derivation, cycle checks, law tests (move/reorder/cascade/promote), explainable failures (small counterexamples).
•	Introspection: describe-ops, schema, invariants, example-generators; errors return patches the LLM can try.
•	Adapters: thin shells (React/Svelte/Godot/TUI) that only map DOM/input → intents and signals → view; the core never sees events.

Verdict on “use re-frame/datascript?”: treat them as adapters when you feel pain (complex queries → Datascript; shell orchestration/async → re-frame). The core remains framework-agnostic. If you can swap the renderer and your intents stay identical—and the model can fix itself using interpreter errors—you didn’t build Yet Another UI Framework; you built the LLVM of UI.


The LLVM metaphor is useful, but thinking of your system through the lens of MLIR (Multi-Level Intermediate Representation) is more powerful. MLIR isn't a single IR; it's an infrastructure for creating and transforming multiple, domain-specific IRs, "progressively lowering" from high-level intent to low-level implementation.

This reveals that your system isn't a single interpreter but a pipeline of transformations between different levels of semantic abstraction.


The UI as MLIR
The LLVM metaphor is useful, but thinking of your system through the lens of MLIR (Multi-Level Intermediate Representation) is more powerful. MLIR isn't a single IR; it's an infrastructure for creating and transforming multiple, domain-specific IRs, "progressively lowering" from high-level intent to low-level implementation.

This reveals that your system isn't a single interpreter but a pipeline of transformations between different levels of semantic abstraction.

1. The Intent IR (High-Level)
   This is the most abstract representation of what the user or LLM wants to do. It describes semantic intent, not implementation.

Example: [:merge-blocks {:from "id-1" :to "id-2"}] or [:style-selection {:style :heading1}].

2. The Core Algebra IR (Mid-Level)
   This is the EDN DSL we've been designing—the pure, verifiable, host-agnostic set of atomic data transformations. This is the canonical language of your "World State."

Example: [:tx [[:patch "id-2" {:text "..."}] [:delete "id-1"]]]

This IR is the output of a "compiler pass" that lowers the Intent IR into a concrete transaction.

3. The View Diff IR (Low-Level)
   This is the lowest-level representation, specific to a rendering shell. It's the incseq diff format we saw in Electric. It describes the minimal set of changes needed to update a specific view (DOM, TUI, etc.).

Example: {:grow 0, :shrink 1, :permutation {}, :change {17: "..."}}

This IR is the output of the Adapter/Renderer, which acts as a final lowering pass from a state change to a view patch.

What this metaphor reveals:

Your system becomes a pipeline: Intent IR -> Core Algebra IR -> View Diff IR. This is a profoundly robust model. To add a complex new feature (like multi-block refactoring), you simply define a new high-level "Intent" and write a pure function that lowers it to your stable "Core Algebra." The core interpreter doesn't have to change. This cleanly separates semantic goals, logical data transformations, and rendering specifics, making the entire system more modular and extensible.