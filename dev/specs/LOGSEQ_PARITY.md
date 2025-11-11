# LOGSEQ_PARITY.md — Evo vs Logseq Gap Tracker

This document enumerates the remaining deviations between Evo (`src/`) and the canonical Logseq behavior defined in `LOGSEQ_SPEC.md`. Items are ordered by severity. Once a gap is closed, move it to the “Resolved” appendix with a short commit / PR reference.

| ID | Area | Spec Reference | Status in `src/` | Impact | Fix Outline |
|----|------|----------------|------------------|--------|-------------|
| P0-001 | Shift+Arrow while editing | §3 Text Selection (Rule 3) | `handle-global-keydown` resolves global binding `{:key "ArrowDown" :shift true}` even when `q/editing?` is true. Event never reaches editor, so mid-line `Shift+Arrow` exits edit mode and dispatches block-selection intents. | Editing text selections break immediately; violates state machine. | Skip global Shift+Arrow bindings when `q/editing?` is truthy, or allow component handler to cancel the event before keymap dispatch. Add an integration test that types text, presses `Shift+ArrowDown`, and asserts caret remains within block. |
| P1-002 | Selection boundary tests | §3 Text Selection / §1 State Machine | `test/core/selection_edit_boundary_test.cljc` toggle scenario uses `:idss` (typo). Intent validation rejects it before assertions, so we never verify that selection intents leave `session/ui` untouched. | Important invariant silently untested; regressions could reappear unnoticed. | Change to `:ids`, rerun suite. Expand test to cover `Shift+Arrow` editing boundary once P0-001 is fixed. |
| P1-003 | Playwright coverage | §8 Testing Guidance | No end-to-end test ensures browser-level selection behaviors (`Shift+Arrow`, background click clearing) match Logseq. | Manual QA required; high risk of regressions. | Author Playwright spec mirroring Logseq’s `editor_basic_test`: type multiline block, assert `Shift+Arrow` extends text, `Escape` clears selection, etc. |
| P2-004 | Documentation sync | Whole spec | Older specs (`LOGSEQ-BLOCK-NAV-TEXT-SPEC.md`, `LOGSEQ-PARITY-BLOCK-TEXT.md`, `claude-logseq-parity-deep-dive.md`) contradicted each other and the current code. | Confuses contributors / agents. | ✅ Consolidated into `LOGSEQ_SPEC.md` (this task completed with latest update). |

## Resolved / Historical Notes

- **Cursor hint clearing** — `components/block.cljs` now calls `:clear-cursor-position` immediately after applying hints, matching `LOGSEQ_SPEC` §6. (See commit 2025-11-11.)
- **Logical outdenting semantics** — `plugins/struct.cljc` implements non-kidnapping behavior (`outdent-ops`), aligning with `LOGSEQ_SPEC` §5.1.
- **Backspace merge child preservation** — `plugins/editing.cljc` re-parents children before moving the emptied node to trash (§4 Editing Actions).

Keep this table authoritative. When Evo matches Logseq for an item, move it to the resolved list with evidence (test names, commit hashes, or manual verification steps).
