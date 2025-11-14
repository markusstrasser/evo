# Cursor Behavior - Logseq Parity

**Date**: 2025-11-14
**Source**: Analysis of `logseq/src/main/frontend/handler/editor.cljs` and `util.cljc`

---

## Block Selection Navigation (Non-editing Mode)

### Down Arrow on Selected Block
**Order**: Pre-order traversal (DOM order of `.ls-block` elements)
- From parent to first child
- From child to next sibling (or parent's next sibling if no sibling)
- Skips collapsed children
- Skips nested children of currently selected blocks

**Example**:
```
-A (selected)
--B
---C
--D
-E
```

Down Arrow sequence: A → B → C → D → E

### Up Arrow on Selected Block
**Order**: Reverse pre-order
- From child to parent
- From sibling to previous sibling (or previous sibling's last child)

**Example** (same tree, starting from E):
Up Arrow sequence: E → D → C → B → A

---

## Cursor Position During Block Navigation (Editing Mode)

### Up/Down Arrow While Editing
**Logseq Behavior**:
1. Check if cursor is on first/last row of contenteditable
2. If on **first row** and press Up → navigate to **previous block**
3. If on **last row** and press Down → navigate to **next block**
4. **CRITICAL**: Cursor **column position is remembered** and applied to target block

**Implementation**:
- Use mock-text technique to detect cursor row position
- Store cursor column offset when navigating
- Apply same column offset to target block (or end if line is shorter)

**Code Reference**: `shortcut-up-down` in `editor.cljs:2567`

### Left/Right Arrow at Edges
**Logseq Behavior**:
1. If cursor at **position 0** and press Left → navigate to **previous block at END**
2. If cursor at **end** and press Right → navigate to **next block at START**

**Code Reference**: `shortcut-left-right` in `editor.cljs:2649`

---

## Backspace/Delete Block Behavior

### Backspace at Position 0 (Merge with Previous)
**Logseq Behavior** (`keydown-backspace-handler` at line 3206):
1. **STOP** the browser's default backspace
2. Find **previous block** in DOM order (non-collapsed)
3. Move cursor to **END of previous block**
4. **Delete current block**
5. **Merge** current block's text into previous block at cursor position

**Edge Cases**:
- Top block (no previous) → do nothing
- Custom query → do nothing
- Root block → do nothing
- Single block container → do nothing

### Delete Key at End (Merge with Next)
**Logseq Behavior**:
- Mirror of Backspace behavior
- Merge next block into current at cursor position

---

## Enter Key Cursor Behavior

### Enter While Editing (Create New Block)
**Logseq Behavior** (`keydown-new-block-handler`):
1. Split text at cursor position
2. Create new block below with text after cursor
3. **Move cursor to START (position 0) of new block**

**Code Reference**: `editor.cljs:2980`

### Enter on Selected Block (Enter Edit Mode)
**Logseq Behavior** (`open-selected-block!` at line 3426):
1. Enter edit mode in selected block
2. Cursor goes to **END** of text (`:max` pos)

---

## Escape Key Cursor Behavior

### Escape While Editing
**Logseq Behavior** (`escape-editing` at line 3897):
1. Save current block
2. Exit edit mode
3. **DO NOT select the block**
4. **Clear cursor position memory**

**Critical**: No block should be selected after Escape.

---

## Current Implementation Gaps

### Missing in Our Codebase

1. **Cursor column memory during Up/Down navigation**
   - Currently: Cursor goes to END on every navigation
   - Should: Remember column position and apply to target block

2. **Left/Right at edges navigation**
   - Currently: NOT implemented
   - Should: Navigate to prev/next block at appropriate position

3. **Backspace at position 0**
   - Currently: Uses browser default (might not merge correctly)
   - Should: Explicit merge with previous block, cursor at END of previous

4. **Delete at end**
   - Currently: NOT implemented
   - Should: Merge next block into current

5. **Enter key cursor position**
   - Currently: Might not be at START of new block
   - Should: Always position 0 in newly created block

---

## Implementation Priority

### P0 (Critical for Basic Editing)
1. Fix Enter → cursor to START of new block
2. Fix Backspace at position 0 → merge with previous
3. Fix Escape → clear cursor position memory

### P1 (Important for Natural Feel)
4. Cursor column memory for Up/Down navigation
5. Left/Right edge navigation

### P2 (Nice to Have)
6. Delete at end → merge with next

---

## Testing Strategy

### Unit Tests
- Test cursor position after each operation
- Test block merge logic
- Test cursor memory persistence

### E2E Tests (Playwright)
- Visual verification of cursor position
- Test actual typing after navigation
- Compare with Logseq side-by-side

### Manual Testing
- Create complex nested structure
- Navigate with Up/Down/Left/Right
- Test Backspace/Delete at edges
- Verify cursor position feels natural
