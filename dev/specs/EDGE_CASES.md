# Edge Cases Analysis - Logseq Parity

**Date**: 2025-11-13
**Purpose**: Document all edge cases discovered through actual Logseq testing that are missing from LOGSEQ_SPEC.md

---

## State Machine Edge Cases

### Empty States (No Selection, No Editing)

**Scenario**: After Escape clears everything, what does each action do?

| Action | Expected Behavior | Status |
|--------|------------------|---------|
| Down Arrow | Select first visible block | ✅ FIXED |
| Up Arrow | Select last visible block | ✅ FIXED |
| Enter | **No-op** (only works when editing) | ✅ VERIFIED |
| Backspace | **No-op** (only works when editing) | ✅ VERIFIED |
| Tab | **No-op** (only works when editing/selecting) | ✅ VERIFIED |
| Click block | Select that block | 🔍 TODO |
| Type character | Start editing that block? | 🔍 TODO |

**Source**: `logseq/src/main/frontend/handler/editor.cljs:3365-3395, 2949-2963`

### Single Block Page

**Scenario**: Only one block exists on the page

| Action | Expected Behavior | Status |
|--------|------------------|---------|
| Down Arrow while selected | **No-op** (already at last) | ✅ VERIFIED |
| Up Arrow while selected | **No-op** (already at first) | ✅ VERIFIED |
| Down Arrow while editing | Move cursor to end of current block | ✅ VERIFIED |
| Up Arrow while editing | Move cursor to start of current block | ✅ VERIFIED |
| Outdent on single block | Depends on parent (likely no-op at root) | 🔍 TODO |
| Delete last block | ??? | 🔍 TODO |

**Source**: `logseq/src/main/frontend/handler/editor.cljs:2675-2677`
**Key insight**: When navigation hits boundary, fallback to cursor movement within block

---

## Navigation Edge Cases

### At Boundaries

**Scenario**: First/last block in various contexts

| Context | Action | Expected | Status |
|---------|--------|----------|---------|
| First block in page (editing) | Up Arrow | Move cursor to start | ✅ VERIFIED |
| Last block in page (editing) | Down Arrow | Move cursor to end | ✅ VERIFIED |
| First block in page (viewing) | Up Arrow | No-op (no previous block) | ✅ VERIFIED |
| Last block in page (viewing) | Down Arrow | No-op (no next block) | ✅ VERIFIED |
| First child of parent | Up Arrow | Previous sibling of parent | 🔍 TODO |
| Last child of parent | Down Arrow | Next sibling of parent | 🔍 TODO |
| Deep nested first block | Up Arrow | Walk up tree to previous visible | 🔍 TODO |

**Source**: `logseq/src/main/frontend/util.cljc:891-903`, `editor.cljs:2675`
**Key insight**: Boundary navigation returns `nil`, handlers fall back to cursor movement

### Folded/Collapsed Blocks

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Down Arrow on/after folded parent | **Skip automatically** (DOM query excludes collapsed) | ✅ VERIFIED |
| Up Arrow on/before folded block | **Skip automatically** (DOM query excludes collapsed) | ✅ VERIFIED |
| Edit folded block | Auto-expand? | 🔍 TODO |
| Delete folded block with children | Delete all? Move to trash? | 🔍 TODO |

**Source**: `logseq/src/main/frontend/util.cljc:860-877`, `editor.cljs:1373`
**Key insight**: `get-blocks-noncollapse` is a **DOM query** that only returns visible blocks. Folded/collapsed blocks never appear in navigation candidates.

### Zoom Context

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Navigate past zoom boundaries | Stop at boundary? | 🔍 TODO |
| Outdent at zoom root | Prevented? | 🔍 TODO |
| Up Arrow at first in zoom | No-op? | 🔍 TODO |

---

## Selection Edge Cases

### Multi-Select Boundaries

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Shift+Down on last block | Extend to where? | 🔍 TODO |
| Shift+Up on first block | Extend to where? | 🔍 TODO |
| Select all on empty page | ??? | 🔍 TODO |
| Cmd+A in empty block | ??? | 🔍 TODO |
| Cmd+A second press behavior | Exit edit → select block | 📝 SPEC'D |

### Invalid Selection States

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Select deleted block | Clear selection? | 🔍 TODO |
| Focus on moved block | Update focus? Clear? | 🔍 TODO |
| Selection across zoom | ??? | 🔍 TODO |

---

## Editing Edge Cases

### Empty/Whitespace Blocks

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Backspace in empty block | Merge with previous | 📝 SPEC'D |
| Backspace in whitespace-only | ??? | 🔍 TODO |
| Enter in empty block | Create new below? | 🔍 TODO |
| Tab on empty block | Indent under previous? | 🔍 TODO |

### Text Boundaries

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Backspace at position 0 | Merge with previous | 📝 SPEC'D |
| Delete at end of text | ??? | 🔍 TODO |
| Cmd+Backspace at start | ??? | 🔍 TODO |
| ArrowLeft at position 0 | Navigate to previous block end | 📝 SPEC'D |
| ArrowRight at end | Navigate to next block start | 📝 SPEC'D |

