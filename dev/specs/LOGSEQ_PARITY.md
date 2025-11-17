# LOGSEQ_PARITY.md — Evo vs Logseq Gap Tracker

This tracker maps outstanding gaps to the Functional Requirements (FR) defined in the canonical spec (`dev/specs/LOGSEQ_SPEC.md`). Implementation guardrails and testing expectations live in `docs/specs/LOGSEQ_PARITY_EVO.md`. Keep this table current: when a gap is fixed, move it to the Resolved appendix with references to commits/tests (and update `docs/specs/logseq_behaviors.md` + relevant triad rows).

| Gap ID | FR ID(s) | Area | Status in `src/` | Impact | Fix/Test Outline |
|--------|----------|------|------------------|--------|------------------|
| G-Idle-01 | FR-Idle-01..03 | Idle-state guard | **COMPLETE** ✅ | Idle guard implemented in foundational parity work. | Commit: foundational-editing-parity fixes. |
| G-Scope-01 | FR-Scope-01..03 | Visible outline boundaries | **DEFERRED** (see `dev/specs/later/ZOOM_SCOPE_BOUNDARIES_SPEC.md`) | Structural ops ignore zoom roots; zoom shortcuts still broken in shell. | Prereqs: wire Cmd+./Cmd+, in shell, then honor zoom root in struct intents per deferred spec. |
| G-Nav-Visibility | FR-Scope-01..03 | Visible-outline traversal | **COMPLETE** ✅ | ArrowUp/Down navigation respects folded blocks, zoom roots, and current page boundaries. | Already implemented: `kernel/query.cljc::visible-blocks-in-dom-order` used throughout `plugins/selection.cljc` and `plugins/navigation.cljc`. Pre-order traversal with fold/zoom awareness. |
| G-Horizontal-DOM | FR-NavEdit-02 | Horizontal boundary navigation | **COMPLETE** ✅ | ArrowLeft at col 0 and ArrowRight at end now use DOM-order traversal to climb to parents or descend into children. | Already implemented: `plugins/navigation.cljc::navigate-to-adjacent` uses `prev-block-dom-order` and `next-block-dom-order` for pre-order DOM traversal. |
| G-ShiftClick-Visibility | FR-NavView-02 | Shift+Click range selection | **OPEN** | Shift+Click still consults `doc-range`, so folded/hidden/out-of-page blocks sneak into selections. | Swap in the visibility-aware helper for `calc-extend-props`; add view + E2E coverage for folded cases. |
| G-ShiftArrow-Seeding | FR-NavEdit-04 | Shift+Arrow (editing boundary) seed | **COMPLETE** ✅ | Shift+Arrow at boundaries now properly extends selection incrementally. | Fixed: `src/shell/nexus.cljs:125-141` - Changed `:mode :extend` to `:mode :extend-prev/:extend-next`. Commit: cursor-behavior-fixes. |
| G-Clipboard-02 | FR-Clipboard-01..03 | Paste semantics | **IN PROGRESS** | Multi-paragraph paste should split blocks, keep list markers, respect custom MIME payloads, and detect whiteboard TLDR blobs. Current handler pastes everything into one block. | Finish `plugins/clipboard.cljc/:paste-text` + `handler/paste.cljs` parity; add tests for blank-line splitting and `web application/logseq` payloads. |
| G-Pointer-01a | FR-Pointer-01 | Bullet/pointer collapse parity | **DEFERRED** (see `dev/specs/later/alt-click-subtree.spec.ts`) | Bullet control still lacks Logseq's subtree toggle parity; pointer chords are blocked on upstream folding support in shell. | Implement spec once folding UI lands; tests live in `dev/specs/later/`. |
| G-Pointer-01b | FR-Pointer-02 | Block ref hover preview | **NOT STARTED** | Hovering block references shows nothing, Cmd/Shift clicks do not open sidebars like Logseq. | Build popover + sidebar wiring; add Playwright hover tests. |
| G-Slash-01 | FR-Slash-01 | Slash palette behavior | **COMPLETE** ✅ | `/` now opens an inline command palette with real-time filtering and keyboard navigation. | Implemented: `plugins/slash_commands.cljc` + `components/slash_menu.cljs` with trigger detection, fuzzy filtering, and 13 commands (TODO markers, formatting, embeds). Tests needed for multi-step forms. |
| G-QuickSwitch-01 | FR-QuickSwitch-01 | Quick switcher / command palette | **COMPLETE** ✅ | Cmd+K and Cmd+P now open quick switcher overlay for page/block search with keyboard navigation. | Implemented: `plugins/quick_switcher.cljc` + `components/quick_switcher.cljs` with real-time filtering. Add Playwright coverage for filtering + keyboard nav. |
| G-Undo-01 | FR-Undo-01 | Undo/redo focus memory | **COMPLETE** ✅ | Undo/redo now restores cursor position and editing state. | Already implemented: `kernel/history.cljc::strip-history` preserves `:editing-block-id` and `:cursor-position` in snapshots. |

> Update this table whenever a new gap is discovered or closed. Use FR IDs for traceability and cite commit hashes / test names in the "Fix/Test Outline" once resolved.

## Resolved Gaps

| Gap ID | FR ID(s) | Summary | Evidence |
|--------|----------|---------|----------|
| G-Nav-01 | FR-NavEdit-01..04 | Horizontal/vertical navigation scope + Shift+Arrow seeding | Earlier commits, tests `foundational-editing-parity.spec.ts` |
| G-Nav-01 | FR-NavEdit-01..04 | Horizontal/vertical navigation with editing transitions | Earlier foundational commits |
