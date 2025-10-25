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

Give the LLM "the absolute right tools" by freezing a tiny set of primitives and
nothing else:
• Model: persistent document (tree+refs), session (selection/undo/ephemeral
overlays).
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

I want to have the system port architectures and improve upon them (like threejs
port or threlte8 or d3js or create interface tooling by themselves -- taking
other
repos fragments as inspiration).

For this I need the system to have a reinforcement loop of discussing
architectural proposals among itself ... building the dev tooling (evals, repl,
code-complexity-metrics, summarization (for LLM
piping) etc) AND then the actual packages (like kernel ... which took me weeks
to iterate on manually). I, the Human, would write requirements,design, taste,
ideas and use cases (or domain applications)
somewhere in this repo ... I just have trouble systematically setting up the
full loop.

## DEV

when you're using coding agents you're in two modes

1. building context
2. reviewing changes

a chat interface achieves both in a very clumsy way

### META SYSTEM

I see it now. You're building a self-improving meta-development system where AI
agents:

1. Learn patterns from best-of repos
2. Propose architectures
3. Evaluate via tournament
4. Implement winners
5. Measure outcomes
6. Feed learnings back

🔄 The Loop (Simplified for Solo Dev)

┌─ 1. Mine Patterns ──────────────────────┐
│ scripts/mine-patterns threejs │
│ → threejs-patterns.edn │
└──────────────┬──────────────────────────┘
↓
┌─ 2. Draft Spec (Manual+LLM) ────────────┐
│ Use patterns → draft spec.edn │
│ Update loop-state.edn :stage │
└──────────────┬──────────────────────────┘
↓
┌─ 3. Evaluate ────────────────────────────┐
│ scripts/v3-run {spec-id} │
│ → evaluations/{ts}.edn │
└──────────────┬──────────────────────────┘
↓
┌─ 4. Scaffold & Implement ───────────────┐
│ scripts/scaffold {spec} ││
└──────────────┬──────────────────────────┘
↓
┌─ 5. Capture Outcome ─────────────────────┐
│ scripts/postmortem {branch} │
│ → implementations/{spec}.edn │
│ → loop-state updates with lessons │
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

## GPT5-ideas: Possible future directions (brainstorm ... not goals right now)

Your instinct is right: “agent-legible” isn’t a breakthrough; it’s table stakes.
The breakthrough needs to be a crisp capability others can’t trivially bolt on.
Here are four sharper bets that sit on your kernel but change the game; each has
a 1–2 week acid-test and a kill metric.

1) Preference Compiler (dotfiles → UI)

Problem: Tools don’t learn users; they accrete toggles.
Thesis: A tiny DSL that compiles fuzzy traces (undo, dwell, reroute) into
concrete UI rewrites (bindings, defaults, affordances) via Bradley–Terry style
pairwise prefs + PU-learning.
Why incumbents can’t: PM/Lexical/Yjs don’t have a policy layer or a closed IR.
Demo: Log trace.jsonl → learn policy.edn → auto-patch UI (e.g., auto-promote
your 3 most used actions to first-class controls).
Kill metric: ≥30% reduction in steps for 3 tasks without user hand-tuning.

2) Rewrite-Driven UI Synthesis (E-graphs for UI)

Problem: “Growable UI” is hand-coded variations.
Thesis: Treat UI edits as algebraic rewrites; use an e-graph (à la Willsey et
al.) over your IR to search layouts/flows under constraints (Cassowary-lite).
Why incumbents can’t: They lack an IR that’s closed and queryable.
Demo: Given a form + logs, synthesize a keyboard-only variant that preserves
semantics (spec tests).
Kill metric: Find a strictly better variant (fewer keypresses) on ≥2 tasks
within 60s search.

3) Universal Selection Bus (LSP-for-UI)

Problem: Every app has incompatible selection/intent semantics.
Thesis: Standardize three ops + anchors as a local protocol (LSP analogy).
Adapters for 2 apps (say, Notes + Obsidian) prove cross-app macros, replay, and
agent control.
Why incumbents can’t: Vendor lock-in; they don’t expose intent semantics.
Demo: One keystroke sequence that moves a block across apps via your bus.
Kill metric: Cross-app macro authoring time < 10 min; success rate > 90%.

4) Replayable UI Dataset (training set, not a framework)

Problem: Agents overfit prompts, not interfaces.
Thesis: Ship a dataset + evaluator: intents↔ops↔derive traces with ground-truth
task outcomes. This becomes the “MNIST of agent UIs.” Kernel = trace generator.
Why incumbents can’t: Their internals aren’t trace-clean.
Demo: Release 50 tasks × 3 variants each + scorer; show Claude-Code-style agent
improves with finetuning on your traces.
Kill metric: ≥15% success lift on unseen UI variants.

⸻

If none of these excite you, pivot hard: your kernel is already good enough as
infrastructure. Don’t polish it. Pick one bet, set a 7-day bake-off with a
brutal exit rule. Canon you’re building on: Smalltalk/Morphic (growable
objects), TEA/Elm (intent→update), MLIR (lowering), LSP (protocol not product),
e-graphs (equivalence search), Cassowary (constraints), PU-learning (Elkan &
Noto). The win isn’t prettier ops—it’s a measurable adaptation loop the rest
can’t fake.

