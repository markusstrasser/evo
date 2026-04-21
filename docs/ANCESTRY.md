# Ancestry

A narrative history of the ideas and code that fed into evo. Git log
inside a single repo can't tell this story — evo's history starts at
`02a37703 init` on 2025-09-22, but the editor it is today was already
being designed, prototyped, and pivoted through for six months before
that in a predecessor repo, and has gone through six further phases
of internal crystallization since.

## TL;DR

Evo's direct **code** ancestor is **`~/Projects/browsing/`**
(`2025-03-12` → `2026-02-26`, 147+ commits across six internal
phases). Browsing is where the ops/kernel crystallization happened
on `2025-08-04` (`14823ac` — "UNIFIED OPERATIONS ... -10% codebase"),
and it's where the current evo kernel was forked from.

Evo's **idea** ancestry runs further back — through four TS/Svelte
repos that chased the same "AI-native UI-evolver" thesis before
browsing existed. In chronological order: **`savant`** (2023, Next.js)
→ **`synth`** (2024-05, Next.js, first "generative UI" pitch) →
**`synthoric`** (2024-06 → 2024-12, Svelte 5 + SvelteKit, 140 commits,
first dynamic-interface-generation prototypes) → **`flowread`**
(2025-02-20 → 2025-03-09, Svelte 5, 270 commits, first unified
store + event-sourced tree ops — the direct Svelte predecessor that
ended three days before browsing started). The move to ClojureScript
at `browsing init` on `2025-03-12` was a language pivot, not an idea
pivot — the event-sourced unified-ops thesis was already on the page.

Evo itself is 1,659 commits (as of 2026-04-20) split into six
internal phases: kernel foundation → three-op IR lock → intent-layer
+ plugins → session/DB split → FR registry → Nexus removal and
specviewer extraction. Key milestones have dated, hash-addressable
commits and are listed below.

Downstream there is at least one consumer (`~/Projects/chats/`,
spawned from `evo-template`) whose existence validates the
"extraction mode" thesis.

## Lineage at a glance

```
idea lineage (TS/Svelte)                              code lineage (CLJS)
─────────────────────────                             ───────────────────

2023-06  savant (Next.js, TS)
         │  "domain functions", streaming AI,
         │  inference saved to store, dedup tree util
         │  — first "AI processes content → state" sketch
         ▼
2024-05  synth (Next.js, Vercel AI SDK)
         │  "AI-driven STEM learning platform …
         │  through generative UI"
         │  first explicit "generative UI" pitch,
         │  172 commits, xstate attempt → abandoned
         ▼
2024-06  synthoric (Svelte 5 + SvelteKit, Convex)
         │  Svelte pivot — same pitch, new stack.
         │  140 commits. `generateComponent` API,
         │  `generateDynamic with orchestrator prompt`,
         │  `ui builder v.01 with prefill`,
         │  `split up dynamicinterface generation`
         ▼
2025-02-20  flowread (Svelte 5 + TS + Arktype)
         │  "next gen reader app". 270 commits in 17 days.
         │  `first step in eventsourcing` (b26b176),
         │  `unified store` (290d6e3),
         │  `schema simplification → mirror actual DOM …
         │   semantic structure derived later` (45ca6d5),
         │  `compress doc intent / action` (8b56fde),
         │  `curry dispatch … store forwards curried
         │   treeoperations` (32db096, eefa671).
         │  /unified route = direct Svelte dry-run of
         │  what became browsing's unified-ops kernel.
         │
         │  last commit 2025-03-09 14:24
         └──────────────────── 3 days ────────────────────┐
                                                          │
                                                          ▼
browsing                                              evo
──────────                                            ──────────
2025-03-12  Initial Setup  (ed4126b)
            │
            │  Phase 1 — DOM-centric exploration
            │  Clerk + portfolio + tictac
            │  ("wasted hours" on routing)
            │
2025-03-16  newschema branch
            │
            │  Phase 2 — tree-ops + multimethod dispatch
            │  nested hiccup, action → multimethod pivot
            │
2025-04–07  Phase 3 — zipper prototype & pain
            │   text fragmentation, prewalk, stack overflows
            │   (archived at prototypes/zippers.clj)
            │
2025-07-31  Phase 4 — kernel crystallization sprint
2025-08-03  "working kernel: apply-ops"        (2df4d91)
2025-08-04  "UNIFIED OPERATIONS ... -10% codebase" (14823ac)  ← crystallization
            │
            │  Phase 5 — instrumentation & UI integration
            │  JSXGraph, Python/SCI, Clerk debug tools
            │  prototypes/hypergraph.clj (never integrated)
            │
            ├──────────────────────────────────┐
            │                                  │
            ▼                                  ▼
2026-02-26  "Add scratch test for       2025-09-22  init (02a37703)
             kernel CRUD operations"    │
            (final commit on main;      │  Phase 1 — kernel foundation
             jsx branch still alive)    │  tree DB + derived metadata
                                        │  (a6cad43e — "real first kernel")
                                        │
                                        │  2025-09-29  three-op IR locked
                                        │  (48922610 — migration guide)
                                        │
                                        │  Phase 2 — three-op IR
                                        │
                                        │  2025-10-25  query layer + defintent
                                        │  (3c6280be, a13f0058)
                                        │
                                        │  Phase 3 — intent layer + plugins
                                        │
                                        │  2025-11-18  FR registry born
                                        │  (b6504c93)
                                        │
                                        │  2025-11-21  session split from DB
                                        │  (52fcd735 uncontrolled editing)
                                        │
                                        │  Phase 4 — session/DB split
                                        │
                                        │  Phase 5 — FR expansion
                                        │
                                        │  2026-03-08  Nexus removed; direct
                                        │  dispatch; post-Nexus docs
                                        │
                                        │  Phase 6 — extraction, specviewer
                                        │
                                        ▼
                                   2026-04-20  (current, 1659 commits)
```

The overlap is real: browsing's final main-branch commit is
2026-02-26, five months into evo's lifetime. Browsing stayed useful
as a low-stakes scratch surface — the last commit is literally
"scratch test for kernel CRUD operations."

The TS/Svelte chain (savant → synth → synthoric → flowread) is
described in **Part 8** — it predates browsing and carries the
idea (AI-native UI, event sourcing, unified store, curried
dispatch) but not the code.

---

## Part 1 — The code ancestor: `~/Projects/browsing/`

### What it was trying to be

From browsing's `agent-readme.md`:

> "Conversational UI orchestrator where an AI LLM can arbitrarily
> modify the view for user needs from a 1000+ component library."

The pitch blended three references:

- **Figma's data model** — normalized node storage with explicit
  parent indices, not a nested tree.
- **Observable's reactive graph** — derived values recomputed
  automatically from the node graph.
- **LSP's protocol shape** — mutations expressed as serializable
  operations so an AI agent can target them directly.

The headline framing was "generative UI": the LLM rewrites your app
by emitting operations, not code. That framing *did not* survive
into evo — evo narrowed to an outliner kernel where LLM-friendliness
is a consequence of having a clean IR, not the headline.

### Phase 1 — DOM-centric exploration (Mar 12–25, 2025)

Commits `ed4126b` → `b370f0f` (~25 commits). Pure integration
scaffolding: Clerk + nREPL, Replicant setup, a portfolio site, a
tic-tac-toe toy. The commit messages record the friction:

- `7d72cfc` "portfolio still not working"
- `4db6290` "/portfolio.html l.......wasted hours"
- `983449f` "tictac ... not showing"

No thesis yet. Rendering was the pain point; state management was
a nested atom updated by ad-hoc handlers. The portfolio and tictac
examples were pruned at `28dc83c` (2025-03-21) when it became clear
example-driven dev was a trap — the clean path was a library with
one canonical state model.

### Phase 2 — tree-ops and multimethod dispatch (Mar 16–Apr 5, 2025)

