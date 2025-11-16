# Logseq Parity Regressions Spec

**Status:** Draft for implementation  
**Date:** 2025-11-15  
**Owner:** Codex (editing / navigation parity)

## 1. Background & Motivation

Logseq parity work (see `docs/specs/logseq_behaviors.md`) claims that foundational editing, navigation, and selection behaviors now match Logseq. Manual verification in Evo build `main@HEAD` (2025-11-15) surfaces four mismatches that end users notice immediately:

1. Arrow navigation spills into hidden pages/folded trees, unlike Logseq’s page-scoped, visibility-aware traversal.
2. Horizontal boundary navigation (Left/Right at block edges) only walks siblings, so it gets stuck at parent boundaries and never dives into children.
3. Shift+Click (range selection) includes folded/off-page blocks because it trusts `doc-range` instead of the visible DOM tree.
4. While editing, Shift+Arrow at the block boundary starts extending from the top of the page because selection state is empty—Logseq seeds the current block before extending.

These gaps break the “feel identical to Logseq” goal and contradict `docs/specs/logseq_behaviors.md`. This spec defines the required fixes, acceptance criteria, and testing obligations.

## 2. Goals

- Keep navigation and selection strictly within the rendered outline (current page or zoom root, respecting fold state).
- Make boundary navigation (Left/Right) mirror Logseq’s DOM-order traversal, including parent/child transitions.
- Ensure range selection initiated by Shift+Click only touches visible blocks under the current context.
- Guarantee that Shift+Arrow at the boundary, while editing, extends the selection starting from the editing block rather than page extremes.

## 3. Non-Goals

- Adding new shortcuts (PageUp/PageDown, kill commands) beyond parity scope.
- Reworking Replicant/Nexus architecture—only behavior-level changes are required.
- Sidebar/graph view parity (out of scope for this spec).

## 4. Current vs Desired Behavior

### 4.1 Navigation Scope Isolation

| Aspect | Logseq | Evo Current | Impact |
|--------|--------|-------------|--------|
| Source of “visible blocks” | Uses page/zoom DOM tree (same as renderer) | `kernel/query/visible-blocks-in-dom-order` starts from `:doc` whenever `:zoom-root` is nil and never consults `:current-page` | Arrow Up/Down or Shift+Arrow from the last block of a page jumps into hidden pages; caret vanishes |

**Implementation references**
- Renderer only mounts `current-page-id` children (`src/shell/blocks_ui.cljs:343-354`).
- Navigation + selection reuse `visible-blocks-in-dom-order` (`src/plugins/navigation.cljc:42-62`, `src/plugins/selection.cljc:63-145`).
- When not zoomed, `visible-blocks-in-dom-order` falls back to children of `:doc` (`src/kernel/query.cljc:193-206`).

**Requirement**
- Treat an active page as an implicit zoom root. While `current-page` is set, DOM-order helpers must start traversal at that page’s block tree and ignore siblings outside it.
- Fold state filtering must still apply.

### 4.2 Horizontal Boundary Traversal

| Scenario | Logseq | Evo Current | Impact |
|----------|--------|-------------|--------|
| Left at start of first child | Jumps to parent block at the end | No-op (no previous sibling) | Users cannot navigate to parent via Left/Right |
| Right at end of parent | Enters first visible child at start | Stays on parent unless there is a next sibling | Nested editing feels broken |

**Implementation references**
- `components/block.cljs` calls `{:type :navigate-to-adjacent ...}` when hitting boundaries (`lines ~210-240`).
- `:navigate-to-adjacent` resolves adjacency via `:prev-id-of`/`:next-id-of` (sibling order only) (`src/plugins/navigation.cljc:249-275`).
- Derived indexes in `kernel/db.cljc:45-51` only capture sibling relationships.

**Requirement**
- Replace sibling-only lookups with the same DOM-order traversal used for vertical navigation (respecting visibility + zoom). Left should target the previous visible block; Right should target the next visible block.
- Preserve cursor position semantics (`:cursor-position :max` vs `0`).

### 4.3 Shift+Click Range Selection

| Step | Logseq | Evo Current | Impact |
|------|--------|-------------|--------|
| Shift+Click between folded nodes | Skips folded descendants; selection only spans visible nodes | Uses `tree/doc-range` between anchor and focus → includes folded children, blocks on other pages | Structural ops act on hidden blocks; user surprises |

**Implementation references**
- Shift+Click path: block container click -> `:selection :mode :extend` (`src/components/block.cljs:573-577`).
- Reducer path: `calc-extend-props` uses `tree/doc-range` (`src/plugins/selection.cljc:32-45`).
- `tree/doc-range` ignores fold state and page context (`src/kernel/query.cljc:208-219`).

**Requirement**
- Introduce a visibility-aware range helper (e.g., `visible-range db anchor target`) that walks `visible-blocks-in-dom-order` and stops at the clicked block.
- Replace the `doc-range` shortcut with the new helper; keep behavior for mouse selections consistent with keyboard selections.

### 4.4 Shift+Arrow Anchoring in Editing Mode

