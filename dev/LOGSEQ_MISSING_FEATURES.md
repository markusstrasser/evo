# Missing Logseq Features - Basic Block/Page Operations

**Current Status:** Core structural editing works, but missing key editing UX features.

---

## ✅ Implemented

- Block creation (Enter)
- Text editing (contentEditable)
- Navigation (Up/Down arrows)
- Indent/Outdent (Tab/Shift+Tab)
- Multi-select (Shift+arrows)
- Block deletion (Backspace on empty/multi-select)
- Block merging (Backspace at start)
- Move blocks (Cmd+Shift+arrows)
- Undo/Redo (Cmd+Z)

---

## ❌ Missing Basic Features

### Editing Mode vs Navigation Mode

**Issue:** No clear distinction between "editing text" vs "navigating blocks"

**Logseq Behavior:**
- **Escape** - Exit edit mode, enter navigation mode (block stays selected)
- In navigation mode:
  - Up/Down navigate WITHOUT entering edit mode
  - Enter to start editing current block
  - Typing starts editing current block
- In edit mode:
  - Up/Down move cursor within text (if multi-line) or navigate blocks
  - Tab/Shift+Tab indent/outdent AND exit edit mode
  - Enter creates new block AND enters edit mode on new block

**Current Behavior:**
- Always in "edit mode" (contentEditable always active)
- No Escape key handling
- Tab/Shift+Tab work but don't exit edit mode

**Fix Needed:**
- Add `:edit-mode?` flag to selection state
- Escape exits edit mode (blur contentEditable)
- Click or Enter enters edit mode
- Structural actions (Tab, Enter, etc.) should work in both modes

---

### Slash Commands

**Missing:** Type `/` to open command menu

**Logseq Behavior:**
- `/` opens popup with common actions
- `/page` to link pages
- `/todo` to create TODO
- `/doing`, `/done` for task states

**Priority:** Medium (nice-to-have for demo)

---

### Block References

**Missing:** `((` to search and link blocks, `[[` to link pages

**Logseq Behavior:**
- `((` opens block search popup
- `[[` opens page search popup
- Autocomplete as you type

**Priority:** Low (advanced feature)

---

### TODO/DOING/DONE Markers

**Missing:** Cmd+Enter to cycle TODO states

**Logseq Behavior:**
- Cmd+Enter: TODO → DOING → DONE → (none) → TODO
- Checkbox rendered for TODO states
- Strike-through for DONE

**Priority:** Medium (useful for task management)

---

### Block Collapse/Expand

**Current:** Cmd+Up/Down logged but not implemented

**Logseq Behavior:**
- Cmd+Up: Collapse current block (hide children)
- Cmd+Down: Expand current block
- Visual indicator (triangle icon) when collapsed

**Priority:** Medium (useful for large outlines)

---

### Breadcrumbs/Zoom

**Missing:** Cmd+Click to zoom into block, Shift+Click to zoom out

**Logseq Behavior:**
- Cmd+Click block: Zoom into that block (becomes root)
- Breadcrumb navigation at top
- Shift+Click breadcrumb: Zoom out

**Priority:** Low (advanced navigation)

---

### Block Context Menu

**Missing:** Right-click block for actions menu

**Logseq Behavior:**
- Copy block ref
- Delete block
- Duplicate block
- Move to page
- Extract to page

**Priority:** Low (can use keyboard shortcuts)

---

### Shift+Enter (Newline within block)

**Missing:** Shift+Enter inserts newline within current block

**Logseq Behavior:**
- Enter: Creates new block
- Shift+Enter: Inserts `\n` within current block (multi-line block)

**Current:** Only Enter works (creates new block)

**Priority:** High (essential for multi-line blocks)

---

### Copy/Paste Blocks

**Missing:** Cmd+C/V for blocks (not just text)

**Logseq Behavior:**
- Cmd+C with block selected: Copy block structure
- Cmd+V: Paste as new block(s) preserving structure
- Works with multi-select

**Priority:** Medium (useful for reorganizing)

---

### Block Timestamps

**Missing:** Automatic/manual timestamps

**Logseq Behavior:**
- Auto-timestamps on creation
- `/today`, `/yesterday`, `/tomorrow` shortcuts

**Priority:** Low (journaling feature)

---

## Priority Order for Implementation

### P0 (Essential for Basic Use)
1. **Escape key** - Exit edit mode
2. **Shift+Enter** - Multi-line blocks
3. **Better cursor management** - Fix contentEditable focus issues

### P1 (Makes It Feel Like Logseq)
1. **TODO markers** - Cmd+Enter to cycle states
2. **Block collapse/expand** - Cmd+Up/Down
3. **Copy/paste blocks** - Cmd+C/V

### P2 (Nice-to-Have)
1. **Slash commands** - `/` for actions
2. **Block references** - `((` for block links
3. **Page links** - `[[` for page links

### P3 (Advanced Features)
1. **Zoom/breadcrumbs** - Cmd+Click navigation
2. **Context menu** - Right-click actions
3. **Timestamps** - Auto/manual dates

---

## Next Steps

1. **Fix Escape key** - Add edit mode toggle
2. **Test Tab/Shift+Tab** in edit mode - Ensure they work and exit edit mode
3. **Add Shift+Enter** for multi-line blocks
4. **Improve cursor management** - Ensure smooth typing experience

---

## Notes

- Focus on **text editing UX** first (Escape, Shift+Enter, cursor)
- Then **structural operations in edit mode** (Tab behavior)
- Advanced features (slash commands, references) can wait
- Keep it simple - don't implement full Logseq, just essential editing