The `newschema` branch (merged at `1adea02`) brought the first
architectural pivot: separate node structure from rendering. Nodes
got IDs, handlers became multimethods, a single `content` atom
replaced scattered state.

- `a08527c` "simple dispatch" — action-vectors to multimethods
- `a0d7feb` ".stopPropagation" — hiccup/DOM event model bridged
- `bd81cca` "tree utils" — first traversal helpers

This phase hit its first real wall: `d0c3cb2` "HICKORY NAME COLLISION
... 6 hours of my life" — namespace collision between the HTML
parser (Hickory) and the HTML builder (Hiccup). Infrastructure
friction that forced cleaner module boundaries.

### Phase 3 — zipper prototype & tree-walking pain (Apr–Jul 2025)

As browsing tried to support real content — text fragmentation for
inline styles, nested markdown, drag-and-drop reordering — the
nested-tree approach began to break. Key symptoms:

- `cf9d39e` "text fragmentation"
- `4760e5e` "text fragmentation simpler" (still fragile)
- `b370f0f` "tree-ops with prewalk for update" → stack overflows on
  deep documents

The author prototyped a proper `clojure.zip`-based editor
(`src/prototypes/zippers.clj`, 119 lines, still in the repo as a
museum exhibit). Zippers are principled but verbose — each edit
rebalances the path, tree moves need explicit sibling tracking, and
the state is monadic in a way that fights REPL-first workflow.

This phase is what *caused* the crystallization. The zipper pain
made "nested tree as canonical form" untenable.

### Phase 4 — kernel crystallization (Jul 31 – Aug 4, 2025)

The pivotal sprint. Four calendar days, ~15 commits, -2,200 lines.

- `cfbd414` "update deps" — fresh start
- `d02900f` "new hiccup first schema and normalizing utils" — the
  normalized graph shape appears: `{:nodes, :children, :parents}`
- `2df4d91` "working kernel: apply-ops" (2025-08-03) — first unified
  dispatcher
- `fb1b771` "kernel ops unified schema `[:patch {params..}]`"
- `8bd81a3` "unified schema ... very finicky with the `[[]]` in
  `->ops`"
- `4bdfdcb` "malli init" — typed schemas introduced
- **`14823ac` "UNIFIED OPERATIONS ... -10% codebase"** (2025-08-04)
  — 20 files, +21 / −2,240 lines. The thesis commit.

After `14823ac`, the answer to "how does state change?" was one
sentence: *emit data-shaped ops, one dispatcher applies them, the
rest is derived.* Every downstream decision — transaction pipeline,
derived indexes, intent system, event sourcing, undo, LLM
targetability — is a corollary.

The kernel settled at 131 lines and stayed there. The five ops
(`:patch`, `:place`, `:create`, `:move`, `:delete`) would later be
narrowed to three in evo by merging move into place/update.

### Phase 5 — instrumentation and UI experiments (Aug 4 – Sep 25, 2025)

With a stable kernel, the author went sideways into developer
experience and visualization:

