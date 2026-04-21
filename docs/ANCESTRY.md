# Ancestry

A narrative history of the ideas and code that fed into evo. Git log
can't tell this story — evo's history starts at `02a37703 init` on
2025-09-22, but the editor it is today was already being thought
through in an earlier repo for the preceding six months.

## TL;DR

Evo has one known direct ancestor: **`~/Projects/browsing/`**
(`2025-03-12` → `2026-02-26`, 147 commits). Browsing started as a
Clerk/SCI notebook playground, pivoted in August 2025 to a normalized
op-kernel on Replicant, and that pivot is the seed evo germinated
from. No second ancestor was found; if one existed it was either
private or lived only in scratch files. The core three-op pattern,
normalized graph DB, and intent-dispatch design all exist in
proto-form in browsing's `src/kernel.cljc` (131 lines) and
crystallized into evo's `src/kernel/` as the codebase expanded.

## Lineage

```
browsing                                              evo
──────────                                            ──────────
2025-03-12  Initial Setup
            │
            │  Clerk + SCI + JSXGraph era
            │  (detour — math/notebooks/portfolio)
            │
2025-08-03  "working kernel: apply-ops"
2025-08-04  "UNIFIED OPERATIONS ... -10% codebase"    ← crystallization
            │       (14823ac)
            │
            │  Replicant + hiccup + normalized DB
            │
            ├──────────────────────────────────┐
            │                                  │
            ▼                                  ▼
2026-02-26  "Add scratch test for       2025-09-22  init (02a37703)
             kernel CRUD operations"    │
            (final commit)              │  kernel / shell / plugins
                                        │  scripts, FRs, E2E
                                        │  extraction mode
                                        │
                                        ▼
                                   2026-04-20  (current)
```

Note the overlap: browsing kept getting commits for five months after
evo started. It wasn't a clean handoff — browsing stayed alive as
a sketch surface while evo took on the real weight.

## The ancestor: `~/Projects/browsing/`

### What it was

A **data-driven generative UI orchestrator**: an LLM would modify
a live interface by emitting semantic operations against a normalized
graph of components, rather than editing code or HTML. Think of it as
Figma's data model + Observable's reactive graph + LSP's
protocol-mediated mutations, all pointed at "AI reshapes the app
directly."

From the browsing README (paraphrased): combine ECS-style normalized
state with a single mutation kernel so that every change is
serializable, inspectable, and reversible — which is exactly the
substrate an LLM needs to hold a UI in its head.

### Development arc

Three phases visible in the 147-commit log:

1. **Notebooks & exploration (Mar–Jul 2025).** Clerk notebooks,
   JSXGraph plotting, SCI/Wolfram experiments, Python bridge, a
   portfolio site, a tictactoe toy. A playground — no central thesis
   yet. Several commits read "wasted hours" on unrelated detours.
2. **Kernel crystallization (Aug 2025).** The pivot.
   - `2df4d91  2025-08-03  working kernel: apply-ops`
   - `24de1e9  2025-08-03  test runner for kernel`
   - `fb1b771  2025-08-04  kernel ops unified schema [:patch {params..}]`
   - `8bd81a3  2025-08-04  unified schema ... very finicky with the [[]] in ->ops`
   - `14823ac  2025-08-04  UNIFIED OPERATIONS ... -10% codebase`  ← **crystallization commit**

   A single multimethod dispatcher subsumed `patch`, `move`, `create`,
   `delete`. Normalized `{:nodes, :children, :parents}` replaced
   nested trees. This is the thesis that evo inherited.
3. **Replicant integration (Aug–Sep 2025).** `replicant.dom` plugged
   in, a query/render split for components, style engine on
   predicates. By the time evo's `02a37703 init` landed on
   `2025-09-22`, browsing's architecture was mature enough to fork.

### The crystallization commit

**`14823ac` — "UNIFIED OPERATIONS ... -10% codebase" (2025-08-04).**

Deadpan subject, but the change is the whole design. From this commit
forward, browsing — and by extension evo — had a single answer to
"how does state change?": emit data-shaped ops, one dispatcher applies
them, the rest is derived. Every architectural decision downstream
(transaction pipeline, derived indexes, intent system, event sourcing,
undo, LLM targetability) is a consequence.

## What carried forward

| browsing                                          | evo                                                      |
| ------------------------------------------------- | -------------------------------------------------------- |
| `apply-ops` multimethod (`:patch/:move/:create/:delete`) | `kernel/ops.cljc` + three primitives: `create-node`, `place`, `update-node` |
| Normalized `{:nodes, :children, :parents}`        | Canonical DB: `:nodes`, `:children-by-parent`, `:derived` |
| `command->ops` translation                        | `kernel/intent.cljc` — full intent → ops dispatch with schema validation |
| Replicant + hiccup render                         | Same — with components, keymaps, plugins layered on     |
| Component `{:query, :render}` split               | `src/components/` smart components with derived props    |
| Atomicity through single gatekeeper               | Transaction pipeline: normalize → validate → apply → derive |
| Cycle detection & parent/child invariants in ops  | Promoted to kernel-level invariants + property tests     |
| Predicate-based style rules                       | Class system + theme engine                              |

The continuity is real: evo's three-op kernel is a *refinement*, not
an invention. Browsing had four ops merged under one dispatcher; evo
narrowed to three (by splitting concerns differently — placement is
its own op), added a derivation step, and hardened the boundaries.

