# Structural Editing Specification

Core behaviors for block-based structural editors. These are fundamental mechanics that any outliner must implement correctly - independent of UI chrome like palettes, sidebars, or search overlays.

**Scope**: Single-page editing with blocks, navigation, cursor handling, selection, and tree operations.

---

## 1. State Machine

Three mutually exclusive interaction states:

```
┌──────────────┐   click block    ┌────────────────────┐
│    Idle      │ ────────────────▶│    Selection       │
│ • no cursor  │                  │ • blue highlight   │
│ • no select  │◀──── Escape ─────│ • focus == anchor  │
└──────────────┘   (clears all)   └─────────┬──────────┘
       │                                    │
       │ ArrowUp/Down                       │ Enter / typing
       │ (selects first/last)               ▼
       │                          ┌────────────────────┐
       └─────────────────────────▶│    Editing         │
              double-click        │ • contenteditable  │
                                  │ • caret visible    │
                                  │ • selection = ∅    │
                                  └────────────────────┘
```

### 1.1 Invariants

- **Mutual exclusivity**: Edit mode and block selection never coexist
- **Entering edit**: Clears block selection, sets exactly one `:editing-block-id`
- **Exiting edit**: Clears editing state; selection remains empty unless gesture re-selects
- **Escape in edit**: Exits edit AND selects the block (Logseq parity)
- **Escape in selection**: Clears selection → idle
- **Background click**: Clears selection → idle

### 1.2 Idle Guard (FR-Idle-01)

In true idle state (no block selected, no block editing):

| Key | Behavior |
|-----|----------|
| Enter, Backspace, Delete, Tab | **No-op** |
| Shift+Enter, Shift+Arrow | **No-op** |
| Cmd+Enter | **No-op** |
| ArrowUp | Select **last** visible block |
| ArrowDown | Select **first** visible block |
| Printable character | **No-op** (no block to edit) |

### 1.3 Type-to-Edit

When a block is selected (not editing), pressing any printable key:
1. Enters edit mode on that block
2. Appends the character
3. Positions caret after it

---

## 2. Cursor & Navigation

### 2.1 Cursor Memory (Vertical Navigation)

When pressing ArrowUp/Down while editing:

| Scenario | Behavior |
|----------|----------|
| Multi-line block, not at boundary | Move within block (browser default) |
| At first/last row | Navigate to prev/next visible block |
| Target block shorter | Clamp to end of line |
| No prev/next block | No-op (stay in current block) |

**Column memory**: Store grapheme column when moving vertically. Restore when target line is long enough.

### 2.2 Horizontal Boundary Navigation

| Position | Key | Behavior |
|----------|-----|----------|
| Column 0 | ArrowLeft | Navigate to prev block, cursor at end |
| End of text | ArrowRight | Navigate to next block, cursor at 0 |
| Interior | Arrow keys | Browser default |

### 2.3 View Mode Navigation

| Key | Behavior |
|-----|----------|
| ArrowUp | Move focus to previous visible block |
| ArrowDown | Move focus to next visible block |
| Enter | Enter edit mode on focused block |

"Visible" = respects folding and zoom root.

---

## 3. Text Selection

### 3.1 Within-Block Selection

Standard browser text selection while editing. No special handling needed.

### 3.2 Shift+Arrow at Boundaries

| Scenario | Behavior |
|----------|----------|
| Shift+Up, NOT at first row | Browser extends text selection |
| Shift+Up, AT first row | Exit edit, select block, extend selection upward |
| Shift+Down, NOT at last row | Browser extends text selection |
| Shift+Down, AT last row | Exit edit, select block, extend selection downward |

### 3.3 Block Selection Extension

Selection has three components:
- **nodes**: Set of selected block IDs
- **anchor**: First block selected (for range calculations)
- **focus**: Current endpoint (for extend operations)
- **direction**: `:up` or `:down` (for contract semantics)

| Mode | Behavior |
|------|----------|
| `:extend-prev` | Add previous visible block to selection |
| `:extend-next` | Add next visible block to selection |
| `:replace` | Clear selection, select single block |
| `:clear` | Empty selection → idle |

### 3.4 Cmd+A Cycle

| Press | State | Result |
|-------|-------|--------|
| 1st | Editing | Select all text in block |
| 2nd | All text selected | Exit edit, select block |
| 3rd | Block selected | Select parent (if exists) |
| 4th | Parent selected | Select all visible blocks |

### 3.5 Cmd+Shift+A

Select all visible blocks in current page/zoom scope.

### 3.6 Shift+Click

Click with Shift held:
1. If anchor exists: Select range from anchor to clicked block
2. If no anchor: Select clicked block as anchor

Range respects document order and visibility (folding/zoom).

---

## 4. Editing Actions

### 4.1 Enter (Smart Split)

| Context | Behavior |
|---------|----------|
| Cursor at position 0 | Create block above, stay in current |
| Cursor in middle | Split block at cursor, move to new block |
| Cursor at end | Create block below, move to it |
| Empty list item (`- `) | Unformat + create peer at parent level |
| Inside code fence | Insert literal newline |

### 4.2 Shift+Enter

Insert literal newline within block. Never creates new block.

**Doc-mode toggle**: When active, Enter/Shift+Enter swap behaviors.

