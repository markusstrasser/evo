# LOGSEQ_PARITY.md — Evo vs Logseq Gap Tracker

This tracker maps outstanding gaps to the Functional Requirements (FR) defined in the canonical spec (`dev/specs/LOGSEQ_SPEC.md`). Implementation guardrails and testing expectations live in `docs/specs/LOGSEQ_PARITY_EVO.md`. Keep this table current: when a gap is fixed, move it to the Resolved appendix with references to commits/tests (and update `docs/specs/logseq_behaviors.md` + relevant triad rows).

| Gap ID | FR ID(s) | Area | Status in `src/` | Impact | Fix/Test Outline |
|--------|----------|------|------------------|--------|------------------|
| G-Idle-01 | FR-Idle-01..03 | Idle-state guard | **COMPLETE** ✅ | Idle state guard implemented in earlier work | Commit: foundational-editing-parity fixes |
| G-Scope-01 | FR-Scope-01..03 | Visible outline boundaries | **DEFERRED** (see `dev/specs/later/ZOOM_SCOPE_BOUNDARIES_SPEC.md`) | Navigation already scoped; structural ops require functional zoom feature first. Blocked: zoom keyboard shortcuts don't work in blocks-ui shell. | Prerequisites: fix zoom-in/out (Cmd+.) in shell, ensure view layer respects zoom-root. Then add boundary guards to struct ops. Full spec + tests ready in deferred doc. |
| G-Clipboard-02 | FR-Clipboard-01..03 | Paste semantics | **IN PROGRESS** | Multi-paragraph splitting with list marker preservation | Commit 624f20c: plugins/clipboard.cljc with :paste-text intent. Tests failing, needs clipboard API fixes. |
| G-Pointer-01a | FR-Pointer-01 | Alt+Click subtree toggle | **DEFERRED** (see `dev/specs/later/alt-click-subtree.spec.ts`) | Nice-to-have feature, lower priority than core editing | Tests moved to later/ directory. Implementation in plugins/folding.cljc can be completed after core features. |
| G-Pointer-01b | FR-Pointer-02 | Block ref hover/Cmd+Click | Not implemented | Missing hover popovers + sidebar opening | Implement popover component; add UI tests. |
| G-Slash-01 | FR-Slash-01 | Slash palette behavior | Not implemented | `/` command palette absent or incomplete. | Implement inline command palette mirroring Logseq's filtering/navigation. |
| G-QuickSwitch-01 | FR-QuickSwitch-01 | Quick switcher | Not implemented | Cmd+K/Cmd+P overlay missing; users can't jump quickly. | Add quick switcher UI + keyboard plumbing. |
| G-Undo-01 | FR-Undo-01 | Undo/redo focus memory | **IN PROGRESS** | Cursor position and editing block restored on undo/redo | Commit 9d62e05: kernel/history.cljc preserves session/ui state. Tests failing, needs cursor restoration fixes. |

> Update this table whenever a new gap is discovered or closed. Use FR IDs for traceability and cite commit hashes / test names in the “Fix/Test Outline” once resolved.

## Resolved Gaps

| Gap ID | FR ID(s) | Summary | Evidence |
|--------|----------|---------|----------|
| G-Nav-01 | FR-NavEdit-01..04 | Horizontal/vertical navigation scope + Shift+Arrow seeding | Earlier commits, tests `foundational-editing-parity.spec.ts` |
| G-Nav-01 | FR-NavEdit-01..04 | Horizontal/vertical navigation with editing transitions | Earlier foundational commits |