| Step | Logseq | Evo Current | Impact |
|------|--------|-------------|--------|
| Start editing block, hit Shift+ArrowUp at first row | Anchor = current block, selection extends to previous visible block | Selection state is empty because `:enter-edit` cleared it; reducer seeds anchor with first block on page | First Shift+Arrow jumps to page top instead of adjacent block |

**Implementation references**
- Enter edit clears selection (`src/plugins/editing.cljc:20-34`).
- Shift+Arrow handler dispatches `{:type :selection :mode :extend-prev/next}` directly (`src/components/block.cljs:169-201`).
- Reducer’s extend path expects existing `:focus`/`:anchor` (`src/plugins/selection.cljc:124-185`).

**Requirement**
- Before dispatching `:extend-*`, ensure selection state includes the editing block (e.g., dispatch `{:type :selection :mode :replace :ids block-id}` once per entry into editing boundary mode, or reuse focus data from `session/ui`).
- Avoid flicker: don’t leave blocks selected after editing; selection should be cleared again when staying inside the same block.

## 5. Functional Requirements

1. **Context-bound traversal**
   - `visible-blocks-in-dom-order` must accept an explicit `root-id`. Default order: `zoom-root` → `current-page` → `:doc`.
   - Both navigation and selection call sites pass the same root used by the renderer.

2. **DOM-order adjacency for Left/Right**
   - Introduce helpers `prev-visible-block` / `next-visible-block` that mirror the vertical navigation ones and reuse visibility checks.
   - Update `:navigate-to-adjacent` to call those helpers instead of sibling indexes.

3. **Visibility-aware range selection**
   - Add `visible-range db root anchor focus` returning a vector/set of block IDs encountered between anchor and focus, inclusive, in DOM order filtered by visibility.
   - `calc-extend-props` uses this helper when `single-id?`.

4. **Editing-mode Shift+Arrow seeding**
   - When `handle-shift-arrow-*` detects a boundary transition, ensure the current block is in selection state before issuing extend intents. Option A: dispatch a compound Nexus action `[[:selection/seed-anchor {:block-id ...}] [:selection/extend-prev ...]]`. Option B: send a map intent to replace selection followed by extend.
   - Ensure selection is cleared again once the user re-enters editing mode without extending.

## 6. Acceptance Criteria

1. **Navigation stays inside page**
   - Manual: On the Projects page, Arrow Down from the last block should no-op instead of jumping to Tasks.
   - Automated: Add Playwright test “arrow-down-at-page-end-stays-put”.

2. **Left/Right traverse parent + children**
   - Manual: Type in a child block, press Home then Left → caret moves to parent at end.
   - Manual: From parent end, press Right → caret enters first child at start.
   - Playwright: extend `foundational-editing-parity.spec.ts` with these assertions.

3. **Shift+Click ignores folded nodes**
   - Manual: Fold a block, Shift+Click below it → selection count excludes folded descendants.
   - Automated: Add unit test for `calc-extend-props` and a Playwright case for folded tree selection.

4. **Shift+Arrow while editing anchors correctly**
   - Manual: Edit block B, Shift+ArrowDown at last row → selection = {B, next visible}.
   - Playwright: scenario covering extend + contract within editing mode.

## 7. Implementation Plan

1. **API updates**
   - Update `visible-blocks-in-dom-order` signature to accept optional `{:root-id ...}`; default remains for zero callsites.
   - Provide a helper `(q/active-outline-root db)` returning zoom root or current page.

2. **Navigation plugin**
   - Refactor DOM traversal helpers to reuse the updated query.
   - Update `:navigate-to-adjacent` to call DOM-order helpers.

3. **Selection plugin**
   - Add visibility-aware range helper.
   - Wire `calc-extend-props` to new helper.

4. **Block component / Nexus**
   - Introduce `:selection/seed-anchor` Nexus action (or inline intent) executed before extend when editing boundary hit and no focus is recorded.
   - Ensure seeding is idempotent (skip if focus already equals block-id).

5. **Tests**
   - Unit tests: `test/plugins/selection_direction_tracking_test.cljc` (new) for `visible-range` and extend logic.
   - Integration/E2E: extend `test/e2e/foundational-editing-parity.spec.ts` with scenarios from §6.

6. **Docs**
   - Update `docs/specs/logseq_behaviors.md` after fixes land.
   - Append “page vs zoom” guidance to `docs/RENDERING_AND_DISPATCH.md` and `dev/specs/LOGSEQ_EDITING_SELECTION_PARITY.md`.

## 8. Risks & Mitigations

- **Performance:** DOM-order traversal now filters by page/zoom each time. Mitigate by caching active-root children or passing the root into helper calls to avoid repeated lookups.
- **Selection regressions:** Changing range logic affects mouse + keyboard flows. Cover with unit tests for contiguous/non-contiguous selections.
- **State churn:** Seeding selection during editing could leave stray highlights. Ensure cleanup happens on blur/exit-edit.

## 9. Open Questions

1. Should page nodes themselves ever be selectable/navigable? (Logseq keeps page title outside block list.) Default assumption: no, but confirm with product/design.
2. When zoomed into a block that lives on another page, should switching pages reset zoom stack? (Out of scope but note interaction.)

---

**Next Steps**: Engineering to implement per plan above, then QA to re-run parity checklist plus new tests.
