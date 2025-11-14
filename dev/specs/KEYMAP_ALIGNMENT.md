# Keymap Alignment with Logseq (macOS)

**Date**: 2025-11-13
**Status**: Complete keymap extraction and alignment

---

## What Changed

### 1. Extracted Complete Logseq Keymap

Created `LOGSEQ_KEYMAP_MACOS.md` - comprehensive reference of all Logseq editing/navigation shortcuts for macOS, extracted directly from source code.

**Includes**:
- Core editing (Enter, Escape, formatting)
- Kill commands (Emacs-style: Ctrl+L, Ctrl+U, Ctrl+W)
- Word navigation (Emacs-style: Ctrl+Shift+F/B)
- Arrow key navigation (Up/Down/Left/Right + Ctrl+P/N aliases)
- Block manipulation (Tab, move, fold)
- Selection operations
- Undo/Redo
- Special actions (links, checkboxes)

### 2. Updated bindings_data.cljc

**Key changes**:

1. **Fixed Kill Command Modifiers** (CRITICAL):
   ```clojure
   ;; BEFORE (WRONG):
   [{:key "l" :mod true} ...]  ; Would be Cmd+L (conflicts!)
   [{:key "u" :mod true} ...]  ; Would be Cmd+U

   ;; AFTER (CORRECT - matches Logseq):
   [{:key "l" :ctrl true} ...] ; Ctrl+L
   [{:key "u" :ctrl true} ...] ; Ctrl+U
   [{:key "w" :ctrl true} ...] ; Ctrl+W
   ```

2. **Removed Ctrl+K and Alt+W** (not bound on macOS in Logseq):
   - Logseq explicitly sets these to `false` for macOS
   - We removed the bindings to match

3. **Added Delete key for selected blocks**:
   - Both Backspace and Delete can delete selected blocks
   - Matches Logseq's `:editor/delete-selection`

4. **Removed Backspace/Delete from editing mode**:
   - These are handled by contenteditable + component logic
   - Special cases (merge at position 0) handled in component
   - Matches Logseq's architecture

5. **Added navigation intents**:
   - Up/Down with cursor memory
   - Ctrl+P/N aliases (Emacs-style)
   - Left/Right at edges navigate to adjacent blocks

6. **Better organization**:
   - Grouped by function (core editing, formatting, navigation, kill commands)
   - Added comments explaining each section
   - References LOGSEQ_KEYMAP_MACOS.md for full details

### 3. Archived Previous Specs

Moved to `dev/specs/archive/`:
- CRITICAL_INTERACTION_EDGE_CASES.md
- EDGE_CASE_STATUS.md
- ENTER_ESCAPE_BEHAVIOR.md
- ENTER_ESCAPE_THOROUGH_ANALYSIS.md
- ENTER_ESCAPE_VERIFIED.md
- WHY_WE_KEEP_FAILING.md

These documents were valuable for understanding the process failures, but are now superseded by the direct keymap extraction approach.

---

## Critical Fixes

### Kill Commands (Emacs-style on macOS)

**Problem**: We were using `:mod true` (Cmd key) instead of `:ctrl true` (Control key).

**Impact**:
- Cmd+L would have conflicted with browser/system shortcuts
- Cmd+U would have conflicted with View Source
- These wouldn't match Logseq behavior at all

**Fix**: Changed all kill commands to use `:ctrl true`:
- Ctrl+L → clear block
- Ctrl+U → kill to beginning
- Ctrl+W → kill word forward

**Removed** (not bound on macOS in Logseq):
- Ctrl+K → kill to end
- Alt+W → kill word backward

### Navigation Intents

**Added proper intents for arrow keys in editing mode**:

```clojure
;; Up/Down navigate between blocks with cursor memory
[{:key "ArrowUp"} {:type :navigate-with-cursor-memory
                   :direction :up
                   :block-id :editing-block-id
                   :cursor-pos :cursor-pos}]

;; Ctrl+P/N aliases (Emacs-style)
[{:key "p" :ctrl true} {:type :navigate-with-cursor-memory
                        :direction :up ...}]

;; Left/Right at edges navigate to adjacent blocks
[{:key "ArrowLeft"} {:type :navigate-to-adjacent
                     :direction :left ...}]
```

