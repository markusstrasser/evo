# Goals & Governance

> Human-owned. Agents may propose changes but must not modify without explicit approval.
> Last revised: 2026-04-24 — merged inline Constitution section from CLAUDE.md.

## Mission

A solid outliner with a clean, data-driven extension surface. Kernel stays pure so the code is readable and agents can patch by emitting intents. That's the whole thing.

Structured note-taking is largely a nerd trap.

The deeper issue is that outliners trap you in a single representation. You switch apps because you need to switch modalities. There's no superapps.

Evo is not a PKM tool. It's a well-specified outliner whose kernel is small enough to read end-to-end. The data-driven dispatch lets agents patch the tree by emitting intents.

## Generative Principle

> Minimize the spec surface while maximizing kernel power — legible to both humans and LLMs.

Every design decision, refactoring choice, and documentation edit should be evaluated against: "Does this make the kernel smaller, more correct, or more legible?" If none of the three, don't do it.

## Project Mode: Solid Outliner With Clean Extension Surface

*Updated 2026-04-22. Supersedes earlier "Extraction" and "Reference Implementation + Trace Substrate" framings, which were over-claims.*

Evo is a solid outliner with a clean, data-driven extension surface. Kernel stays pure so the code is readable and agents can patch by emitting intents. That's the whole thing.

Agent work should trend toward:
1. **Kernel purity.** Zero imports from `shell/`/`components/`/`keymap/` in `src/kernel/`.
2. **Clean extension surface.** Three registries (intent, derived, render) + session atom. Adding a feature = registering handlers, not editing core.
3. **Deletion.** Remove dead code, consolidate redundant patterns.
4. **Test portability.** Property tests and specs self-contained with the kernel.

Do NOT: add new outliner features, chase Logseq parity, or build speculative infrastructure (trace recording, replayable datasets, library extraction, universal adapters, LLVM-of-UI IRs). Bug fixes and extension-surface cleanup are welcome.

## Strategy

Keep the kernel pure. Keep the extension surface narrow (three registries + session atom). Fix real bugs. Decline feature work that requires new concepts instead of registering new handlers.

## Principles

1. **Kernel purity over feature breadth.** The kernel must have zero UI dependencies. Every import from `shell/`, `components/`, or `keymap/` in kernel code is a bug.
2. **Three-op invariant.** All state changes reduce to `create-node`, `place`, `update-node`. If a new operation can't be expressed as a composition of these three, the design is wrong.
3. **REPL-verifiable in 30 seconds.** Any kernel behavior must be demonstrable in the REPL with a fixture DB. If it requires a browser to test, it's not kernel — it's shell.
4. **Specs are the product.** `resources/specs.edn`, `docs/STRUCTURAL_EDITING.md`, and the kernel source ARE the publishable artifacts. Keep them precise, correct, and self-contained.
5. **Docs: facts not plans.** Keep documentation that states invariants, specs, and verified behaviors. Delete executed plans, stale proposals, and session artifacts. Git preserves history.
6. **Tests travel with the kernel.** Property tests in `test/kernel/` and `test/scripts/` must work without shell, view, or component dependencies.
7. **Commit freely.** Same auto-commit policy as other projects. Granular semantic commits after every logical change.

## Autonomy Boundaries

**Autonomous (do without asking):**
- Commit after completing a logical change
- Delete dead code, executed plans, stale docs
- Refactor kernel internals for clarity
- Fix bugs in existing code
- Run quality gates (`bb check`, `bb test`)

**Ask first:**
- Adding new features or capabilities
- Changing the three-op kernel API
- Modifying `resources/specs.edn` (the FR registry)
- Modifying this document (`docs/GOALS.md`)
- Modifying `CLAUDE.md` / `AGENTS.md` structural sections
