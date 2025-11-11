# LOGSEQ_PARITY.md — Evo vs Logseq Gap Tracker

This document enumerates the remaining deviations between Evo (`src/`) and the canonical Logseq behavior defined in `LOGSEQ_SPEC.md`. Items are ordered by severity. Once a gap is closed, move it to the “Resolved” appendix with a short commit / PR reference.

| ID | Area | Spec Reference | Status in `src/` | Impact | Fix Outline |
|----|------|----------------|------------------|--------|-------------|
| P2-004 | Documentation sync | Whole spec | Older specs (`LOGSEQ-BLOCK-NAV-TEXT-SPEC.md`, `LOGSEQ-PARITY-BLOCK-TEXT.md`, `claude-logseq-parity-deep-dive.md`) contradicted each other and the current code. | Confuses contributors / agents. | ✅ Consolidated into `LOGSEQ_SPEC.md` (this task completed with latest update). |

**All known gaps resolved as of 2025-11-11.**

## Resolved / Historical Notes

- **P0-001: Shift+Arrow while editing** — `src/shell/blocks_ui.cljs` now skips global Shift+Arrow bindings when `editing?` is true, allowing block component to handle text selection. Added Playwright tests in `test/e2e/editing-parity.spec.js` verifying text selection within blocks and Escape clearing. (Commit 2025-11-11)
- **P1-002: Selection boundary test typo** — Fixed `:idss` → `:ids` in `test/core/selection_edit_boundary_test.cljc`. All 259 tests pass. (Commit 2025-11-11)
- **P1-003: Playwright coverage** — Added comprehensive Shift+Arrow text selection tests and Escape behavior tests to `test/e2e/editing-parity.spec.js`. (Commit 2025-11-11)
- **Cursor hint clearing** — `components/block.cljs` now calls `:clear-cursor-position` immediately after applying hints, matching `LOGSEQ_SPEC` §6. (See commit 2025-11-11.)
- **Logical outdenting semantics** — `plugins/struct.cljc` implements non-kidnapping behavior (`outdent-ops`), aligning with `LOGSEQ_SPEC` §5.1.
- **Backspace merge child preservation** — `plugins/editing.cljc` re-parents children before moving the emptied node to trash (§4 Editing Actions).

Keep this table authoritative. When Evo matches Logseq for an item, move it to the resolved list with evidence (test names, commit hashes, or manual verification steps).