These intents already exist in `plugins/navigation.cljc`.

---

## Verification

### Compilation

```bash
npx shadow-cljs compile blocks-ui
# ✅ Build completed. (139 files, 1 compiled, 0 warnings)
```

### Intent Coverage

**All intents referenced in keymap exist**:
- `:context-aware-enter` ✅ (plugins/smart_editing.cljc)
- `:insert-newline` ✅ (plugins/editing.cljc)
- `:enter-edit-selected` ✅ (plugins/editing.cljc)
- `:exit-edit` ✅ (plugins/editing.cljc)
- `:format-selection` ✅ (plugins/smart_editing.cljc)
- `:move-cursor-forward-word` ✅ (plugins/editing.cljc)
- `:move-cursor-backward-word` ✅ (plugins/editing.cljc)
- `:clear-block-content` ✅ (plugins/editing.cljc)
- `:kill-to-beginning` ✅ (plugins/editing.cljc)
- `:kill-word-forward` ✅ (plugins/editing.cljc)
- `:navigate-with-cursor-memory` ✅ (plugins/navigation.cljc)
- `:navigate-to-adjacent` ✅ (plugins/navigation.cljc)
- `:selection` ✅ (plugins/selection.cljc)
- `:indent-selected` ✅ (plugins/structure.cljc)
- `:outdent-selected` ✅ (plugins/structure.cljc)
- `:delete-selected` ✅ (plugins/editing.cljc)
- `:toggle-fold` ✅ (plugins/folding.cljc)
- `:expand-all` ✅ (plugins/folding.cljc)
- `:collapse` ✅ (plugins/folding.cljc)
- `:zoom-in` ✅ (plugins/zoom.cljc)
- `:zoom-out` ✅ (plugins/zoom.cljc)
- `:toggle-checkbox` ✅ (plugins/smart_editing.cljc)
- `:follow-link-under-cursor` ✅ (plugins/refs.cljc)
- `:undo` ✅ (plugins/history.cljc)
- `:redo` ✅ (plugins/history.cljc)
- `:move-selected-up` ✅ (plugins/structure.cljc)
- `:move-selected-down` ✅ (plugins/structure.cljc)

---

## What's NOT Included Yet

The following Logseq shortcuts are documented in LOGSEQ_KEYMAP_MACOS.md but NOT YET implemented in bindings_data.cljc:

### Copy/Paste
- Cmd+C → copy blocks
- Cmd+Shift+C → copy as plain text
- Cmd+X → cut blocks
- Cmd+Shift+V → paste as plain text

### Links & Special
- Cmd+L → insert HTML link
- Cmd+Shift+E → copy block embed
- Cmd+Shift+R → replace block ref with content
- Cmd+Shift+O → open link in sidebar

### Block Manipulation
- Cmd+Shift+M → move selected blocks
- Shift+Enter (on selected) → open in sidebar

**Reason**: User requested "basic editing/navigating experience". These are advanced features that can be added incrementally after core behavior is verified.

---

## Next Steps

1. **Test in UI**: Verify all keybindings work as expected
2. **Compare with Logseq**: Test side-by-side to ensure exact behavioral parity
3. **Implement remaining shortcuts**: Add copy/paste, links, advanced block manipulation
4. **E2E tests**: Update tests to verify new keybindings
5. **Documentation**: Update user-facing docs with complete keybinding reference

---

## Process Improvement

**Key Lesson**: Extract complete keymap from source code FIRST, then implement systematically.

**What worked**:
1. Read `logseq/src/main/frontend/modules/shortcut/config.cljs` directly
2. Create comprehensive reference document
3. Map each binding to existing intent (or identify missing intent)
4. Update bindings_data.cljc systematically
5. Verify compilation

**What didn't work before**:
1. Spec based on observation
2. Implement features in isolation
3. Discover missing behaviors after the fact
4. No systematic coverage tracking

**Reference**: See `archive/WHY_WE_KEEP_FAILING.md` for detailed process analysis.
