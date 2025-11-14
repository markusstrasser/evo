# Plugin Architecture Parity (Thin vs Thick)

**Status:** Draft – next implementation target

**Date:** 2025-11-14

**Background:** Evo's plugins fell into two camps:
- **Thin/rotator plugins** (e.g., `plugins.struct/:move-selected-up`) – pure graph re-ordering, unaware of UI semantics.
- **Thick/UI plugins** (e.g., smart editing, selection) – attempt to mirror Logseq's intent logic.

The mix leads to parity gaps: thin plugins miss Logseq's contextual rules, while thick plugins sometimes omit multi-step behaviors. This spec defines the next set of parity fixes.

---

## 1. Move Up/Down “Climb” Semantics

**Current:** `:move-selected-up` no-ops when the block is the first child.

**Logseq:** `Mod+Shift+Up` on a first child “climbs” the block out, making it the previous sibling of its parent. Likewise, `Mod+Shift+Down` on the last child pushes it under the next sibling.

**Plan:**
1. Extend `plugins.struct/move-selected-up-ops` and `move-selected-down-ops` to detect boundary cases.
2. When no previous sibling exists:
   - Re-parent the block under the parent's parent (same level as parent).
   - Place it immediately before the parent (or after, for downwards climb).
   - Preserve descendants.
3. When no next sibling exists (for down), push into the next visible ancestor.
4. Update multi-select handling: climbing should operate on the entire selection.

**Tests:**
- Unit tests covering first-child climb, last-child descend, multi-selection.
- Playwright test: `Mod+Shift+Up` on first child results in block at parent level.

---

## 2. Enter on Empty List Item (One-Step Unformat + Split)

**Current:** `:context-aware-enter` unformats an empty list but leaves the cursor in the same block; user must press Enter again to create a peer.

**Logseq:** Single Enter both unformats AND inserts a new empty peer at the parent level.

**Plan:**
1. In `plugins.smart_editing/:context-aware-enter`, when `:list-item` context is empty:
   - Emit ops to remove the list marker AND create a sibling block after the parent (or at current level if already top-level).
   - Position cursor in the new empty block (start of block).
2. Ensure indent/outdent state is preserved (e.g., keep parent selection).

**Tests:**
- Unit test verifying ops include `:update-node` (empty text) + `:create-node` + `:place` + cursor hint.
- Playwright scenario: typing `- `, Enter, yields an empty peer block with cursor ready for typing.

---

## 3. Drag & Drop Parity (Alt = Block Ref)

**Current:** No drag-and-drop support.

**Logseq:** Dragging blocks with mouse; holding `Alt` during drop inserts block references instead of moving them.

**Plan (future milestone):**
1. Implement Replicant-based DnD handles on blocks (mouse listeners, hover state).
2. Dispatch Nexus actions for DnD start/hover/drop.
3. When `altKey` is true on drop, emit ops to insert `((block-id))` references instead of `:place` moves.
4. Provide keyboard alternative (if needed) for parity.

**Tests:**
- Manual/E2E drag scenarios (needs Playwright’s mouse API).
- Unit tests for DnD action reducers.

---

## 4. Implementation Notes

- All new events must flow through the Nexus dispatcher (see archived `NEXUS_ACTION_PIPELINE_AND_TESTING.md`).
- Update docs/CLAUDE guidance once move/enter behaviors land.

**Acceptance Criteria:**
1. Move up/down climb behaviors match Logseq for single and multi selection.
2. Enter on empty list item creates a peer in one keystroke.
3. DnD parity is documented and tracked (implementation can span multiple PRs).

