# TBD


## MOTTO
Build → Learn → Extract → Generalize
**Not: Theorize → Propose → Analyze → Repeat**

## Assume
* AI-assisted development.
* Assume it's mostly for LLMs to patch in events, features and change uis on the
  fly generatively
* The indirection was done so that every step is data driven and can be tested
  and inspected separately... maybe it's not needed for this?
* There should be plugins later on ... it's an editor but also later a chatbot
  and genui thing where the LLM REPL-s in commands and forms

> You’re not building a UI framework; you’re carving a UI IR + interpreter
> that’s LLM-native and human (cognitive complexity) friendly.

Give the LLM “the absolute right tools” by freezing a tiny set of primitives and
nothing else:
• Model: persistent document (tree+refs), workspace (user-persistent view
prefs), session (selection/undo).
• Intents (IR): total, idempotent EDN ops, stable IDs, deterministic ordering.
• Reactivity: signal, derived, effect—no other FRP. Effects are
capability-scoped and named (debuggable).
• Layout: declarative boxes + a minimal constraint set (stack/grid +
“hug/fill/align”); no custom render logic in the core.
• Interpreter: single entry, stepwise derivation, cycle checks, law tests,
explainable failures (small counterexamples).
• Introspection: describe-ops, schema, invariants, example-generators; errors
return patches the LLM can try.
• Adapters: thin shells (React/Svelte/Godot/TUI) that only map DOM/input →
intents and signals → view; the core never sees events.

The core remains framework-agnostic. If you can swap the renderer and your
intents stay identical—and the model can fix itself using interpreter errors—you
didn’t build Yet Another UI Framework; you built the LLVM of UI.

The LLVM metaphor is useful, but thinking of your system through the lens of
MLIR (Multi-Level Intermediate Representation) is more powerful. MLIR isn't a
single IR; it's an infrastructure for creating and transforming multiple,
domain-specific IRs, "progressively lowering" from high-level intent to
low-level implementation.

This reveals that your system isn't a single interpreter but a pipeline of
transformations between different levels of semantic abstraction.

The UI as MLIR

1. The Intent IR (High-Level)
   This is the most abstract representation of what the user or LLM wants to do.
   It describes semantic intent, not implementation.

Example: [:merge-blocks {:from "id-1" :to "id-2"}]
or [:style-selection {:style :heading1}].

2. The Core Algebra IR (Mid-Level)
   This is the EDN DSL we've been designing—the pure, verifiable, host-agnostic
   set of atomic data transformations. This is the canonical language of your "
   World State."

This IR is the output of a "compiler pass" that lowers the Intent IR into a
concrete transaction.

3. The View Diff IR (Low-Level)
   This is the lowest-level representation, specific to a rendering shell. It's
   the incseq diff format we saw in Electricv3 (clojure). It describes the
   minimal set of changes needed to update a specific view (DOM, TUI, etc.).

Example: {:grow 0, :shrink 1, :permutation {}, :change {17: "..."}}

This IR is the output of the Adapter/Renderer, which acts as a final lowering
pass from a state change to a view patch.

What this metaphor reveals:

Your system becomes a pipeline: Intent IR -> Core Algebra IR -> View Diff IR.
This is a profoundly robust model. To add a complex new feature (like
multi-block refactoring), you simply define a new high-level "Intent" and write
a pure function that lowers it to your stable "Core Algebra." The core
interpreter doesn't have to change. This cleanly separates semantic goals,
logical data transformations, and rendering specifics, making the entire system
more modular and extensible.

### Kernel OS

The MLIR framing (Intent → Core Algebra → View Diff): “Core Algebra” compiles
cleanly from higher intents without touching the kernel.

## Maybe at some point, IDK.

### Protocols vs Multimethods

Protocols are best when you have a fixed set of operations and want to support
many different data types (backends like InMem, DataScript, Postgres). It
abstracts over the db argument.

Multimethods are best when you have a fixed set of data types (just your InMem
db) and want to support an open-ended set of operations. It abstracts over the
op argument.