## What didn't carry forward

- **Clerk notebooks.** Browsing leaned hard on Clerk for live docs and
  interactive exploration. Evo is pure CLJS on shadow-cljs; the JVM
  side disappeared.
- **SCI / Python / Wolfram / JSXGraph.** The "scientific computing in
  the notebook" thread was entirely abandoned. Evo is text and blocks.
- **Portfolio & tictactoe demos.** Early exploration deliverables,
  pruned.
- **`clojure.zip` tree traversal.** Early versions used zippers for
  tree walks; evo replaced them with direct graph queries over the
  normalized DB and pre/post-order indexes.
- **The "generative UI" framing.** Browsing pitched itself as "LLM
  rearranges your app." Evo narrowed the surface: it's an outliner
  kernel first, LLM-targetability is a consequence of having a clean
  IR, not the headline.

## What crystallized in evo

Design decisions that were fluid in browsing but are now invariants
in evo:

- **Three-op kernel as an invariant, not a convenience.** `create-node`,
  `place`, `update-node`. Enforced by `.claude/rules/invariants.md`:
  "No new primitive operations."
- **Transaction pipeline as a first-class construct.** Normalize →
  validate → apply → derive. Invariants live here; handlers stay dumb.
- **Kernel / shell / plugins / components separation.** `bb check:kernel`
  fails the build if the kernel imports from shell/components/keymap.
  This boundary did not exist in browsing — everything was flat.
- **Session state out of the DB.** The 2025-11-21 split (cursor,
  selection, folding → separate atom) is an evo-era tightening.
  Browsing kept UI state mixed into the graph.
- **FR registry (`resources/specs.edn`).** 44 functional requirements
  with scenario triads, tagged on tests via `^{:fr/ids #{...}}`
  metadata. No analogue in browsing.
- **Extraction mode as north star.** GOALS.md: the kernel is the
  valuable artifact; the UI is the kiln. Browsing was an app;
  evo is a library with an app attached to test it.
- **Event sourcing as canonical.** Every mutation is an immutable EDN
  op. Undo = replay. Browsing had this in spirit but not enforced.
- **REPL-first + property tests.** 30-second REPL verification is a
  hard rule; property tests on random op sequences verify invariants.
  Browsing had a test runner but the property discipline is evo's.

## The six-month gap

Browsing's first commit is 2025-03-12. Evo's `init` is 2025-09-22.
Six months. Only ~3 weeks of that (late Jul → mid-Aug 2025) shows the
pivot from notebooks to op-kernel; the rest is either exploration or
silence.

One plausible reading: the "unified operations" insight arrived on
2025-08-04, was then lived-with in browsing for six more weeks while
the Replicant integration settled, and only then did evo begin as
a clean fork where the design could be built on top of the lesson
instead of retrofitted onto the playground.

Worth noting: browsing kept receiving commits until 2026-02-26, five
months into evo's lifetime. Some of those were probably "scratch
tests for kernel CRUD operations" (literally the final commit
subject) — browsing stayed useful as a low-stakes place to prototype
ideas before landing them in evo.

## Open question: a second prototype?

The user thought there might have been two precursors. An exhaustive
sweep of `~/Projects/` for CLJS projects predating 2025-09-22 turned
up exactly one: browsing. Candidates that looked plausible by name
were all eliminated on inspection:

- `scripting` — started 4 days before evo but is pure shell/Nushell,
  unrelated.
- `anywidget-repl`, `anywidget-svelte` — Svelte/TypeScript, different
  stack.
- `chats`, `demo-app` — postdate evo; `demo-app` was spawned from
  evo-template.
- `synthoria.bio`, `sean`, `markus-*`, `phys-*` — different domains.
- `best/logseq/` — a reference copy of Logseq itself, not original
  work.

If a second ancestor existed, it was either:
1. Never committed (scratch files in `~/Desktop` or similar), or
2. Private / deleted, or
3. A misremembered sibling that's actually just one of the phases
   of browsing itself (the Clerk era vs the kernel era feel distinct
   enough in browsing's log that they could read as "two projects"
   in memory).

If a name surfaces later, append it here.

## How to inspect browsing

The repo is still on disk at `~/Projects/browsing/`. Nothing special
required:

```bash
cd ~/Projects/browsing
git log --reverse --oneline      # the full arc, oldest first
git show 14823ac                 # the crystallization commit
bat src/kernel.cljc              # the 131-line ancestor kernel
```

If at some point you want to archive browsing and free the working
directory, a single-file git bundle preserves full history:

```bash
git -C ~/Projects/browsing bundle create \
  ~/Projects/evo/docs/ancestry/browsing.bundle --all
# Later, to restore:
git clone ~/Projects/evo/docs/ancestry/browsing.bundle restored/
```

No bundle has been created yet. Browsing is still live at its
original path.

## Why this file exists

Git can show you a file's history inside one repo. It can't show you
the *idea's* history across repos. The leap from "unified ops
multimethod on a flat normalized graph" (browsing, Aug 2025) to
"three-op kernel with transaction pipeline, derived indexes, intent
dispatch, and extraction mode" (evo, today) is the kind of
crystallization that's invisible in `git log` but load-bearing for
anyone trying to understand *why* the kernel looks the way it does.

This file is the audit trail for that leap.
