# Goals

> Human-owned. Agents may propose changes but must not modify without explicit approval.
> Last revised: 2026-04-22

## Mission

A solid outliner with a clean, data-driven extension surface. Kernel stays pure so the code is readable and agents can patch by emitting intents. That's the whole thing.

## Context: why not PKM?

The PKM thesis — that structured note-taking makes you think better — has largely failed:

- **Casey Newton**, "Why note-taking apps don't make us smarter" (Platformer, Aug 2023): Roam's promise "fizzled completely." Software is up against a stronger foe — infinite internet distractions.
- **Andy Matuschak**: "The goal is not to take notes — the goal is to think effectively."
- **Justin Murphy**: Knowledge graphs are "combinatorial explosion after two weeks." Real writing requires "brute linear willfulness."
- **Market signal**: Roam stalled ~500K users; category is fragmenting, not consolidating.

The deeper issue: outliners trap you in a single representation. You switch apps because you need to switch *how* you think. AI is more expressive — you speak, write prose, give prompts — and synthesizes without imposing structure.

Evo is not a PKM tool. It's a well-specified outliner whose kernel is small enough to read end-to-end, and whose data-driven dispatch lets agents patch the tree by emitting intents.

## Strategy

Keep the kernel pure. Keep the extension surface narrow (three registries + session atom). Fix real bugs. Decline feature work that requires new concepts instead of registering new handlers.

If someone shows up wanting to reuse the kernel in an outliner-shaped project, make it easy to extract then. Not before.

## Principles

- **Kernel purity.** Zero imports from `shell/`, `components/`, `keymap/` in `src/kernel/`.
- **Three-op invariant.** DB mutations reduce to `create-node`, `place`, `update-node`.
- **Data-driven dispatch.** Intents are EDN maps. Handlers register into data registries, not multimethod-as-main-dispatch (multimethods fine for local sub-dispatch, e.g. autocomplete).
- **Session state separate from DB.** Ephemeral UI state lives in the session atom, not polluting the persistent doc.
- **Docs are facts.** Delete executed plans, stale proposals, session artifacts. Git preserves history.
- **Domains don't abstract.** Text, video, audio, CAD share architectural *principles* but not *primitives*. Don't build toward universality — it's a mirage.

## Deferred scope

- Universal adapter shells, LLVM/MLIR-of-UI thesis — see Principles above.
- Packaged library distribution — no Clojars, no API stability, no versioning until a concrete consumer triggers it.
- New Logseq feature parity — not a goal; close enough that Logseq users feel at home.
- Daily PKM use — not happening.
- Trace-recording infrastructure — premature without a concrete consumer; a `spit` call covers current debugging needs.
- AI-substrate "bets" (preference compiler, replayable datasets, UI synthesis via e-graphs) — interesting, not current goals.

## Success metrics

| Metric | Target | Horizon |
|---|---|---|
| Kernel purity | Zero imports from shell/components/keymap | Ongoing |
| Extension cleanliness | Adding a new inline format = one file that registers into parser + render | After the render-registry refactor |
| Docs are facts not plans | No executed plans in working tree | Ongoing |

## Resource constraints

- Back burner relative to other projects.
- Bursts of focused work, not steady allocation.
- AI agents do most of the implementation; human provides taste, direction, requirements.

## Exit conditions

- If the codebase stops being useful as a reference or tool, evo becomes an archived project.
- If AI-generated UIs become the user's focus, evo might become the specific testbed — but that's speculation, not a committed pivot.