Protocols AT Edges NOT in Kernel functions (and also not right now)
Keep the kernel dumb and sovereign. It should be an algebra over a canonical
value (your {:nodes … :children-by-parent …}), not over an interface. Having
kernel ops “know” :children-by-parent is a feature: it makes them total,
deterministic, property-testable, and trivially REPLable.

IFF protocol then put protocols at the edges as adapters. Flow: external store →
normalize→ canonical → interpret (re-derive) → denormalize → external store*. If
you fear key lock-in, add tiny accessors or a normalize/denormalize pair so :
children-by-parent is a private contract of the kernel, not the world. Net:
better like this—kernel owns the shape; adapters translate.


## UNSURE, loose ideas
I want to have the system port architectures and improve upon them (like threejs port or threlte8 or d3js or create interface tooling by themselves -- taking other
repos fragments as inspiration).

For this I need the system to have a reinforcement loop of discussing architectural proposals among itself ... building the dev tooling (evals, repl, code-complexity-metrics, summarization (for LLM
piping) etc) AND then the actual packages (like kernel ... which took me weeks to iterate on manually). I, the Human, would write requirements,design, taste, ideas and use cases (or domain applications)
somewhere in this repo ... I just have trouble systematically setting up the full loop.

## DEV

when you're using coding agents you're in two modes

1. building context
2. reviewing changes

a chat interface achieves both in a very clumsy way
### META SYSTEM
I see it now. You're building a self-improving meta-development system where AI agents:
1. Learn patterns from best-of repos
2. Propose architectures
3. Evaluate via tournament
4. Implement winners
5. Measure outcomes
6. Feed learnings back

🔄 The Loop (Simplified for Solo Dev)

┌─ 1. Mine Patterns ──────────────────────┐
│   scripts/mine-patterns threejs         │
│   → threejs-patterns.edn                │
└──────────────┬──────────────────────────┘
↓
┌─ 2. Draft Spec (Manual+LLM) ────────────┐
│   Use patterns → draft spec.edn         │
│   Update loop-state.edn :stage          │
└──────────────┬──────────────────────────┘
↓
┌─ 3. Evaluate ────────────────────────────┐
│   scripts/v3-run {spec-id}               │
│   → evaluations/{ts}.edn                 │
└──────────────┬──────────────────────────┘
↓
┌─ 4. Scaffold & Implement ───────────────┐
│   scripts/scaffold {spec}                │
│   Fill TODOs in src/plugins/             │
└──────────────┬──────────────────────────┘
↓
┌─ 5. Capture Outcome ─────────────────────┐
│   scripts/postmortem {branch}            │
│   → implementations/{spec}.edn           │
│   → loop-state updates with lessons      │
└──────────────┬──────────────────────────┘
↓
(Repeat with refined goals/patterns)

### DOMAIN application: Hypergraph Plugin for Knowledge Management

**Decision**: Write comprehensive tests for functionality that doesn't exist yet
to drive future architecture.

**Hypergraph Test Categories Added**:

1. **Cross-references**: Arbitrary entity relationships beyond parent-child (
   `:validates-with`, `:submits-to`, `:contains`)
2. **Referential integrity**: What happens when referenced entities are
   deleted (exposes dangling references)
3. **Bidirectional relationships**: Graph traversal patterns and consistency
   checks
4. **Disconnected subgraphs**: Entities outside tree hierarchy (floating
   dialogs, background services)
5. **Multiple relationship types**: Semantic relationships for UI component
   interactions


* REACTIVE UI has insane OVERHEAD TO DEVELOP
    * I am building a pure data transformation library. the core has no UI, no
      side effects, and no async operations.

**they separate the "algebra of operations" from the "interpretation context"**.
Logseq has block-ops vs renderers. Membrane has spatial-ops vs platform
bindings. Malli has schema-ops vs validators/generators/transformers.

Your system should expose:

- **Domain algebra** (blocks, cards, nodes, shapes) (first domain test case)
- **Spatial algebra** (constraints, layout, bounds) (very much later)
- **Temporal algebra** (undo, branching, versioning) (later... maybe undo/redo
  as proof of concept)
- **Reference algebra** (links, embeds, transclusions) (links already
  implemented ... rest can wa)

