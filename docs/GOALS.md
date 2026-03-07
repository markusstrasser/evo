# Goals

> Human-owned. Agents may propose changes but must not modify without explicit approval.
> Last revised: 2026-03-07

## Mission

Build the best-specified outliner kernel, extract it as a standalone library, and publish an interactive essay showcasing it. The outliner UI is a proving ground, not the product.

## Context: Why not PKM?

The PKM thesis — that structured note-taking software makes you think better — has largely failed. The evidence:

- **Casey Newton**, "Why note-taking apps don't make us smarter" (Platformer, Aug 2023): Roam's promise to improve thinking by building a knowledge base "fizzled completely." Software is up against a stronger foe — infinite internet distractions. "It is probably a mistake, in the end, to ask software to improve our thinking."
- **Andy Matuschak**: Note-taking apps emphasize displaying and manipulating notes, but never making sense *between* them. "The goal is not to take notes — the goal is to think effectively."
- **Justin Murphy**, "Personal Knowledge Management is Bullshit" (otherlife.co): Knowledge graphs are "bullshit signaling devices" — combinatorial explosion after two weeks. Real writing requires "brute linear willfulness" that cuts *through* the graph, not more graph.
- **Redeeming Productivity**, "The Failed Promise of Connected Note-Taking Apps" (2023): "There is no such thing as effortless output." Thinking "stubbornly resists automation."
- **Gwern** takes a narrower, defensible position: spaced repetition works for *memorization* (well-evidenced), but that's retention, not thinking. His wiki is a publishing system, not a PKM graph.
- **Market signal**: Roam Research growth stalled at ~500K users by 2025, significant churn to Obsidian/Logseq. The category is fragmenting, not consolidating.

The deeper issue: outliners trap you in a single representation. You switch apps because you need to switch *how you think*, and the tool can't follow. AI is more expressive — you speak, write prose, give prompts — and the AI synthesizes across all of it without imposing structure prematurely. Projects like `selve` and `research` are where knowledge work actually happens now.

Evo's value is not as a PKM tool. It's as:
1. A battle-tested kernel with a clean spec (the artifact)
2. A substrate for AI-generated domain-specific UIs (the future bet)

## Strategy

### Phase 1: Kernel extraction (near-term)
- Extract `src/kernel/` as a standalone, publishable library
- Clean API surface: three-op primitives, transaction pipeline, derived indexes
- No UI dependencies, no shell, no Replicant
- Property tests and spec travel with the kernel

### Phase 2: Interactive essay (near-term)
- Publish the outliner as a showcase of "the perfect spec for a text-line tree"
- Interactive: readers can see the kernel, try operations, inspect state
- The essay is the product — not the app

### Phase 3: Self-improving loop (exploration, highest interest)
- AI observes interaction logs, proposes UI changes or new domain-specific UIs
- Requires actually using the app to generate logs (bootstrap problem)
- OR: feed it synthetic tasks / recorded sessions
- This is the most interesting direction but needs the bootstrap solved

## Deferred scope (explicitly not now)

- **Universal adapter shells** (React/Svelte/Godot/TUI) — domain shapes representation, universality is a mirage
- **LLVM-of-UI / MLIR thesis** — interesting metaphor, not a practical goal
- **Logseq feature parity** — feature-complete enough (slash commands, sidebar, drag-drop all LOW)
- **Daily PKM use** — not happening; knowledge work happens in AI tools, Google Docs, voice memos
- **Multimodal/visual experience capture** — interesting but better served by dedicated apps (Sublime, etc.)

## Belief tracker (separate project idea)

A flat list of strong beliefs / predictions. Causal graph optional. AI checks against incoming research weekly. Dashboard shows which beliefs got weaker. Keeps you intellectually honest. Not an Evo feature — probably its own small project.

## Success metrics

| Metric | Target | Horizon |
|--------|--------|---------|
| Kernel extracted as standalone lib | Published, usable without UI code | 3 months |
| Interactive essay draft | Readable, interactive, showcases kernel | 6 months |
| Architecture discussion trail | Key decisions preserved in git, cruft removed | Ongoing |

## Resource constraints

- Evo is on the back burner relative to other projects (selve, research, etc.)
- Bursts of focused work, not steady allocation
- AI agents do most of the implementation; human provides taste, direction, requirements

## Architecture docs & discussion trail

Architecture docs should be pruned: remove cruft, keep decisions that show the evolution of thinking. These are interesting *for the kernel* — how it evolved, what was tried, what was discarded. Git history preserves everything; the working tree should be clean.

Look at `meta` project patterns for better taxonomy of architecture discussions.

## Exit conditions

- If the kernel doesn't find external users or the essay doesn't generate interest, Evo becomes a archived reference project
- If AI-generated UIs become the focus, Evo might pivot to being purely the substrate (kernel + AI loop, no manual UI)
