# LOGSEQ_SPEC.md — Editing, Navigation, Selection, and Structural Behaviors

**Target Application:** Logseq desktop (macOS, build 0.10.x)  
**Purpose:** Canonical source-of-truth for Evo parity. Every behavior below is observed directly in `~/Projects/best/logseq` and must be implemented identically unless a deliberate divergence is documented in `LOGSEQ_PARITY.md`.

---

## 1. Global Interaction Model

### 1.1 State Machine

Logseq enforces a strict, mutually exclusive state machine for block interaction:

```
           Cmd/Ctrl+Enter           (new block)  
      ┌──────────────────────────────────────────┐
      │                                          ▼
┌──────────────┐   Enter block   ┌────────────────────┐
│ View (Idle)  │ ───────────────▶ │ View (Selection)   │
│ • no cursor  │   (click block) │ • blue selection   │
│ • no blue    │ ◀───────────────│ • focus == anchor  │
│   frames     │  Escape / click │ • no caret         │
└──────┬───────┘  background     └────────┬───────────┘
       │                                  │
       │ Cmd+Enter / double-click         │ Enter / start typing
       ▼                                  ▼
┌──────────────┐   Escape / blur   ┌────────────────────┐
│ Edit Mode    │ ─────────────────▶│ View (Idle)        │
│ • content-   │                   │                    │
│   editable   │                   │                    │
│ • caret shown│                   │                    │
│ • selection Ø│                   │                    │
└──────────────┘                   └────────────────────┘
```

**Invariants:**
- Edit mode (`Mode: Edit`) and selection state (`Mode: View` with `[*]`) never coexist.
- Transitioning *into* edit mode clears `selection`, `focus`, and ensures exactly one block has `:editing-block-id`.
- Transitioning *out* of edit mode leaves selection cleared unless a view-mode gesture immediately re-selects blocks.
- Background clicks and Escape (when not editing) clear selection.

### 1.2 Session Data Contracts

Logseq stores ephemeral interaction state in session nodes:

| Node ID               | Key                      | Meaning                                  |
|-----------------------|--------------------------|------------------------------------------|
| `session/ui`          | `:editing-block-id`      | ID of block in edit mode (or `nil`)      |
|                       | `:cursor-position`       | Hint for caret placement (`:start`/`:end`/offset) |
|                       | `:cursor-memory`         | `{ :line-pos int :last-block-id id :direction dir }` |
|                       | `:cursor`                | Map of boundary flags per block `{block-id {:first-row? ...}}` |
| `session/selection`   | `:nodes`                 | Set of selected block IDs                |
|                       | `:focus`                 | Current focus block in selection         |
|                       | `:anchor`                | Anchor block for range selection         |

Any Evo implementation must mutate these nodes exactly as Logseq does to avoid undo/redo drift.

---

## 2. Cursor Placement & Navigation

### 2.1 Vertical Navigation in Edit Mode

| Scenario | Before | Action | After | Notes |
|----------|--------|--------|-------|-------|
| Same-column Down | `A: "Hello ^world"` → `B: "First⏎Second line"` | `↓` | Caret lands in B’s last line, same visual column | Column stored via `GraphemeSplitter` line-pos, reused on target block |
| Short Target Up   | `A: "Hi"`, `B: "foo^ bar"` | `↑` | Caret in A at end | Falls back to `:end` when target shorter |
| Boundary No-op    | Single block editing | `↑` | No change | No exception thrown |

**Implementation Requirements:**
- Use grapheme-aware counting (`js/Intl.Segmenter`/`GraphemeSplitter`) to calculate `line-pos` and convert back to character offsets.
- `:navigate-with-cursor-memory` must skip folded descendants and blocks outside current zoom root using the same filters as `util/get-prev-block-non-collapsed` in Logseq.
- Missing siblings result in a no-op with the caret left at boundary (`:cursor-position` resets to `:start`/`(count text)`), never an exception.

### 2.2 Vertical Navigation in View Mode

- `ArrowUp/ArrowDown` when not editing move `:focus` through visible siblings, skipping folded subtrees and respecting zoom root.
- `Shift+Arrow` extends the range selection: anchor remains first selected block, focus becomes next visible sibling, range includes all blocks between anchor and focus in document order.
- Selection operations never modify `session/ui`.

### 2.3 Horizontal Boundary Navigation

- `ArrowLeft` at column 0 dispatches `:navigate-to-adjacent {:direction :up :cursor-position :max}`.
- `ArrowRight` at end of text dispatches `:navigate-to-adjacent {:direction :down :cursor-position 0}`.
- Interior positions defer to browser defaults.

---

## 3. Text Selection Rules

1. While editing, the caret (`|`) and intra-block text selection (`«…»`) are visible. No block is marked `[*]`.
2. While viewing, selection is represented by block highlighting (`[*]`); no caret is visible.
3. `Shift+Arrow` behavior:
   - If caret is not on first/last rendered row, let the browser extend text selection. Logseq does this by allowing the native event to proceed.
   - When caret reaches first/last row, `Shift+Arrow` should prevent default, exit edit mode, and dispatch `{:type :selection :mode :extend-prev/next}`.
