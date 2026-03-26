# Evo Invariants

These constraints MUST be maintained at all times, including after compaction.

## Kernel Purity

1. **Zero UI dependencies in kernel.** Every import from `shell/`, `components/`, or `keymap/` in `src/kernel/` is a bug.
2. **Three-op invariant.** All state changes reduce to `create-node`, `place`, `update-node`. No new primitive operations.
3. **No protocols in kernel.** Just pure functions.
4. **No async in core.** Event sourcing is synchronous.
5. **Canonical DB shape owned by kernel.** Adapters at edges only.
6. **Tests travel with the kernel.** `test/kernel/` and `test/scripts/` must work without shell, view, or component dependencies.

## Project Mode: Extraction

Evo is in extraction mode. Agent work should trend toward:
- Separating kernel from shell/UI concerns
- Cleaning the kernel API surface
- Ensuring property tests and specs are self-contained

Do NOT: add new outliner features, chase Logseq parity, or build speculative infrastructure.

## Protected Files (require human approval)

- `resources/specs.edn` — FR registry (44 FRs)
- `resources/failure_modes.edn` — known bugs/anti-patterns
- Constitution and GOALS sections in CLAUDE.md
- Golden test fixtures (`tests/fixtures/golden_*`)

## Quality Gates

Before committing: `bb check` (lint + compile). Before pushing: tests pass (`bb test`).