### Special Characters

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Paste multi-line text | **Splits into multiple blocks** (via mldoc parser) | ✅ VERIFIED |
| Double newline in paste | Creates paragraph break (new block) | ✅ VERIFIED |
| Single newline in paste | Preserved within block | ✅ VERIFIED |
| Tab character in text | Indent or literal tab? | 🔍 TODO |
| Emoji at cursor boundaries | ??? | 🔍 TODO |

**Source**: `logseq/src/main/frontend/handler/paste.cljs:24-57`
**Key insight**: Paste uses `mldoc/->edn` parser to extract block structure. Double newlines (`\n\n`) split paragraphs into separate blocks, each prefixed with `- ` if not already bulleted.

---

## Structural Editing Edge Cases

### Indent/Outdent Boundaries

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Indent first block | **No-op** (no previous sibling) | ✅ VERIFIED |
| Outdent at root level | Prevented | 📝 SPEC'D |
| Outdent only child | Works (moves under grandparent after parent) | ✅ FIXED |
| Indent when previous has children | Becomes last child of previous | 🔍 TODO |
| Outdent with children | Children stay under original parent | 📝 SPEC'D |

**Source**: `logseq/deps/outliner/src/logseq/outliner/core.cljs:1093-1111`
**Key insight**: Indent checks `when left` - returns `nil` (no-op) when no previous sibling exists

### Move Up/Down Boundaries

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Move up first block | No-op? | 🔍 TODO |
| Move down last block | No-op? | 🔍 TODO |
| Move across parent boundaries | ??? | 🔍 TODO |

### Delete with Dependencies

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Delete block with children | Move all to trash | 📝 SPEC'D |
| Delete parent of selected block | ??? | 🔍 TODO |
| Delete block referenced elsewhere | ??? | 🔍 TODO |

---

## Cursor Position Edge Cases

### After Operations

| Operation | Where does cursor go? | Status |
|-----------|---------------------|---------|
| Merge blocks | End of first block text | 📝 SPEC'D |
| Split block | Start of new block | 🔍 TODO |
| Outdent | ??? | 🔍 TODO |
| Indent | ??? | 🔍 TODO |
| Undo | Previous cursor position? | 🔍 TODO |
| Navigate then come back | Restore position? | 📝 SPEC'D (cursor-memory) |

### Multi-line Text

| Scenario | Expected Behavior | Status |
|----------|------------------|---------|
| Up Arrow on wrapped line | Move within text or navigate block? | 🔍 TODO |
| Cursor memory across wrapped lines | ??? | 🔍 TODO |

---

## Summary of Verified Edge Cases

### ✅ Behaviors Verified from Logseq Source

1. **Empty state actions** (no selection, not editing):
   - Enter, Backspace, Tab → **No-op**
   - Down Arrow → Select first visible block
   - Up Arrow → Select last visible block

2. **Navigation at boundaries**:
   - When editing: Arrow keys move cursor to start/end of current block
   - When viewing: Arrow keys are no-op (no block change)

3. **Folded block navigation**:
   - Automatically skipped via DOM query (`get-blocks-noncollapse`)
   - Never appear in navigation candidate list

4. **Indent first block**: **No-op** (checks for left sibling, returns nil if none)

5. **Paste multi-line text**:
   - Splits into blocks via mldoc parser
   - Double newlines = paragraph breaks
   - Auto-prefixes with `- ` if not already bulleted

6. **Outdent positioning**: Block moves **right after parent** (not to bottom) ← **BUG FIXED**

---

## Key Implementation Patterns Discovered

1. **State guards**: Most handlers check `(state/editing?)` or `(state/selection?)` first
2. **Boundary fallback**: Navigation at boundaries → cursor movement within block
3. **DOM-based collapsed detection**: Uses DOM queries, not DB state
4. **Nil returns for no-ops**: Functions return `nil` when operation can't proceed
5. **Parser-based paste**: Uses `mldoc/->edn` to preserve structure

---

## Testing Methodology

To verify each edge case:

1. **Test in actual Logseq**: Reproduce exact scenario
2. **Observe behavior**: Document what happens
3. **Test in Evo**: Compare behavior
4. **Update spec**: Add to LOGSEQ_SPEC.md
5. **Implement if missing**: Fix in Evo
6. **Add test**: E2E or unit test to prevent regression

---

## Next Steps

### High Priority (Missing Behaviors)
1. **Indent first block** → Implement no-op check
2. **Paste multi-line** → Implement block splitting
3. **Navigation boundary fallback** → Cursor movement when at edges

### Medium Priority (Spec Gaps)
1. Document all ✅ VERIFIED behaviors in LOGSEQ_SPEC.md
2. Add property-based tests for edge cases
3. Create E2E tests for boundary conditions

### Low Priority (Nice to Have)
1. Investigate remaining 🔍 TODO cases
2. Add documentation for implementation patterns
3. Create troubleshooting guide for common edge case bugs