### 4.3 Backspace

| Context | Behavior |
|---------|----------|
| Text selection | Delete selection (browser) |
| Cursor > 0 | Delete char before cursor (browser) |
| Cursor = 0, has prev sibling | Merge into previous block |
| Cursor = 0, no prev sibling | No-op OR climb out |
| Empty block | Delete block, focus previous |

**Merge semantics**: Concatenate texts, re-parent children to target, position cursor at join point.

### 4.4 Delete

| Context | Behavior |
|---------|----------|
| Text selection | Delete selection (browser) |
| Cursor < end | Delete char after cursor (browser) |
| Cursor = end, has children | Merge with first child |
| Cursor = end, has next sibling | Merge with next sibling |
| Cursor = end, neither | No-op |

### 4.5 Whitespace Handling

- Blocks containing only whitespace behave as empty blocks
- Backspace/Delete remove them
- Tab/Shift+Tab indent/outdent them
- Merge operations treat them as zero-length

---

## 5. Structural Operations

### 5.1 Indent (Tab)

Move block under previous visible sibling (becomes last child).

| Condition | Behavior |
|-----------|----------|
| Has previous sibling | Move under it as last child |
| First child (no prev sibling) | **No-op** |
| At zoom root | **No-op** |

Children stay attached to indented block.

### 5.2 Outdent (Shift+Tab)

Move block to become sibling of its parent (positioned after parent).

| Condition | Behavior |
|-----------|----------|
| Has parent (not root) | Move after parent |
| At root level (:doc) | **No-op** |
| At zoom root | **No-op** |

Children stay attached to outdented block. Right siblings stay under original parent.

### 5.3 Move Up (Cmd+Shift+Up)

Reorder block before its previous sibling.

| Condition | Behavior |
|-----------|----------|
| Has previous sibling | Swap positions |
| First child, has parent | **Climb out**: Move before parent |
| At absolute top | **No-op** |

### 5.4 Move Down (Cmd+Shift+Down)

Reorder block after its next sibling.

| Condition | Behavior |
|-----------|----------|
| Has next sibling | Swap positions |
| Last child, parent has next | **Descend**: Move into parent's next sibling as first child |
| At absolute bottom | **No-op** |

### 5.5 Delete Block

Move to `:trash` root. Children move with parent. Never truly destroys nodes.

### 5.6 Multi-Selection Operations

When multiple blocks selected:
- Indent/outdent: Process in document order, filter nested duplicates
- Move: Maintain relative order, stable reorder
- Delete: All selected move to trash together

---

## 6. Folding & Zoom

### 6.1 Folding

- Toggle via bullet click or Cmd+;
- Folded blocks hide all descendants from navigation
- All navigation/selection respects fold state
- Editing a folded block auto-expands it

### 6.2 Zoom

- Zoom root becomes the visible boundary
- Navigation cannot escape zoom scope
- Outdent/move operations that would exit zoom = no-op
- Cmd+. zooms in, Cmd+, zooms out

### 6.3 Visibility

"Visible block" means:
1. Within current page/zoom root
2. Not a descendant of a folded block
3. Not in :trash

All navigation and selection use visible-order traversal.

---

## 7. Undo/Redo

### 7.1 Scope

Undo/redo operates on:
- Block content
- Block structure (parent-child relationships)
- Cursor position at time of edit
- Selection state at time of edit

### 7.2 Granularity

Each intent that modifies the DB creates one undo entry. Rapid typing may be coalesced.

### 7.3 Cursor Restoration

After undo/redo:
- If a block was being edited, restore cursor position in that block
- If blocks were selected, restore that selection
- Focus follows the restored state

---

## 8. Behavioral Invariants

- [ ] Edit ↔ View states never overlap
- [ ] Cursor memory preserves grapheme column across blocks
- [ ] Shift+Arrow only exits text selection at visual row boundaries
- [ ] Structural moves maintain relative sibling order
- [ ] Indent first-child and outdent root-level are always no-ops
- [ ] Move up/down use climb/descend at boundaries
- [ ] All operations respect fold and zoom visibility
- [ ] Undo/redo restores cursor/selection context
- [ ] Empty/whitespace blocks behave like empty for structure ops

---

## Appendix: Implementation Checklist

### State Machine
- [ ] Idle guard blocks destructive keys
- [ ] Type-to-edit appends character
- [ ] Escape exits edit AND selects block
- [ ] Background click clears selection

### Navigation
- [ ] Cursor memory across vertical moves
- [ ] Horizontal boundary navigation
- [ ] View mode arrow keys move focus

### Selection
- [ ] Shift+Arrow boundary detection
- [ ] Block selection with anchor/focus
- [ ] Cmd+A cycle through levels
- [ ] Shift+Click range selection

### Editing
- [ ] Context-aware Enter
- [ ] Shift+Enter inserts newline
- [ ] Backspace merge at position 0
- [ ] Delete merge at end

### Structure
- [ ] Indent under previous sibling
- [ ] Outdent after parent
- [ ] Move up with climb
- [ ] Move down with descend
- [ ] Multi-select operations

### Visibility
- [ ] Folding hides descendants
- [ ] Zoom constrains boundaries
- [ ] All ops respect visibility

### History
- [ ] Undo/redo with cursor restore
