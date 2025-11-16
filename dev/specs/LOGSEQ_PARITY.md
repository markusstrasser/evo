# LOGSEQ_PARITY.md — Evo vs Logseq Gap Tracker

This tracker maps outstanding gaps to the Functional Requirements (FR) defined in `dev/specs/LOGSEQ_SPEC.md` and the PRD (`dev/specs/LOGSEQ_PARITY_PRD.md`). Keep it current: when a gap is fixed, move it to the Resolved appendix with references to commits/tests.

| Gap ID | FR ID(s) | Area | Status in `src/` | Impact | Fix/Test Outline |
|--------|----------|------|------------------|--------|------------------|
| G-Idle-01 | FR-Idle-01..03 | Idle-state guard | **??** – need audit | Accidental block creation/deletion when idle. | Ensure global key handler no-ops Enter/Backspace/Tab in true idle state; add Playwright regression. |
| G-Scope-01 | FR-Scope-01..03 | Visible outline boundaries | Partially fixed in parity/navigation-selection-fixes | Arrow navigation now scoped but structural ops (outdent at zoom root, shift+arrow across zoom) still need guards. | Audit commands (`:indent-selected`, `:move-selected-up/down`, `:selection :extend`) to enforce zoom root; add unit + e2e tests. |
| G-Clipboard-02 | FR-Clipboard-01..03 | Paste semantics | Unknown | Paste likely uses doc-range; multi-paragraph paste may not split like Logseq. | Implement blank-line detection in paste handler; add unit tests referencing `handler/paste.cljs`. |
| G-Pointer-01 | FR-Pointer-01..02 | Alt+Click toggle / hover preview | Not implemented | Missing Alt+Click full-tree toggle + hover popovers. | Add bullet listener for Alt+Click; implement popover component; add UI tests. |
| G-Slash-01 | FR-Slash-01 | Slash palette behavior | Not implemented | `/` command palette absent or incomplete. | Implement inline command palette mirroring Logseq’s filtering/navigation. |
| G-QuickSwitch-01 | FR-QuickSwitch-01 | Quick switcher | Not implemented | Cmd+K/Cmd+P overlay missing; users can’t jump quickly. | Add quick switcher UI + keyboard plumbing. |
| G-Undo-01 | FR-Undo-01 | Undo/redo focus memory | Requires verification | Need tests proving caret/selection state restores with undo/redo. | Instrument undo/redo ops; add tests ensuring editing block and caret position restored. |

> Update this table whenever a new gap is discovered or closed. Use FR IDs for traceability and cite commit hashes / test names in the “Fix/Test Outline” once resolved.

## Resolved Gaps

| Gap ID | FR ID(s) | Summary | Evidence |
|--------|----------|---------|----------|
| G-Nav-01 | FR-NavEdit-01..04 | Horizontal/vertical navigation scope + Shift+Arrow seeding | Commit `<hash>` (parity/navigation-selection-fixes), tests `foundational-editing-parity.spec.ts` |
