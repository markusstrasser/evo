# Evo Logseq Parity Overlay

This document ties the canonical Logseq behavior spec (`docs/LOGSEQ_SPEC.md`) to Evo's implementation reality. Read the canonical spec first for user-facing truth, then use this overlay to understand how Evo mirrors those behaviors, what still gaps out, and which test layers guard each promise.

_Last sync:_ 2025-12-04 (comprehensive editing behavior audit).

## 1. Reference Stack
- **Ground truth:** `docs/LOGSEQ_SPEC.md`
- **Triad scenarios:** `docs/logseq_behaviors.md`
- **Testing philosophy & commands:** `docs/TESTING_STACK.md`
- **Headless + browser tooling:** `docs/PLAYWRIGHT_MCP_TESTING.md`, `docs/TEXT_SELECTION.md`

## 2. Implementation Guardrails

1. **Single dispatcher rule** - Editing-context keys (Enter, ArrowUp/Down, Shift+Arrow) are owned by `src/components/block.cljs`. They dispatch Nexus actions containing DOM facts (cursor rows, selection collapse). Never bind these keys in `keymap/bindings_data.cljc` under `:editing`.
2. **Nexus wiring** - DOM handlers route through `shell/nexus.cljs`. Components emit `[:editing/navigate-up payload]`, while the global keymap feeds the same queue. That gives deterministic instrumentation and exactly one action per DOM event.
3. **Cursor guard** - Preserve the `__lastAppliedCursorPos` + mock-text pattern so Replicant doesn't stomp browser selections (see `.architect/RECURRING_PROBLEM_ANALYSIS.md` for historical context).
4. **Selection direction tracking** - `session/selection` stores `:direction`, `:anchor`, and `:focus`. Shift+Arrow extends/contracts incrementally instead of recomputing contiguous ranges. Shift+Click can continue to use contiguous visible ranges via `visible-blocks-in-dom-order`.
5. **Visibility filter** - Navigation/selection helpers must respect the active outline root (current page or zoom root) and skip folded descendants. Reuse `visible-blocks-in-dom-order {:root ... :skip-folded? true}` everywhere.

## 3. Editing Behavior Parity

### 3.1 What Works (Matches Logseq)

| Feature | Logseq Behavior | Evo Status |
|---------|-----------------|------------|
| **Enter mid-text** | Splits block at cursor: "abc\|de" -> "abc" + "de" | :white_check_mark: Works |
| **Enter at pos 0** | Creates empty block ABOVE, cursor stays | :white_check_mark: Works |
| **Shift+Enter** | Inserts literal `\n` newline (multi-line block) | :white_check_mark: Works |
| **Enter on list item** | Continues list pattern (-, *, 1.) | :white_check_mark: Works |
| **Enter on empty list** | Unformats block + creates peer at parent level | :white_check_mark: Works |
| **Enter on checkbox** | Continues `- [ ]` pattern | :white_check_mark: Works |
| **Enter inside markup** | Exits markup (moves cursor after closing marker) | :white_check_mark: Works |
| **Enter inside code block** | Inserts newline, stays in block | :white_check_mark: Works |
| **Vertical nav cursor memory** | Preserves column position across blocks | :white_check_mark: Works |
| **Left at start -> parent** | Navigates to parent block at end position | :white_check_mark: Works |
| **Right at end -> first child** | Navigates to first child at start (DOM order) | :white_check_mark: Works |
| **Navigation scope** | Arrow keys respect current page boundaries | :white_check_mark: Works |
| **Undo cursor restore** | Undo/redo restores cursor position | :white_check_mark: Works |

### 3.2 Behavioral Differences (Gaps)

| Gap | Logseq Behavior | Evo Behavior | Priority |
|-----|-----------------|--------------|----------|
| **Doc-mode (Enter swap)** | Configurable mode where Enter inserts newline, Shift+Enter creates block | Not implemented - Enter always creates block | LOW |
| **Empty block auto-outdent** | Enter on empty child block at end of parent auto-outdents | Stays in place, no auto-outdent | **MEDIUM** |
| **Block-ref -> sidebar** | Enter on block-ref `((uuid))` opens ref in sidebar | Returns stub `{:navigate-to-page}`, not implemented | LOW |
| **Page-ref -> navigate** | Enter on page-ref `[[page]]` navigates to page | Returns stub, not implemented | LOW |
| **Single-block embed mode** | In `.single-block` wrapper, Enter always inserts newline | No concept of single-block embeds | LOW |
| **Own-order list cleanup** | Empty ordered list removes list-type attribute | Not implemented | LOW |
| **Shift+Click with folded** | Only selects visible blocks, skips folded descendants | Selects folded descendants too | **MEDIUM** |

### 3.3 Logseq Code References

Key Enter behavior in Logseq (`handler/editor.cljs`):
- **Lines 2490-2554**: `keydown-new-block` - context detection and routing
- **Lines 2565-2572**: `keydown-new-block-handler` - doc-mode swap logic
- **Lines 2547-2551**: Empty block auto-outdent condition
- **Lines 2383-2386**: `keydown-new-line` - Shift+Enter inserts `\n`

## 4. Selection Behavior Parity

### 4.1 What Works

- :white_check_mark: Incremental selection extension (Shift+Arrow)
- :white_check_mark: Selection anchor/focus tracking
- :white_check_mark: Block selection at edit boundaries
- :white_check_mark: Shift+Arrow in multi-line blocks (text selection when NOT at boundary)

### 4.2 Remaining Gaps

1. **Visibility-aware Shift+Click** (`plugins.selection/calc-extend-props`)
   - Should filter out folded blocks
   - Replace `doc-range` with `visible-range` check

## 5. Focus & Lifecycle

Evo uses Replicant lifecycle hooks with `dataset.mounted` pattern:
- `on-mount`: Sets `mounted` flag, handles initial cursor for NEW elements
- `on-render`: Only handles cursor if `mounted` flag exists (avoids lifecycle inversion)
- Session-updates set `cursor-position`, lifecycle hooks apply it

This differs from Logseq's rum lifecycle but achieves similar results.

## 6. Triad & Testing Workflow

1. **Triad entries** (`docs/logseq_behaviors.md`) connect Keymap slices -> Intent contracts -> Scenario ledger rows
2. **Testing tiers** (`docs/TESTING_STACK.md`):
   - `bb test:view` - hiccup/view-only checks (<1s)
   - `bb test:int` - action extraction + integration loop
   - `bb e2e` - Playwright E2E tests
3. **Quality gates** - `bb lint`, `bb check`

## 7. Implementation Priority

**High priority (affects core UX):**
1. Empty block auto-outdent at end of parent

**Medium priority (edge cases):**
2. Visibility-aware Shift+Click (skip folded blocks)

**Low priority (power features):**
3. Doc-mode Enter/Shift+Enter swap
4. Block-ref/Page-ref navigation on Enter
5. Single-block embed mode

Use this overlay as the living bridge between Logseq truth and Evo's implementation. When behavior changes upstream, update `docs/LOGSEQ_SPEC.md` first, then refresh the tables here.
