# Evo Invariants

These constraints MUST be maintained at all times, including after compaction.

## Kernel Purity

1. **Zero UI dependencies in kernel.** Every import from `shell/`, `components/`, or `keymap/` in `src/kernel/` is a bug.
2. **Three-op invariant.** All state changes reduce to `create-node`, `place`, `update-node`. No new primitive operations.
3. **No protocols in kernel.** Just pure functions.
4. **No async in core.** Event sourcing is synchronous.
5. **Canonical DB shape owned by kernel.** Adapters at edges only.
6. **Tests travel with the kernel.** `test/kernel/` and `test/scripts/` must work without shell, view, or component dependencies.

## Project Mode: Solid Outliner With Clean Extension Surface

*Updated 2026-04-22. Supersedes earlier "Extraction" and "Reference Implementation" framings.*

Evo is a solid outliner with a clean, data-driven extension surface. See `docs/GOALS.md` and `VISION.md`.

Agent work should trend toward:
- Kernel purity (zero shell/UI imports in `src/kernel/`)
- Clean extension surface (three registries + session atom — see VISION.md)
- Deletion (remove dead code, consolidate redundancy)

Do NOT: add new outliner features, chase Logseq parity, or build speculative infrastructure (trace recording infrastructure, replayable datasets, universal adapter shells, LLVM-of-UI IRs, library extraction on speculation).

## Protected Files (require human approval)

- `resources/specs.edn` — FR registry (44 FRs)
- `resources/failure_modes.edn` — known bugs/anti-patterns
- Constitution and GOALS sections in CLAUDE.md
- Golden test fixtures (`tests/fixtures/golden_*`)

## Quality Gates

Before committing: `bb check` (lint + compile). Before pushing: tests pass (`bb test`).