- `2c24c94` "proto graph create, patch, delete, move ops" — the
  `prototypes/hypergraph.clj` file (177 lines, DataScript-inspired,
  never integrated, kept as a reference for "what if nodes had
  typed edges?").
- `ca7fe69` fireworks tap / `e09b955` clojurestorm / `4dd3c24`
  flowstorm — debugging investment.
- `bb1214e` / `2089351` — Clerk + MCP + LLM debug tools.
- `8b75e45` / `ed23799` / `74f76b7` — JSXGraph integration; the
  `jsx` branch is still active at browsing's HEAD.
- `5673e68` — "use python and packages within clerk clj" — SCI /
  Python interop exploration.
- `bc49ce0` "NEVER put clerk notebooks into /dev! hot reloading
  works in /notebooks" — the lesson-learned commits.

None of this made it into evo. The JSXGraph / SCI / Wolfram /
Python thread was a separate axis — "scientific computing in the
notebook" — that evo consciously dropped. But these commits
coexist in time with evo's early development, which tells you
what was *tempting* about browsing: it was a playground where you
could try anything. Evo's narrow scope is exactly the reaction to
that temptation.

### Phase 6 — stable maintenance (Feb 2026)

- `ead4a2e` "Add nREPL client test script for shadow-cljs
  connectivity"
- `e6b75ff` "Add scratch test for kernel CRUD operations" (final
  commit on main)

By this point browsing is a reference implementation — the kernel
works, there are tests, and any new idea worth taking seriously goes
into evo.

### Branches in browsing (not all merged)

- **`jsx`** — current HEAD, the JSXGraph + Python integration thread.
- **`newschema`** — merged Mar 2025, kept for history.
- **`normalized-flat`** — the pre-crystallization work on the flat
  data model.
- **`ops-test`** — early event dispatch experiments, superseded.
- **`colocated-convolved`** — experimental feature branch.
- **`clerk-llm-guide`** — LLM integration notes.

### Archived prototypes in browsing

- `src/prototypes/zippers.clj` (119 lines) — the approach the
  crystallization repudiated. Useful to read as context for *why*
  normalized + ops won.
- `src/prototypes/hypergraph.clj` (177 lines) — a DataScript-style
  typed-edge graph. Never live. Records an idea that was considered
  and set aside: "what if blocks had typed relationships beyond
  parent/child?"

### Concept first-appearances in browsing

| Concept | Commit | Date | Note |
| --- | --- | --- | --- |
| multimethod dispatch | `a08527c` | 2025-03-14 | first use of `defmulti` for events |
| schema (flat) | `c813a15` | 2025-03-15 | "starting new schema" — flattening begins |
| normalize | `d02900f` | 2025-07-31 | "new hiccup first schema and normalizing utils" |
| op language | `67e2e1b` | 2025-08-02 | "s2 working" |
| `:patch` | `6ba48b7` | 2025-08-02 | "simple tx and splice/set 2 ortho ops" |
| kernel (named) | `2df4d91` | 2025-08-03 | "working kernel: apply-ops" |
| Malli schemas | `4bdfdcb` | 2025-08-03 | "malli init" |
| unified ops | `14823ac` | 2025-08-04 | **crystallization** |
| hypergraph sketch | `2c24c94` | 2025-08-05 | prototyped, not integrated |

Notable absence: "intent" never became a concept in browsing's code.
It exists in `agent-readme.md` as an aspiration. Evo is where
intents moved from noun-in-a-design-doc to `defintent` macro.

---

## Part 2 — Evo's own internal evolution

Six phases since 2025-09-22. Phase boundaries are architectural, not
calendar-based.

### Phase 1 — Kernel foundation (Sep 22–25, 2025)

From `02a37703 init` through `a6cad43e`. About 40 commits in the
first three days.

- `02a37703` "init" (Sep 22, 12:27) — Replicant + shadow-cljs scaffold.
- Rapid iteration on ordering: fractional indexing (Greenspan CRDT)
  was tried, then simpler integer positions won. DataScript was
  tried, then plain maps won — simpler mental model when the whole
  point is a readable kernel.
- **`a6cad43e`** (Sep 25, 17:12) "feat(kernel): implement tree DB,
  derived metadata and structural edit ops; add command API" — the
  "first real kernel" commit. After this, the DB shape
  (`{:nodes, :children-by-parent, :derived}`) is stable.

The three decisions that stuck: tree DB over graph DB, map-based
over DataScript, derived indexes as first-class (`:parent-of`,
`:next-id-of`, `:index-of`, `:pre`, `:post`).

### Phase 2 — Three-op IR crystallization (Sep 26 – Oct 25, 2025)

About 200 commits. Six major reshapes, each tightening the
operation surface.

- **`48922610`** (Sep 29) — three-op kernel migration guide.
  Browsing's five ops (`:patch/:place/:create/:move/:delete`) were
  narrowed to three (`:create-node`, `:place`, `:update-node`) by
  folding `:move` into `:place` and `:delete` into `:place` with a
  trash anchor. This is the canonical form that's still in place.
- **`6507c024`** (Oct 25, merge) — "Return to true 3-op IR with
  unified read layer." The `:update-ui` op that had crept in during
  plugin experiments was removed; UI state moved out of the op
  language entirely. This merge is the moment "three-op" became
  non-negotiable.

Everything downstream — the `invariants.md` rule "no new primitive
operations," `bb check:kernel`, the FR tests — protects this lock.

### Phase 3 — Intent layer and plugin ecosystem (Oct 25 – Nov 21, 2025)

- **`3c6280be`** (Oct 25) "feat(kernel): create query layer for
  centralized database reads" — `kernel.query/*` becomes the single
  read surface. Plugins stop touching the DB directly.
- **`a13f0058`** (Oct 25) "refactor(intent): convert all plugins to
  `defintent` macro" — intents become declarative data:
  `(defintent :move [{:keys [id at]}] ...)`. Every plugin gets the
  same signature. Intent dispatch stops being a registry of
  handwritten functions.
- Following month: `:move` consolidates `:reorder` + manual move;
  `:selection` unifies fragment intents; ~40 redundant helper
  intents are collapsed.

This is the phase where the word "plugin" means something specific:
a pure function from `[db session intent]` to `{:ops [...]
:session-updates [...]}`. No mutation, no imports from `shell/`.

### Phase 4 — Session / DB split (Nov 21 – Dec 10, 2025)

The architectural change that has the most user-visible impact: UI
state (cursor, selection, editing, folding) left the DB entirely.

- **`52fcd735`** (Nov 21) "feat(block): implement uncontrolled
  editing architecture" — the block editor becomes an uncontrolled
  DOM node; cursor lives in the session atom.
- **`36f32747`** (Nov 24) "refactor(kernel): add session param to
  intent handler signature" — all 40+ intent handlers gain
  `session` as a third argument. This is the moment every plugin
  can read session without importing shell.
- `fd6a5afe` — "remove buffer plugin, typing now pure session"
  closes the loop; keypress buffering leaves the persistent log.

Payoff: undo/redo stops blowing up because cursor moves aren't in
the event log. Snapshots are ~3× smaller. The DB is now cleanly
"the document," period.

### Phase 5 — FR registry and specification as data (Nov 18 – Jan 6, 2026)

- **`b6504c93`** (Nov 18) "feat(specs): Add FR registry as EDN data"
  — 12 Functional Requirements appear in `resources/specs.edn`,
  extracted from `LOGSEQ_SPEC.md`.
- `35fc95e9` (Dec 6) — registry moves to `src/spec/registry.cljc`
  with a Malli schema. FRs become loadable, queryable, testable.
- Dec 6 cluster — executable scenarios get added to each FR: a
  small tree DSL + runner. `bb lint:fr-tests` now verifies every
  FR has ≥1 scenario.
- Through January, FRs grow from 12 to 44. Tests get tagged with
  `^{:fr/ids #{...}}` metadata. FR coverage becomes a first-class
  health metric (`bb fr-audit`, `bb fr-matrix`).

This phase is when "the specs are the product" (principle 4 in the
constitution) became mechanically true: you can read the kernel
without reading the code, and the tests prove the prose.

### Phase 6 — Post-Nexus cleanup and specviewer extraction (Feb – Apr 2026)

The "Nexus" dispatcher was a temporary routing layer introduced
around Nov 28 to debug dispatch order under concurrent intents. It
was never the architecture — but it lived in the code for ~3 months.

- **`adc3a5b9`** (Mar 8) "[shell] Remove Nexus dispatcher — replaced
  by direct function dispatch"
- `b30d92c4` "[plugins] Add manifest for centralized plugin
  registration"
- `029621fb` "[scripts] Purify scripts — return structural facts
  only, no intent emission"
- `eb30829f` "[docs] Update all docs for post-Nexus architecture"

Then specviewer was built: a browsable UI over `specs.edn` that
renders FRs, their narratives, and their scenarios — independent of
evo's own editor UI. That's the *extraction proof*: the kernel's
specification is now readable via a tool that doesn't require evo
itself.

### Evo concept timeline

| Concept | First commit | Date | Replaced / from |
| --- | --- | --- | --- |
| Tree DB + derived indexes | `a6cad43e` | 2025-09-25 | DataScript atoms, nested hiccup |
| Three-op IR (`create-node`, `place`, `update-node`) | `48922610` (guide) | 2025-09-29 | browsing's 5-op dispatcher |
| Unified read layer (`kernel.query/*`) | `3c6280be` | 2025-10-25 | plugins reading DB directly |
| `defintent` macro | `a13f0058` | 2025-10-25 | manual intent registration |
| FR registry (EDN) | `b6504c93` | 2025-11-18 | `LOGSEQ_SPEC.md` prose |
| Uncontrolled editing / session atom | `52fcd735` | 2025-11-21 | session-as-DB-subkey |
| 3-arg intent signature | `36f32747` | 2025-11-24 | `(db intent)` without session |
| Scripts (multi-step ops) | `9ccce4e3` (rename) | 2025-12-07 | `macros/` |
| Executable FR scenarios | Dec 6 cluster | 2025-12-06 | prose-only specs |
| Post-Nexus direct dispatch | `adc3a5b9` | 2026-03-08 | Nexus routing layer |
| Specviewer | Apr 16 cluster | 2026-04-16 | (new — extraction proof) |

### Dead ends and reverts inside evo

- **Nexus dispatcher** (Nov 28 → Mar 8) — temporary routing layer
  to debug concurrent dispatch. Removed cleanly.
- **Buffer plugin** (Sept 26 → Nov 21) — keypress buffering in the
  persistent layer. Removed when session became ephemeral.
- **`:update-ui` op** (Oct 1–24) — crept in during plugin
  experiments; removed at `6507c024` when "true three-op" was
  reasserted.
- **Plugin manifest v1** (Nov 20 → Dec 16) — centralized plugin
  registration; replaced by simpler loader, then reintroduced
  post-Nexus in simpler form.
- **Visible-order index** (Nov 17) — added, then deprecated; sort
  order is now derived on demand in plugins.

### Deleted executed plans

Per the constitution ("Docs: facts not plans"), evo deletes plan
docs once they're executed. Git preserves history. Notable removals:

- `ARCHITECTURE_UNIFICATION_PLAN.md` — executed, removed ~Mar 8
- `DEPENDENCY_REVIEW.md` — one-shot assessment, recs landed, removed
- `.claude/overviews/*` — auto-generated orientation artifacts,
  superseded by `docs/DX_INDEX.md`

---

## Part 3 — What carried forward (browsing → evo)

| browsing (Aug 2025)                              | evo (Sep 2025 → now) |
| --- | --- |
| `apply-ops` multimethod, 5 ops (`:patch/:place/:create/:move/:delete`) | 3-op IR (`create-node`, `place`, `update-node`) in `kernel/ops.cljc`; move and delete expressed as `place` variants |
| Normalized `{:nodes, :children, :parents}` | `{:nodes, :children-by-parent, :derived {:parent-of, :next-id-of, :index-of, :pre, :post, :id-by-pre}}` |
| `command->ops` translation (aspirational in `agent-readme.md`) | `defintent` macro + `kernel/intent.cljc` with Malli schemas and allowed-state validation |
| Single atomic gatekeeper `apply-op` | Transaction pipeline: normalize → validate → apply → derive |
| Cycle detection & parent/child invariants baked into ops | Promoted to kernel-level invariants with property tests + FR scenarios |
| Replicant + hiccup render | Same — with `components/`, `keymap/`, `plugins/` layered on |
| Component `{:query, :render}` split | `src/components/` smart components with derived props and keyed conditional rendering |
| Malli schemas (initial) | Malli + FR scenarios + specs.edn as canonical registry |
| Predicate-based style rules | Class system + theme engine |

The continuity is real. Evo's three-op kernel is a *refinement*
of browsing's five-op dispatcher — narrower, stricter, with a
transaction pipeline around it and derived indexes below it.

---

## Part 4 — What didn't carry forward

Ideas browsing explored that evo consciously discarded:

- **Clerk notebooks.** Browsing invested heavily; evo runs on pure
  CLJS/shadow-cljs, no JVM in the runtime.
- **SCI / Python / Wolfram / JSXGraph.** The whole "interactive
  scientific computing in a notebook" thread. Evo is text and
  blocks.
- **Portfolio / tictac / demos.** Example-driven exploration;
  pruned even inside browsing itself.
- **`clojure.zip` tree traversal.** The whole reason the
  crystallization happened. Archived at
  `~/Projects/browsing/src/prototypes/zippers.clj` as a museum
  exhibit.
- **Typed hypergraph edges.** Prototyped at
  `~/Projects/browsing/src/prototypes/hypergraph.clj` (177 lines).
  Never integrated. Evo sticks to parent/child plus the three-op
  IR; typed edges remain an open question.
- **The "generative UI" framing.** Browsing pitched itself as "LLM
  rearranges your app." Evo narrowed: it's an outliner kernel
  first, LLM-targetability is a consequence.
- **Tree-walking via `prewalk` / `postwalk`.** Replaced by direct
  graph queries over the derived indexes.
- **`:update-ui` as an op.** Briefly tried in evo plugin
  experiments Oct 2025; removed when three-op was reasserted.
  UI state leaves the op language entirely.

---

## Part 5 — What crystallized only inside evo

Decisions that were fluid (or absent) in browsing and hardened into
invariants in evo:

- **Kernel / shell / plugins / components boundary.** Zero imports
  from `shell/`, `components/`, `keymap/` into `src/kernel/`.
  Enforced mechanically by `bb check:kernel`. Browsing was flat.
- **Three-op IR as a hard invariant.** `invariants.md`: "All state
  changes reduce to `create-node`, `place`, `update-node`. No new
  primitive operations."
- **Transaction pipeline as a first-class construct.** Normalize →
  validate → apply → derive. Invariants live in the pipeline, not
  in handlers.
- **Session state as a separate atom.** The Nov 21 split. Cursor,
  selection, folding, editing state — all ephemeral, all out of
  the document graph.
- **FR registry (`resources/specs.edn`, 44 FRs).** Specification
  as data, testable via tree-DSL scenarios, cited from tests via
  metadata.
- **Extraction mode.** `GOALS.md`: "If the kernel doesn't find use
  in another project, Evo becomes an archived reference project."
  Proven by specviewer (Apr 2026) and by `chats/` using
  evo-template.
- **Scripts pattern for multi-step ops.** Dry-run on scratch DB,
  accumulate ops, commit atomically. Browsing didn't need this;
  it had no "chain of ops" use case.
- **REPL + property tests as the validation floor.** Every kernel
  behavior demonstrable in the REPL with a fixture DB in <30s.
  Property tests run random op sequences to check invariants.
  Browsing had a test runner but no property discipline.
- **The "Build → Learn → Extract → Generalize" philosophy as an
  explicit constitutional commitment.** Browsing lived in the
  Build phase. Evo added Extract and made it normative.

---

## Part 6 — Downstream: evo as ancestor

Ancestry also runs forward. `~/Projects/chats/` (first commit
2025-10-27, `ac9d74a Initial commit from evo-template`) spawned
from an evo-derived template and uses evo's kernel design to
manage conversation + artifacts state. The repo contains
`ARCHITECTURE_SHOOTOUT.md` comparing "evo Kernel (Tree-Based)" vs
alternatives and landing on: "Use the proven 3-op kernel from evo
for all state management."

That's the extraction-mode thesis in the wild: the kernel is
valuable precisely because it can leave evo.

---

## Part 7 — The six-month gap and the overlap

Two calendar questions worth addressing:

**The gap (Mar–Jul 2025).** Browsing's first commit is 2025-03-12;
the unified-ops crystallization is 2025-08-04. Four months of
exploration, one month of zipper pain, then a four-day sprint that
changed everything. The gap isn't empty — it's the cost of finding
out that nested trees don't work for this problem.

**The overlap (Sep 2025 – Feb 2026).** Browsing kept receiving
commits for five months after evo started. Some were maintenance,
some were the JSXGraph / SCI / Python thread (the `jsx` branch is
still HEAD), some were "scratch tests for kernel CRUD operations."
The overlap reflects a healthy pattern: evo got the important
lesson and the production weight; browsing kept being a cheap
place to try ideas before landing them.

---

## Part 8 — The TS/Svelte idea lineage (before browsing)

The earlier sweep only looked for **code** ancestors (CLJS / editor
primitives / shadow-cljs). That framing missed the **idea** lineage:
the "AI-native UI that rewrites itself from user behavior" thesis
was chased for ~22 months in TypeScript and Svelte before the CLJS
pivot. There are four repos in the chain, all on GitHub under
`markusstrasser/*`, none currently cloned locally by default:

### savant (2023-06-08 → 2023-07-19) — TS + Next.js — *ranking engine*

41 days, ~60 commits. The earliest sketch. A Chrome-extension +
Next.js app that ingests browser history, uses GPT-4 as a *filter*
(not a generator), groups the survivors by domain, and shows them
back. LLM-as-classifier; no UI generation yet.

**Architecture.** Single Zustand store with three orthogonal
concerns that foreshadow evo's later split:

```
contents[]       — original atoms pulled from sources (history, parsed URLs)
inferences[]     — LLM decisions over those atoms (kept, skipped, confidence)
interactions[]   — user selections (upvotes / downvotes / picks)
processed[]      — ids already sent to LLM (dedup ledger)
```

Dispatch is direct method calls — `setContents`, `setInferences`,
`upsertItem(path, item, keys)` with lodash-path traversal. No
middleware, no event log, no separation between "what happened"
and "what the state is now."

**The shape in one sentence.** `extract → LLM-rank → cluster by
domain → display`. Commit messages that matter:

- `feat: keep history in state but processedHistory in store`
- `feat: save interactions to store`
- `feat: save processed Ids in store to only process once`
- `feat: dedup tree util`
- `feat: group UI by domain`

**Where it got stuck.** Three signals of the architectural
tension that would recur:

1. **Commit `274afe6 refactor: remove .inferences as separate
   struct and merge with contents`** — the author tried to keep
   LLM decisions in a separate table, realized it split authority,
   and merged them back. Same lesson synth would re-learn with the
   `unify-AIstate` branch a year later, and that evo finally
   settled by making the DB the sole document graph and session
   a separate atom.
2. **Dead branch `origin/completionAPI`** — last commit
   `0f71921 feat: run /select for subsets of atoms (chained)`,
   never merged. An attempt to make LLM ranking *iterative*
   (rank → re-rank the survivors → re-rank again). The recursion
   shape was right; the infrastructure wasn't there to keep the
   intermediate states coherent.
3. **README `## temp` section** — the author thinking out loud:
   > "useChat inside separate component to — do summarization
   > (-> then write to store) — do inference (inside component -->
   > fire up if materially new stuff for user to look at). derived
   > zustand store with chatCompletion (withInference middleware -
   > **separate store for inferences??**)"
   The question marks are the point. *Where does inference live*
   was unresolved.

**`src/pages/sketch.tsx`** (80 lines, mostly commented out) shows
a parallel desire: a scientific-notebook vibe with `<red>` tags,
KaTeX, "PLOT rendering", "JSONVIEW components". None of it wired
up. This is the first appearance of the "AI output is structured
content, not just text" instinct that synth would try to productize
and synthoric would try to generate from code.

**What transmits.** The three-concern split (content, inference,
interaction), dedup obsession, and server-action pattern. The
Zustand + persist middleware combo survives all the way to
synthoric.

**What dies here.** Chrome-extension as ingestion surface, chat
as primary UI metaphor, the idea that dedup belongs in the store
rather than at generation time.

### synth (2024-05-01 → 2024-06-24) — Next.js + Vercel AI SDK — *generation engine*

172 commits, 54 days. First repo whose README states the thesis:

> "The goal is to make an AI-driven STEM learning platform that
> adapts content and interaction types to individual users,
> capturing and analyzing user actions to optimize the content and
> representations **through generative UI**."

Stack: Next.js + Vercel AI SDK + Convex + shadcn + Clerk + Sentry +
Posthog + Drizzle + Biome.

**Architecture.** A **three-actor loop** that never closed:

```
          ┌──────── Decide  (LLM: what interaction type next?) ────┐
          │                                                        │
          ▼                                                        │
      Generate  (LLM: build the task object)                       │
          │                                                        │
          ▼                                                        │
   [render interaction, user submits UserActions[]]                │
          │                                                        │
          ▼                                                        │
      Infer  (LLM: update knowledge / skill / misconception graph) ┘
                          │
                          └─── never actually fed back into Decide
```

Two stores, split authority:
- **Client** (`src/app/appStore.ts`, Zustand): `interactions[]`,
  `currentInteraction`, `userActions[]`.
- **Server** (Convex): `interactions`, `inferences`, `history`
  tables with `v.any()` payloads — schema-less because synth
  couldn't settle the interaction schema.

Dispatch is **server actions + Convex mutations**. Tool selection
is a hard-coded `interactionType2Tool` map:

```typescript
const interactionType2Tool = {
  exercise: generateTextInputExercises,
  multipleChoice: generateMultipleChoiceTasks,
};
// TODO: infer from interactionTypes schema ... or types..
```

**The three structural failures** (all recorded in commits):

1. **xState FSM attempt and rip-out.** `1b7ee87 feat: goddamn
   xstate machine mock` → three commits later → `f160a14 chore:
   remove xstate for now ... to much`. A formalism for modeling
   interaction sequences that fought the REPL-first workflow.
   Same shape as browsing's zipper failure a year later: *the
   elegant abstraction resists ad-hoc iteration*.
2. **"Massive refactor part 1/3" and "part 2/3"** — commits
   `c0717ab` and `5596e14`. No "part 3/3" ever appears. Feature
   velocity collapses after these.
3. **Branch `origin/unify-AIstate`** — never merged. README
   TODO on that branch:
   > `[] One source of truth in AIState`
   > `[] parseAIstate to components (through .name/.displayname)`
   The diagnosis is in the branch name: *stop splitting state*.
   synth couldn't fix it; evo's "kernel owns the document, shell
   owns the session" split is the eventual answer.

**The feedback loop that never wired.** `inferences[]` were
generated and stored with `{type, description, masteryLevel,
confidence, sources[{id, whyRelevant, weight}]}` — rich shape,
real evidence chains. But `decideNextInteraction` never read them.
Synth built the producer and the consumer but not the wire
between them. This is the canonical "generative UI without a state
model" trap.

**Dead code that signals direction.**
- `src/_scratch.ts` — commented-out `useHotkeys` for `i` / `e`
  bindings. The author wanted keyboard-first REPL-style navigation
  and couldn't reconcile it with Next.js client/server boundaries.
- `src/lib/tools/index.ts` — `const IxSeqTypes = ['TextExercise',
  'BooleanStorm', 'GraphMaster']`. "GraphMaster" is a planned
  graph-editing interaction type that never shipped. First time
  "the interaction *is* a graph edit" appears in the ancestry.
- `src/app/actions/notworking.tsx` — exists, never imported.

**How it ended.** The last ten commits are maintenance:
`docs: wrote small readme`, `chore: restructure, todos`,
`dev: setting up million lint`, `fix: test streamingUI and fix
vercel/ai to early non-buggy version`. No "v1 shipped" commit,
no retrospective. The README closes with "Full architecture is
still in development."

**What transmits.** The three-actor decompose (decide / generate /
infer) survives as evo's intent → plugin → transaction → derive.
The `UserAction` shape (`{id, displayIndex, fromSpec, value,
timeStamp}`) is the direct ancestor of evo's op shape. Convex
drops out but "DB as source of truth, client as projection"
survives.

**What dies here.** FSMs for interaction sequencing, separate
client cache + server DB, chat-as-container. Also: the idea that
the interaction schema could stay `v.any()` — synthoric tried to
rescue it with prompts; flowread finally typed it; evo locked
it to three ops.

### synthoric (2024-06-24 → 2024-12-04) — Svelte 5 + SvelteKit + Convex — *peak "LLM emits UI code"*

140 commits, 5 months. A *stack rewrite* of synth in Svelte 5 + SvelteKit.
First commit literally: `745f028 copy files from NextJS project`.
Same thesis, new medium: if the component system is simpler, maybe
the generation story gets simpler too.

**Architecture.** The peak "LLM emits UI code" approach. Live
endpoint `src/routes/api/generateComponent/+server.ts`:

```
POST /api/generateComponent { componentName, patchPrompt, interactionId, seqIndex }
  │
  ├─ fetch context from Convex:   convexClient.query(api.interactions.getContext, {seqIndex})
  ├─ Tools.InterfaceSpec.execute(contextStr)      → LLM writes spec prompt
  ├─ generateDynamicInterface(specPrompt)         → LLM writes Svelte source
  ├─ fs.writeFile(`src/components/_generated/Dynamic_${ts}.svelte`, src)
  └─ return { path, debug }
```

That's not metaphor. The LLM generates Svelte source text and the
endpoint writes it to disk under `_generated/`. The client then
imports and renders it. **Each interaction is a fresh component
tree.** There is no canonical document; there is only the pile of
timestamped generated files plus the Convex history of
`interactions`.

**The prompt system is the product.** `src/lib/prompts/index.ts`
is where the pedagogy lives:

- `ContentGuidelinePrompt` — "reduce cognitive load", "intuition
  over calculation", "avoid instructional preamble", SI units only,
  no calculator-required numbers.
- `ActionSelfTagPrompt` — requires the LLM to preface its response
  with a pedagogical state tag ("*testing subcomponents*",
  "*digging deeper into weakness*", "*solidifying concept B*"). An
  attempt to give the LLM a persistent self-model across turns
  without building one into the state.

**Dispatch.** Curried factory pattern introduced in
`15cff4e` (2024-07-26): `createDispatch(type, metaData) =>
(action) => void`. Components receive `createDispatch` as a prop.
This is the *exact* shape flowread and evo inherit.

Class-based store `ActionState` with:
- `userActions` (typed array)
- `revealedMultipleChoices`, `newSubmit` (UI flags)
- `$derived`: `hasSubmitted`, `actionsByType`, `filteredUserActions`

**The stall, commit by commit.**

- **Aug 2024** — productive. `bfd0afa feat: split up
  dynamicinterface generation into subprompts` (the "one big
  prompt" approach breaking down). `d59a8aa feat: generateDynamic
  with orchestrator prompt, p5, d3` (orchestrator + renderer
  libraries as tools). This is where the author realized a single
  LLM call can't produce reliable Svelte; you need a pipeline.
- **Sep 2024** — silence.
- **Oct 2024** — one commit in six weeks: `31b2f0d update to new
  sonnet model 20241022`. Maintenance, not motion.
- **Nov–Dec 2024** — short burst, *pivoting toward content
  rendering instead of content generation*. `4f08914 playground
  with carta markdown and svelte-exmarkdown as MD options`,
  `78450f3 feat: tikz with escaped backticks draws circuit`. This
  is the author trying to get *static* rendering right (LaTeX,
  circuits, markdown) after giving up on *dynamic* Svelte
  generation.
- **Last commit: `78450f3` on 2024-12-04**, then silence. No WIP
  branch, clean working tree, no retrospective. Abandonment-shaped,
  not shipped-shaped.

**The unstated lesson (visible in the code).** Every generated
component is a fresh tree. User history has to be re-threaded
through the prompt each turn. There is no object you can diff,
undo, sync, or reason about across generations. Split prompts and
orchestrators help the *generation* but don't give you *continuity*.
**Generation without a data model underneath plateaus.** Synthoric
hit the plateau. The next project, flowread, started from the
opposite end.

**What transmits.** Curried dispatch as prop (exact shape → evo).
The prompt-engineering instinct (user-input constraints, self-tag)
survives into evo's intent + FR language. `$state` / `$derived` in
Svelte 5 → evo's `derived` indexes.

**What dies here.** LLM-emits-source-code, generated files on disk,
"the component system is the UI model." Also: the Convex dependency.
Flowread drops server persistence entirely and works client-side
against a document in memory.

### flowread (2025-02-20 → 2025-03-09) — Svelte 5 + TS + Arktype — *direct Svelte predecessor*

270 commits in **17 days** — the densest sprint in the lineage.
Ends 2025-03-09 14:24; browsing's `init` lands 2025-03-12. Three
days.

The README narrows: *"next gen reader app. Rn just prototyping.
Solo dev. Simplicity and adding/removing features is key."* The
AI-generative-UI pitch is still in TODOs (*"Reply with custom AI
generated visualizations for a post"*, *"Run a custom
KnowledgeCreator at Node"*, *"UI stays dumb, store/convexDB is
the source of truth"*), but the focus has moved *underneath* the
generation. The question is no longer "how does the LLM emit UI?"
but "what canonical data model can an LLM-generated view sit on?"

**Architecture — the closest pre-browsing shape.** The `/unified`
route (`src/routes/unified/`) is the payoff. `unifiedModel.ts`
defines a normalized graph:

```typescript
UnifiedNode = {
  id, type,                          // document | paragraph | section | span |
                                     // reference | tooltip | definition | comment
  content: { text, title, attributes },
  edges: Edge[],                     // typed relationships — see below
  presentation: { placement, visibility, styling }
}

Edge.type ∈ { contains, references, annotates, defines,
              visualizes, comments }

Document = { rootNode, nodeIndex: Map<string, UnifiedNode> }
```

Compare to evo's `{:nodes, :children-by-parent, :derived {:parent-of,
:next-id-of, …}}`. Shape is isomorphic: flat node index + typed
edges = normalized graph + derived parent/child indexes.
flowread's `edges: contains` ≈ evo's `:children-by-parent`.

**The store, `src/routes/unified/schema/documentStore.svelte.ts`**
(657 lines — the largest single artifact in the pre-browsing
lineage):

```
document             — current Document (rootNode + nodeIndex)
activeNodes          — Map<id, {reason, context}>   (UI focus, highlighted)
lastActivatedByType  — Map<NodeType, id>            (recency per type)
relatedNodes         — Set<id>                      (bidirectional highlight)
recentlyInteractedNodes  — Set<id>                  (history window)
selection            — { text, nodeId, position }
eventLog             — [{time, action, details}]    ← event sourcing surface
```

With Svelte 5 `$derived`:
```
allNodes       = flatten nodeIndex
mainNodes      = filter by placement=main
sidebarNodes   = filter by placement=sidebar
floatingNodes  = filter by placement=floating
activeMainNodes = mainNodes ∩ activeNodes
```

This is *exactly* evo's derived-indexes pattern expressed in Svelte
runes. Evo's `:pre` / `:post` / `:index-of` are the CLJS/immutable
version of what flowread computes with `$derived` over reactive Maps.

**The commit arc — 17 days of crystallization.**

| Date | SHA | Message | What shifts |
|---|---|---|---|
| 02-24 | 9ba28e6 | event-based state | state is no longer component-local |
| 02-25 | b3a333f | event system to the bone | dispatch pattern gets its own module |
| 03-03 | — | prototype selection plugin | plugins as capability sketches |
| 03-04 | — | event logging for text selection | first real use of eventLog |
| 03-06 | d15222e | **10+ files deleted. massive red wedding level carnage** | legacy docs/stores purged |
| 03-06 | b6eb0f2 | better tests. **fundamental ops: walk, transform, editChildren(local lens)** | the word "ops" enters vocabulary |
| 03-08 | b26b176 | **first step in eventsourcing** | explicit naming |
| 03-08 | 290d6e3 | **unified store** | consolidation |
| 03-08 | 8b56fde | **compress doc intent / action** | intent and action collapse into one op shape |
| 03-08 | fa03130 | **design docs** (Design Requirements v3.md, 150 lines, pair-written with Claude) | thesis gets documented |
| 03-08 | f96b5a9 | **route: unified v1** | `/unified` goes live |
| 03-08 | c088ca1 | unifeid demo node *(sic)* | single node type for everything |
| 03-09 | 45ca6d5 | **schema simplification → mirror actual DOM … semantic structure derived later** | THE normalization commit |
| 03-09 | a078dc4 | **Demoted ToC: just another pedestrian node now** | no more special-case nodes |
| 03-09 | 32db096 | store forwards curried treeoperations | ops become curried functions |
| 03-09 | 72d869d | respect data-no-forward-events attr | event filtering |
| 03-09 | d0945be | rm /tests route | ruthless cleanup |
| 03-09 | eefa671 | **curry dispatch and give as prop instead of import** | final commit; dispatch is a prop, not an import |

`45ca6d5` and `a078dc4` are the Svelte version of browsing's
`14823ac` crystallization. The phrasing is almost word-for-word
the decision evo later locks as invariant: *normalized flat graph,
derived indexes, no special-case nodes*.

**What the design doc says** (`docs/Design Requirements v3.md`,
pair-programmed with Claude on 2025-03-08):

> *"AI as a participant in the interaction model — not just
> backend but an entity that can generate UI and events like a
> user would."*
>
> *"Event-driven architecture to reduce noise (vs. having AI
> directly manipulate DOM) … Error boundaries to contain potential
> issues with AI-generated content."*

Two theses in one document. First: *LLMs should speak the same
event language as users* — exactly evo's "intents are data,
serializable, targetable by either a keystroke or an LLM" stance.
Second: *event sourcing is how you keep AI-generated changes
containable* — the reason evo's op log is append-only.

**The plugin sketch** (`src/lib/stores/pluginplay.js`) — incomplete
but shape-accurate:

```javascript
const RustPlugin = {
  id: 'rust-lang',
  requires: [{ id: 'syntax', version: '^1.0.0' }],
  provides: [{ id: 'rust-format', ... }],
  activate(ctx) { ctx.commands.register(...) },
  render: () => "Component",
  style: (nodeId) => "Component"
};

const selectPlugin = {
  selectedNodes: new SvelteSet(),
  onEvent: (event, nodeId) => { /* handle */ },
  style: (nodeId) => this.selectedNodes.has(nodeId)
    ? 'ring-2 ring-yellow-500' : ''
};
```

VSCode-inspired capability-based plugin model: `requires` +
`provides` + `activate` + `onEvent`. Never loaded at runtime in
flowread. Evo dropped the VSCode-style capability declarations
and went with multimethod dispatch on `:type` instead — simpler,
no version ranges, no dependency graph.

**Where it stopped (and it really did stop, mid-stride).**

flowread's last three hours on 2025-03-09 are architectural
refinement, not feature work: `curry dispatch`, `respect
data-no-forward-events`, `rm /tests route`, two near-duplicate
commits (`34b8351` and `eefa671` with identical messages —
probably a rebase/amend artifact). Working tree clean at HEAD.
No WIP branch. No retrospective.

What *wasn't* built despite being in the design doc:

- Plugin runtime / loader (sketched in `pluginplay.js`, never
  wired)
- AI-generated events (the "AI as participant" thesis lives only
  in prose)
- Undo / redo (event log exists, time-travel doesn't)
- Capability-declaration enforcement (runtime wouldn't refuse a
  plugin with missing `requires`)
- Backend persistence (events stay in memory)

This is exactly the set browsing/evo went on to build. flowread
didn't fail to complete itself — it crystallized the
*architecture* and the author pivoted to rebuild it in CLJS with
the right primitives.

**Files still worth reading today** (`~/Projects/flowread/`):

```
src/routes/unified/schema/unifiedModel.ts         — graph types
src/routes/unified/schema/documentStore.svelte.ts — 657-line store
src/routes/unified/schema/documentUtils.ts        — walk/transform/edit
src/routes/unified/components/DocumentView.svelte
src/routes/unified/components/NodeRenderer.svelte
src/lib/components/RecursiveRender.svelte         — tree render
src/lib/actions/dispatchDOMEvents.js              — event forwarding
src/lib/stores/pluginplay.js                      — plugin sketch
src/fixtures/newschema.ts                         — real-content sample
docs/Design Requirements v3.md                    — the thesis
```

**The flowread → browsing handoff.** What transmits directly:

| flowread | → | evo (via browsing) |
|---|---|---|
| `UnifiedNode` + typed edges | → | `{:nodes, :children-by-parent}` + op relations |
| `nodeIndex: Map<id, node>` | → | `:nodes` keyed by string id |
| `$derived` active/related/mainNodes | → | `:derived {:parent-of, :next-id-of, :pre, :post}` |
| `eventLog` | → | op log (commit `746da175`) |
| curried `dispatch` as prop | → | `on-intent` prop + `executor/apply-intent!` |
| `fundamental ops: walk, transform, editChildren` | → | `create-node`, `place`, `update-node` |
| `compress doc intent / action` | → | unified op IR |
| `schema → mirror DOM, semantics derived` | → | normalized tree + derived indexes |
| VSCode-style plugin capabilities | ✗ | dropped; multimethod dispatch on `:type` instead |
| Svelte `$state` reactivity | ✗ | dropped; immutable DB + transaction pipeline |
| Plugin `requires`/`provides` | ✗ | dropped; plugin is just a function |

**What this really is.** flowread is the Svelte dry-run of
browsing's unified-ops crystallization, written five months
earlier. The same sentence — *"one canonical data shape, with
derived views and event-sourced mutations through a curried
dispatch"* — exists in commit `45ca6d5` (flowread) and in
commit `14823ac` (browsing). The CLJS rewrite wasn't a change of
thesis; it was a change of *medium* after TS's nominal/structural
tension kept leaking into tree code (see "Why the chain went
CLJS" below).

### The chain at a glance

```
savant       ranking engine         LLM-as-filter       ceiling:
(2023, TS)   extract → rank →       (GPT-4 returns      split authority
             group by domain        selected indices)   over LLM output;
                                                        processed[] ledger;
                                                        inferences merged
                                                        back into contents
                        ↓
synth        generation engine      LLM-as-generator    ceiling:
(2024-05,    decide → generate      (produces task      three actors built,
Next.js)     → infer (→ never       objects with schema) feedback wire never
             fed back to decide)                        connected; xState
                                                        attempted and ripped
                                                        out; unify-AIstate
                                                        branch never merged
                        ↓
synthoric    generation engine v2   LLM-as-coder        ceiling:
(2024-06,    Convex + disk-written  (generates Svelte   no canonical state
Svelte 5)    Dynamic_${ts}.svelte   source per turn)    under generated
             components                                 components; pivoted
                                                        to static rendering
                                                        (TikZ/markdown) then
                                                        abandoned 2024-12
                        ↓
flowread     document kernel        LLM-as-participant  stopping point:
(2025-02,    /unified: normalized   (thesis: "AI speaks event-sourced model
Svelte 5)    graph + event log +    the same event      crystallized in TS;
             curried dispatch       language as user")  plugin runtime,
             + typed edges                              undo, AI-gen events
                                                        all sketched, not
                                                        built — rebuilt in
                                                        CLJS starting 3 days
                                                        later
                        ↓
browsing     CLJS re-do             apply-ops           crystallization:
(2025-03,    `normalized-flat` →    multimethod;        14823ac unified ops,
CLJS)        zipper pain → unified  5 ops               -10% codebase, the
             ops                    (patch/place/       kernel boundary
                                     create/move/       named
                                     delete)
                        ↓
evo          extraction             three-op IR         extraction proof:
(2025-09,    kernel / shell /       (create-node,       FR registry, specs
CLJS)        plugins / components   place,              as data, specviewer,
             session split          update-node)        template repos
                                                        downstream
```

### The three failure modes the chain worked through

One way to read the four repos is as three distinct ceilings,
each hit and broken in turn:

1. **savant's ceiling: split authority over LLM decisions.**
   Inferences lived in their own table; the author tried to merge
   them back into contents (`274afe6`) and built `processed[]`
   ledgers to dedup. Diagnosis that didn't stick: *where does
   LLM-derived state live*?
2. **synth's ceiling: decide/generate/infer without a feedback
   wire.** The three actors existed as server actions, the
   inference schema was rich (`{type, description, masteryLevel,
   confidence, sources}`), but nothing read inferences back into
   decisions. The `unify-AIstate` branch diagnosed it by name;
   couldn't fix it.
3. **synthoric's ceiling: LLM emits source code = no continuity.**
   Each `generateComponent` call produced a fresh `.svelte` file
   on disk. User history re-threaded through prompts every turn.
   Nothing to diff, undo, sync, reason about across generations.
   The pivot to static markdown/TikZ rendering in Dec 2024 is the
   shape of hitting the ceiling and slowing down.

flowread's move is to start from the opposite end: *build the
canonical document model first, then ask where AI plugs in*. The
design-doc sentence "AI as a participant in the interaction
model — not just backend" is the inversion — AI speaks the same
op language as the user, and both go through the same pipeline.

### What the TS/Svelte chain settled (that browsing didn't have to rediscover)

- **"LLM emits UI code" doesn't scale without a state model.**
  synthoric burned through this approach; flowread started by
  building the state model first.
- **Event sourcing is the right shape.** flowread's
  `first step in eventsourcing` commit (`b26b176`) precedes
  browsing's `s2 working` (`67e2e1b`) by ~5 months.
- **Unified store over scattered component state.** flowread's
  `290d6e3 unified store` and `a078dc4 Demoted ToC: just another
  pedestrian node now` are the Svelte version of browsing's
  `14823ac` — the "one canonical data shape" decision.
- **Schema mirrors the structure; semantics derive.** flowread
  commit `45ca6d5` is the explicit phrasing of what became evo's
  `:children-by-parent` + derived indexes.
- **Curried dispatch passed as prop.** synthoric introduced the
  pattern (`15cff4e`, 2024-07-26); flowread locked it in
  (`eefa671`). Evo's `on-intent` prop + `executor/apply-intent!`
  is the same shape.
- **Intent and action collapse into one op.** flowread's
  `8b56fde compress doc intent / action` is the first explicit
  statement of the principle evo later spells out as "all state
  changes reduce to three primitives."
- **LLMs and users emit the same event language.** The design-doc
  thesis — *AI as a participant, not a backend* — is the reason
  evo's intents are EDN data: serializable, diffable, equally
  targetable by a keystroke or an LLM.

### What the TS/Svelte chain *didn't* settle (and browsing/evo did)

- **No "kernel" as a named construct yet.** flowread has a "unified
  store" but not the discipline that zero UI code imports from it.
  Browsing's `2df4d91 working kernel: apply-ops` is the first time
  "kernel" names a real boundary.
- **No operation IR.** flowread's events are ad-hoc intent shapes
  emitted from components. Browsing's `:patch/:place/:create/…`
  ops are the first time the operation surface is explicit and
  finite.
- **No derived-index discipline.** flowread derives on the fly
  inside components. Evo's `{:derived {:parent-of, :next-id-of,
  :pre, :post, …}}` is the first time the derived layer is named
  and recomputed in the pipeline.
- **No property tests, no FR registry.** Both of these land only in
  evo.

### Why the chain went CLJS at step 5

Not recorded in a commit message, but legible from the file system:
flowread's last week is full of `curry dispatch … curried
treeoperations … tree utils` — tree operations were already feeling
awkward in TS. Browsing's first month repeats that pattern (hickory
collisions, stack overflows, `prewalk` pain) and then pivots to
ClojureScript's native idioms for nested data. The language change
wasn't a pivot of the thesis; it was a pivot of the *medium* after
TS's nominal/structural tension kept leaking into tree code.

Three specific ergonomic wins the CLJS jump unlocks:

- **Immutable-by-default data.** flowread's store reassigns
  Maps/Sets to trigger Svelte reactivity (`this.activeNodes =
  newActiveNodes`). In CLJS, immutability is free; derivations
  recompute naturally.
- **Data-shaped ops are just maps.** `{:op :place :id "a" :under
  :doc :at :last}` is a value, serializable, printable at the REPL.
  TS's type gymnastics to get discriminated unions equally ergonomic
  never quite land.
- **Tree walk is `clojure.walk`.** flowread wrote `walk, transform,
  editChildren(local lens)` as bespoke utilities (`b6eb0f2`). CLJS
  has this built-in, then browsing learns the hard way (`prewalk`
  on deep docs overflows) that even idiomatic CLJS tree walking
  breaks under scale — which is what forces the normalized-flat
  model in `14823ac`.

### Not in the main chain, adjacent in time

These repos overlap calendar-wise with the ancestry but aren't
load-bearing on the kernel idea:

- **`reify`** (2025-01-23, TS) — despite the promising name, the
  README is titled "Event Memory (em) CLI": a SQLite + vector-search
  event store, not a UI evolver. Sibling infrastructure, not
  ancestor.
- **`mem` / `mem_old` / `mem_old2`** (2025-01-30 → 2025-08-07) —
  "Knowledge Operating System (KOS) … differentiable mind
  simulator." AI-native, but the axis is *memory / attention
  traces*, not UI. Separate thread.
- **`srsui`** (2025-01-29, JS + Svelte adapter) — SRS UI
  experiment. No event-sourcing, no tree ops.
- **`dl`** (2025-08-12, Svelte) — postdates browsing.
- **`bun-svelte`** (2025-03-23, TS) — scaffolding, no editor
  content.
- **`uis`** (2024-05-13, TS) — React+Vite template.
- **`anywidget-svelte/` / `anywidget-repl/`** (2025-08-10) —
  different stack, Jupyter/anywidget thread.
- **`clipper/`, `capture/`** — capture/scraper tools, not editors.
- **`scripting/`** (2025-09-18) — Nushell; no UI content.

### Candidates ruled out earlier (retained for completeness)

- `chats/` (2025-10-27) — postdates evo, spawned from
  `evo-template` (downstream, not upstream).
- `demo-app/` — template derived from evo, no pre-evo history.
- `specs/` (2025-12-06) — near-duplicates of evo's own docs.
- `best/logseq/` (cloned 2025-12-09) — study artifact, not ancestor.
- Domain-unrelated: `parsers/`, `synthoria.bio/`, `phys-*`,
  `markus_*`, `sean/`, `intel/`, `meta/`, `old_anki_cli/`, etc.

---

## Part 8.5 — How to inspect the TS/Svelte chain

None of savant/synth/synthoric are cloned by default. They live on
GitHub; one-shot inspection:

```bash
mkdir -p ~/Projects/_ancestry && cd ~/Projects/_ancestry
gh repo clone markusstrasser/savant
gh repo clone markusstrasser/synth
gh repo clone markusstrasser/synthoric

# flowread is already local at ~/Projects/flowread

# Key commits to read
git -C savant       log --reverse --oneline | head -30
git -C synth        log --reverse --oneline | grep -iE 'generative|ui|xstate|interact'
git -C synthoric    log --reverse --oneline | grep -iE 'generate|dynamic|builder|orchestrator'
git -C ~/Projects/flowread log --reverse --oneline | grep -iE 'event|unified|schema|tree|dispatch'

# The "generative UI" API endpoint in synthoric
less synthoric/src/routes/api/generateComponent/+server.ts

# The unified route in flowread — closest pre-browsing shape
ls  ~/Projects/flowread/src/routes/unified/
less ~/Projects/flowread/src/routes/unified/schema/documentStore.svelte.ts
```

If disk pressure ever requires freeing these, single-file bundles
preserve full history:

```bash
for r in savant synth synthoric; do
  git -C ~/Projects/_ancestry/$r bundle create \
    ~/Projects/evo/docs/ancestry/$r.bundle --all
done
git -C ~/Projects/flowread bundle create \
  ~/Projects/evo/docs/ancestry/flowread.bundle --all
```

No bundles have been created; all four live at their original paths
(or on GitHub).

---

## Part 9 — Conceptual influences (not code ancestors)

Things that shaped evo's thinking without contributing code:

- **Logseq.** The structural-editing behavior evo targets is
  Logseq's, reverse-engineered into `docs/LOGSEQ_SPEC.md`,
  `docs/LOGSEQ_BEHAVIOR_TRIADS.md`, and `docs/LOGSEQ_UI_FEATURES.md`.
  The reference clone at `~/Projects/best/logseq/` was made
  2025-12-09 — after evo was already underway, so it's a study
  artifact, not a design input. The conceptual influence is real;
  the chronological "ancestry" framing doesn't apply.
- **Figma's data model.** Cited in browsing's `agent-readme.md`
  as the inspiration for "flat node DB + parent index." Present in
  evo via the `:children-by-parent` + `:parent-of` structure.
- **Observable reactive graph.** Same citation. Present in evo as
  the derived-index recomputation after every transaction.
- **LSP-style protocol.** Same citation. Present in evo as
  "intents are EDN, mutations are data, everything is
  serializable."
- **Replicant** (`cjohansen/replicant`). The CLJS rendering library
  both browsing and evo sit on top of. Not an ancestor in the
  genealogical sense; it's the runtime substrate.

---

## Part 10 — How to inspect or preserve browsing

Browsing is still on disk at `~/Projects/browsing/`. Nothing special
required to read it:

```bash
cd ~/Projects/browsing

# The arc, oldest first
git log --all --reverse --oneline

# The crystallization commit
git show 14823ac

# The 131-line ancestor kernel
less src/kernel.cljc

# The archived zipper prototype (what the crystallization repudiated)
less src/prototypes/zippers.clj

# The archived hypergraph sketch (the road not taken)
less src/prototypes/hypergraph.clj
```

Dead / sibling branches worth peeking at:

```bash
git branch -a
git checkout jsx           # the active JSXGraph + Python thread
git checkout newschema     # the March 2025 pivot branch
```

If the working directory ever needs to be freed, a single-file
bundle preserves the full history including every branch and tag:

```bash
git -C ~/Projects/browsing bundle create \
  ~/Projects/evo/docs/ancestry/browsing.bundle --all

# Later, to restore:
git clone ~/Projects/evo/docs/ancestry/browsing.bundle restored/
```

No bundle has been created; browsing lives at its original path.

---

## Why this file exists

Git can show you a file's history inside one repo. It can't show
you the *idea's* history across repos, and it can't narrate the
architectural crystallization that happens between commits. The
leap from "AI-driven STEM platform with generative UI"
(synth, May 2024) → "unified store + curried tree ops in Svelte"
(flowread, Feb 2025) → "unified-ops multimethod on a flat
normalized graph" (browsing, Aug 2025) → "three-op kernel with a
transaction pipeline, a query layer, a defintent macro, an FR
registry, and a session atom" (evo, Apr 2026) is two years of
crystallization that's invisible in any single `git log` but
load-bearing for anyone asking *why* the kernel looks the way it
does.

This file is the audit trail for that leap — the TS/Svelte idea
lineage (Part 8), the prologue that happened in `browsing/`
(Part 1), and the six-phase internal evolution inside evo itself
(Part 2 onward).