4. `Cmd+A` while editing selects entire block text on first press; second press (with full text selected) exits edit mode and selects the block (`[*]`).
5. `Cmd+Shift+A` selects all visible blocks within current page/zoom root.
6. Escape behavior:
   - In edit mode: leaves edit, focus becomes `[*]` block.
   - In view mode: clears selection (no `[*]`).

---

## 4. Editing Actions

| Action | Behavior | Notes |
|--------|----------|-------|
| `Enter` | Dispatch `:smart-split` (context-aware). Splits block unless inside code fence / markdown structure where newline is expected. | Mirrors Logseq’s smart editing plugin. |
| `Shift+Enter` | Insert literal newline inside block (no new block). | Handled by keymap + smart editing.
| `Backspace` | - With selection: delete selection.  - At start of block: merge into previous sibling, re-parent children onto previous block, place caret at boundary.  - Empty block: delete block and move focus to previous sibling. | Matches `handler/editor.cljs:keydown-backspace-handler`.
| `Delete` | At end of block: merge with next block, prioritising first child then next sibling. Re-parent children accordingly. |
| `Cmd+O` | Follow link under caret (page refs open page, block refs scroll into view). |
| `Cmd+Shift+H` | Toggle highlight `^^…^^` preserving text selection. |

Undo granularity: each editing intent must emit minimal structural ops (`:update-node`, `:place`) to integrate with history.

---

## 5. Structural Editing

### 5.1 Indent/Outdent

- `Tab` (`:indent-selected`) moves active block/selection under previous visible sibling. Outdent behavior depends on `:editor/logical-outdenting?` (assumed `true` for this spec):
  - Block moves to bottom of grandparent’s children list.
  - Right siblings remain under original parent (no “kidnapping”).
  - Outdenting is prevented when parent is a root (`:doc`, `:journal`).

### 5.2 Move Up/Down

- `Alt+Shift+ArrowUp/Down` reorder siblings while preserving selection order (stable move).
- When editing, dedicated intents (`:move-block-up-while-editing`) perform same structural move without exiting edit mode.

### 5.3 Delete Selected

- Moves selected blocks to `const/root-trash`. Children are moved with the parent (same as Logseq).

---

## 6. Cursor Hint Lifecycle (Replicant Integration)

- On block render, `components.block.cljs` must:
  1. Focus the contenteditable node if it matches `:editing-block-id`.
  2. Apply cursor hints from `:cursor-position`, respecting `:start`, `:end`, or numeric offsets (clamped to text length).
  3. After applying, dispatch `{:type :clear-cursor-position}` to avoid reusing stale hints.
  4. Update mock text (`#mock-text`) to mirror content for accurate line detection.
- Boundary detection uses stored `:cursor` flags (first/last row) to decide when to escalate `Shift+Arrow` to block selection.

---

## 7. Fold & Zoom Constraints

- All navigation/selection intents must consult derived indexes that exclude folded descendants and nodes outside current zoom root. Equivalent to Logseq’s `util/get-prev-block-non-collapsed` / `get-next-block-non-collapsed`.
- Folding state lives under node props (e.g., `{:props {:folded? true}}`). Navigation should traverse siblings via derived links, skipping folded subtrees entirely.

---

## 8. Testing Guidance

Logseq’s coverage spans three layers; Evo should mirror these:

1. **Pure intent tests** (`plugins/navigation_test.cljs`, `plugins/editing_test.cljs`) to verify ops.
2. **View tests** using Replicant hiccup (`components.block-view-test.cljc`) to ensure caret, selection, and handlers are wired.
3. **Integration tests** simulating keybindings (`integration/keybinding_test.cljc`) covering edit vs view transitions and structural edits.

Additionally, replicate Logseq’s DOM-driven Playwright tests for Shift+Arrow, backspace merge, and outdenting to guard regressions.

---

## 9. Summary Checklist

- [ ] Edit/View states mutually exclusive; Escape/background click clear selection.
- [ ] Cursor memory uses grapheme-aware calculations and respects fold/zoom constraints.
- [ ] Shift+Arrow defers to browser until row boundary, then dispatches selection intents.
- [ ] Structural intents (`indent`, `outdent`, move up/down) mirror Logseq semantics.
- [ ] Backspace/Delete merge logic re-parents children correctly.
- [ ] Cursor hints applied then cleared every render cycle.
- [ ] Keybindings align with Logseq (Cmd+A, Cmd+Shift+A, Cmd+O, Shift+Enter, highlight toggle).
- [ ] Tests cover intents, views, integrations per section 8.

This document supersedes all prior Logseq parity specs. Any future deviations must be recorded in `LOGSEQ_PARITY.md`.
