# Goals

> Human-owned. Agents may propose changes but must not modify without explicit approval.
> Last revised: 2026-03-08

## Mission

Build the best-specified outliner kernel, extract it as a standalone library. The outliner UI is a proving ground, not the product.

## Generative Principle

> Minimize the spec surface while maximizing kernel power — legible to both humans and LLMs.

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

### Phase 1: Kernel extraction (triggered by need)
- Extract `src/kernel/` as a standalone, publishable library
- Clean API surface: three-op primitives, transaction pipeline, derived indexes
- No UI dependencies, no shell, no Replicant
- Property tests and spec travel with the kernel
- **Trigger**: when another project needs the kernel, not on a calendar

### Phase 2: Kernel improvement + augmented features
- If the kernel can be made smaller or more correct, do it
- Interesting directions: multimodal support, augmented features beyond basic outlining
- Interactive essay showcasing the kernel (human-driven, not agent work)

### Phase 3: Self-improving loop (exploration, highest interest)
- AI observes interaction logs, proposes UI changes or new domain-specific UIs
- Bootstrap problem: not currently generating interaction data
- May use the app more in the future; also exploring better ways to externalize preference data

## Deferred scope (explicitly not now)

- **Universal adapter shells** (React/Svelte/Godot/TUI) — domain shapes representation, universality is a mirage
- **LLVM-of-UI / MLIR thesis** — interesting metaphor, not a practical goal
- **New Logseq feature parity** — feature-complete enough (slash commands, sidebar, drag-drop all LOW)
- **Daily PKM use** — not happening; knowledge work happens in AI tools, Google Docs, voice memos

## Docs strategy

- **Specs are invariant**: `STRUCTURAL_EDITING.md`, `LOGSEQ_SPEC.md`, logseq behavior triads — these are facts, keep them
- **Logseq parity**: condense to a table/list referencing feature IDs in the authoritative spec
- **Executed plans**: delete from working tree (git preserves history)
- **Architecture docs**: keep only what's current and true — saves future agents from wasted exploration
- **Discussion trail**: kernel evolution decisions are interesting; preserve in git history, not as cruft in working tree

## Belief tracker (separate project idea)

A flat list of strong beliefs / predictions. Causal graph optional. AI checks against incoming research weekly. Dashboard shows which beliefs got weaker. Keeps you intellectually honest. Not an Evo feature — probably its own small project.

## Success metrics

| Metric | Target | Horizon |
|--------|--------|---------|
| Kernel extractable | Can be used without UI code when needed | When triggered |
| Kernel purity | Zero imports from shell/components/keymap | Ongoing |
| Docs are facts not plans | No executed plans in working tree | Ongoing |

## Resource constraints

- Evo is on the back burner relative to other projects (selve, research, etc.)
- Bursts of focused work, not steady allocation
- AI agents do most of the implementation; human provides taste, direction, requirements

## Exit conditions

- If the kernel doesn't find use in another project, Evo becomes an archived reference project
- If AI-generated UIs become the focus, Evo might pivot to being purely the substrate (kernel + AI loop, no manual UI)
