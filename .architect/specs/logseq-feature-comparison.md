# Logseq Block Navigation Feature Comparison

**Date**: 2025-10-23
**Purpose**: Compare Logseq's structural editing features with current evo specs to identify missing functionality

---

## Summary of Current Evo Specs

From `.architect/specs/structural-editing-interaction-spec.md`:

**Covered Operations:**
- ✅ Move block up/down (Cmd+Shift+↑/↓)
- ✅ Indent (Tab) - move under previous sibling
- ✅ Outdent (Shift+Tab) - lift to parent level
- ✅ Enter - split block at caret
- ✅ Backspace - merge with previous
- ✅ Multi-selection moves
- ✅ Drag and drop
- ✅ Collapse/expand (Ctrl+Alt+←/→)

---

## Logseq Feature Matrix

### 1. Basic Structural Editing (Already in Specs)

| Feature | Logseq Shortcut | Handler | Status |
|---------|----------------|---------|--------|
| Move block up | `mod+shift+up` (mac) / `alt+shift+up` (win/linux) | `editor-handler/move-up-down` | ✅ Covered |
| Move block down | `mod+shift+down` / `alt+shift+down` | `editor-handler/move-up-down` | ✅ Covered |
| Indent | `tab` | `editor-handler/keydown-tab-handler :right` | ✅ Covered |
| Outdent | `shift+tab` | `editor-handler/keydown-tab-handler :left` | ✅ Covered |
| Expand children | `mod+down` | `editor-handler/expand!` | ✅ Covered |
| Collapse children | `mod+up` | `editor-handler/collapse!` | ✅ Covered |
| Toggle collapse | `mod+;` | `editor-handler/toggle-collapse!` | ✅ Covered |

### 2. Block Selection (MISSING from our specs)

| Feature | Logseq Shortcut | Handler | Status |
|---------|----------------|---------|--------|
| Select block up | `alt+up` | `editor-handler/on-select-block :up` | ❌ MISSING |
| Select block down | `alt+down` | `editor-handler/on-select-block :down` | ❌ MISSING |
| Extend selection up | `shift+up` | `editor-handler/shortcut-select-up-down :up` | ❌ MISSING |
| Extend selection down | `shift+down` | `editor-handler/shortcut-select-up-down :down` | ❌ MISSING |
| Select all blocks | `mod+shift+a` | `editor-handler/select-all-blocks!` | ❌ MISSING |
| Select parent | `mod+a` | `editor-handler/select-parent` | ❌ MISSING |
| Delete selection | `backspace` or `delete` | `editor-handler/delete-selection` | ❌ MISSING |

### 3. Zoom/Focus Operations (MISSING from our specs)

| Feature | Logseq Shortcut | Handler | Status |
|---------|----------------|---------|--------|
| Zoom in (focus block) | `mod+.` (mac) / `alt+right` (win) | `editor-handler/zoom-in!` | ❌ MISSING |
| Zoom out (unfocus) | `mod+,` (mac) / `alt+left` (win) | `editor-handler/zoom-out!` | ❌ MISSING |

### 4. Block Opening/Editing (MISSING from our specs)

| Feature | Logseq Shortcut | Handler | Status |
|---------|----------------|---------|--------|
| Open/edit block | `enter` | `editor-handler/open-selected-block!` | ❌ MISSING |
| Open blocks in sidebar | `shift+enter` | `editor-handler/open-selected-blocks-in-sidebar!` | ❌ MISSING |

### 5. Navigation/Jump (MISSING from our specs)

| Feature | Logseq Shortcut | Handler | Status |
|---------|----------------|---------|--------|
| Jump to block | `mod+j` | `jump-handler/jump-to` | ❌ MISSING |
| Follow link | `mod+o` | `editor-handler/follow-link-under-cursor!` | ❌ MISSING |
| Open link in sidebar | `mod+shift+o` | `editor-handler/open-link-in-sidebar!` | ❌ MISSING |

### 6. Todo/Metadata Operations (MISSING from our specs)

| Feature | Logseq Shortcut | Handler | Status |
|---------|----------------|---------|--------|
| Cycle todo state | `mod+enter` | `editor-handler/cycle-todo!` | ❌ MISSING |
| Toggle number list | `t n` | State event handler | ❌ MISSING |

### 7. History (MISSING from our specs)

| Feature | Logseq Shortcut | Handler | Status |
|---------|----------------|---------|--------|
| Undo | `mod+z` | `history/undo!` | ❌ MISSING |
| Redo | `mod+shift+z` or `mod+y` | `history/redo!` | ❌ MISSING |

### 8. Bulk/Multi-Block Operations (Partially covered)

| Feature | Logseq Shortcut | Handler | Status |
|---------|----------------|---------|--------|
| Move blocks (multi) | `mod+shift+m` | `editor-handler/move-selected-blocks` | ⚠️ Partial |
| Toggle open all blocks | `t o` | `editor-handler/toggle-open!` | ❌ MISSING |

---

## Implementation Details from Logseq Source

### Key Implementation Patterns

1. **Block Selection State Management**
   - Logseq maintains selection state separate from edit state
   - Multi-selection is tracked via `state/get-selection-blocks`
   - Selection can be extended (shift) or moved (alt)

2. **Zoom/Focus Pattern**
   - "Zoom in" focuses on a block, making it the root of the view
   - "Zoom out" returns to parent context
   - This is different from collapse/expand which just hides children

3. **Move-up-down Special Cases** (from `structural_editing_specs.md`)
   - **First child move-up**: Logseq hoists to uncle's last position
   - **Last child move-down**: Falls through to parent's right sibling
   - **Auto-expand collapsed**: Automatically opens collapsed drop targets
   - **Metadata update**: Updates page metadata on moves

4. **Indent/Outdent Special Behavior**
   - **Logical outdenting**: Optional flag to keep right siblings attached when outdenting
   - **Multi-select preservation**: `get-top-level-blocks` normalizes selections
   - **Fix non-consecutive**: `fix-non-consecutive-blocks` fills gaps in selection

5. **Handler Architecture**
   - High-level handlers in `frontend/handler/editor.cljs` and `frontend/handler/block.cljs`
   - Core operations in `frontend/modules/outliner/op.cljs`
   - Operations: `move-blocks-up-down!`, `indent-outdent-blocks!`, `move-blocks!`

---

## Priority Missing Features to Implement

### P0 - Critical Navigation

1. **Block Selection (non-editing)**
   - Select block up/down (alt+↑/↓)
   - Extend selection (shift+↑/↓)
   - Select parent (mod+a)
   - These are fundamental for keyboard-only navigation

2. **Undo/Redo**
   - Essential for any editing interface
   - Logseq uses `history/undo!` and `history/redo!`

### P1 - High Value

3. **Zoom In/Out (Focus)**
   - Zoom to block (make it root of view)
   - Zoom out (return to parent)
   - Critical for navigating deep hierarchies

4. **Multi-Selection Operations**
   - Select all blocks (mod+shift+a)
   - Delete selection (backspace/delete on selection)
   - Move selected blocks

### P2 - Nice to Have

5. **Jump/Search**
   - Jump to block by search (mod+j)
   - Follow links (mod+o)

6. **Todo State**
   - Cycle todo state (mod+enter)
   - Toggle list type (numbered vs bullet)

---

## Recommended Test Additions

Based on Logseq's feature file patterns, add these test scenarios:

### Block Selection Tests

```clojure
;; Select next block without editing
(deftest select-block-down
  (-> (setup-page [[:b1 "Block 1"] [:b2 "Block 2"]])
      (select-block :b1)
      (press "alt+down")
      (expect-selection [:b2])
      (expect-not-editing)))

;; Extend selection downward
(deftest extend-selection-down
  (-> (setup-page [[:b1 "B1"] [:b2 "B2"] [:b3 "B3"]])
      (select-block :b1)
      (press "shift+down")
      (expect-selection [:b1 :b2])))

;; Select parent selects all siblings
(deftest select-parent
  (-> (setup-page [[:parent "Parent" [[:child1 "C1"] [:child2 "C2"]]]])
      (select-block :child1)
      (press "mod+a")
      (expect-selection [:child1 :child2])))
```

### Zoom Tests

```clojure
;; Zoom in makes block the root
(deftest zoom-in-focuses-block
  (-> (setup-page [[:p "Parent" [[:c "Child"] [:c2 "C2"]]]])
      (select-block :c)
      (press "mod+.")
      (expect-root-block :c)
      (expect-outline [[:c2 "C2"]])))

;; Zoom out returns to parent
(deftest zoom-out-returns
  (-> (setup-page [[:p "Parent" [[:c "Child"]]]])
      (select-block :c)
      (press "mod+.")  ; zoom in
      (press "mod+,")  ; zoom out
      (expect-root-block :p)))
```

### Undo/Redo Tests

```clojure
;; Undo reverses indent
(deftest undo-indent
  (-> (setup-page [[:b1 "B1"] [:b2 "B2"]])
      (select-block :b2)
      (press "tab")
      (expect-outline [[:b1 "B1" [[:b2 "B2"]]]])
      (press "mod+z")  ; undo
      (expect-outline [[:b1 "B1"] [:b2 "B2"]])))
```

---

## Architecture Notes

### Logseq's Operation Model

1. **Op Layer** (`frontend/modules/outliner/op.cljs`):
   - Builds operation descriptors: `[:move-blocks [ids target-id opts]]`
   - Does NOT execute - just records intent
   - Collected via dynamic var `*outliner-ops*`

2. **Transaction Layer**:
   - `ui-outliner-tx/transact!` executes collected ops
   - Wraps in transaction metadata (`:outliner-op`, `:real-outliner-op`)

3. **Handler Layer**:
   - High-level keyboard/UI handlers
   - Calls op builders within transaction scope
   - Manages DOM/state updates post-transaction

**This matches our architecture**: Intents → Core ops → Transactions

---

## Files to Review for Implementation

### Logseq Source Files

- `src/main/frontend/modules/shortcut/config.cljs` - All keyboard shortcuts
- `src/main/frontend/handler/editor.cljs` - Primary editor handlers (move, indent, select, zoom)
- `src/main/frontend/handler/block.cljs` - Block-specific operations
- `src/main/frontend/modules/outliner/op.cljs` - Operation builders
- `features/op_block_indent_outdent.feature` - BDD test specs

### Our Files to Update

- `.architect/specs/structural-editing-interaction-spec.md` - Add missing operations
- `src/plugins/struct/core.cljc` - Add missing intent compilers
- `test/plugins/struct/core_test.cljc` - Add test coverage

---

## Next Steps

1. ✅ Review current specs (done)
2. ✅ Review Logseq source (done)
3. ⏳ Add missing operations to interaction spec
4. ⏳ Implement intent compilers for P0 features
5. ⏳ Add test coverage for new operations
6. ⏳ Implement zoom/focus infrastructure
7. ⏳ Add undo/redo system

---

## References

- **Logseq Source**: `~/Projects/best/logseq/`
- **Athens Comparison**: `.architect/specs/structural_editing_specs.md`
- **Current Spec**: `.architect/specs/structural-editing-interaction-spec.md`
- **Our Implementation**: `src/plugins/struct/core.cljc`
