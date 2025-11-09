# EVO - Quick Feature Reference vs Specification

## Your Specification vs Implementation

### Block Movement (Cmd+Shift+Up/Down)

Your Spec | EVO Implementation | Status | Files
----------|-------------------|--------|-------
Move blocks up/down with Cmd+Shift | ✓ Complete | `move-selected-up`/`move-selected-down` intents | plugins/struct.cljc lines 170-180, keymap/bindings_data.cljc

**Implementation Details**:
- Resolved intent type: `:move-selected-up` and `:move-selected-down`
- Handler gets currently selected/focused blocks
- Validates shared parent
- Uses `planned-positions` + `lower-reorder` to compute move ops
- Emits `:place` ops with relative anchors

---

### Indent/Outdent (Tab/Shift+Tab)

Your Spec | EVO Implementation | Status | Files
----------|-------------------|--------|-------
Tab: indent block | ✓ Complete | `:indent-selected` intent | plugins/struct.cljc lines 158-162
Shift+Tab: outdent | ✓ Complete | `:outdent-selected` intent | plugins/struct.cljc lines 164-168

**Implementation Details**:
- Indent: places block under previous sibling with `:at :last`
- Outdent: places block under grandparent with `:at {:after parent}`
- Respects document structure constraints
- Works on single block or multi-selection

---

### Enter to Split Blocks

Your Spec | EVO Implementation | Status | Files
----------|-------------------|--------|-------
Enter splits block at cursor | ✓ Complete | `:split-at-cursor` intent | plugins/editing.cljc lines 62-74

**Implementation Details**:
- Captures cursor position from DOM selection via `anchorOffset`
- Creates new block with text after cursor
- Updates original block with text before cursor
- Places new block as next sibling
- Smart variant (`:split-with-list-increment`) increments list numbers

---

### Backspace to Merge Blocks

Your Spec | EVO Implementation | Status | Files
----------|-------------------|--------|-------
Backspace at start merges with prev | ✓ Complete | `:merge-with-prev` intent | plugins/editing.cljc lines 50-60

**Implementation Details**:
- Detects if at text start in component (boundary detection)
- Gets previous sibling via derived `:prev-id-of` index
- Concatenates texts: `prev_text + curr_text`
- Updates prev block, moves current to trash
- Smart variant (`:merge-with-next`) for Delete key

---

### Multi-Selection Operations

Your Spec | EVO Implementation | Status | Files
----------|-------------------|--------|-------
Multi-select, operate on group | ✓ Complete | `:selection` intent with modes | plugins/selection.cljc lines 83-170

**Modes Implemented**:
- `:replace` - Replace selection (last becomes focus)
- `:extend` - Add to selection (with range if anchor exists)
- `:deselect` - Remove from selection
- `:toggle` - Toggle single ID
- `:clear` - Clear all
- `:next`/`:prev` - Navigate
- `:extend-next`/`:extend-prev` - Extend navigation

**UI Bindings**:
- Click: `:replace` single block
- Shift+Click: `:extend` to block
- Shift+Arrow: `:extend-next`/`:extend-prev`

**Storage**: Session node `session/selection` with props `{:nodes #{...} :focus id :anchor id}`

---

### Drag-and-Drop with Drop Guides

Your Spec | EVO Implementation | Status | Files
----------|-------------------|--------|-------
Drag blocks with visual guides | ◐ Partial | Core logic ready | plugins/struct.cljc

**What Works**:
- `lower-reorder` function computes correct move sequences
- `:move` intent handles arbitrary parent changes
- Anchor algebra supports all positioning needs
- Multi-node reordering with document order preservation

**What's Missing**:
- DOM drag event handlers (ondragstart, ondragover, ondrop, ondragleave)
- Visual drop guides during drag
- Drag preview styling
- Drag-zone highlight

**Path Forward**: Add drag handlers to Block component, dispatch `:move` intent on drop

---

### Collapse/Expand Functionality

Your Spec | EVO Implementation | Status | Files
----------|-------------------|--------|-------
Toggle collapse/expand state | ✓ Complete | `:toggle-fold`, `:expand-all`, `:collapse` | plugins/folding.cljc
Zoom in/out for navigation | ✓ Complete | `:zoom-in`, `:zoom-out`, `:zoom-to` | plugins/folding.cljc lines 133-176

**Fold State**:
- Stored in `session/ui` as set of folded IDs (ephemeral, not in history)
- Component checks `folded?` predicate to hide/show children
- Bullet click toggles fold, keyboard shortcuts available

**Zoom Stack**:
- Breadcrumb navigation: zoom-stack stores hierarchy
- `:zoom-root` specifies rendering root (not just :doc)
- `:zoom-out` pops stack, `:zoom-in` pushes

**Key Bindings**:
- Cmd+; → toggle-fold
- Cmd+Up → collapse
- Cmd+Down → expand-all
- Cmd+. → zoom-in
- Cmd+, → zoom-out

---

### Caret Positioning Behavior

Your Spec | EVO Implementation | Status | Files
----------|-------------------|--------|-------
Arrow keys navigate between blocks at boundaries | ✓ Complete | Mock-text detection + boundary handlers | components/block.cljs
Position cursor at start/end when entering edit | ✓ Complete | `:cursor-at` param in enter-edit | shell/blocks_ui.cljs

**Logseq-Style Navigation**:
1. When editing: Up/Down arrows navigate based on cursor row
2. At first row: Up exits edit, goes to prev block, enters edit at END
3. At last row: Down exits edit, goes to next block, enters edit at START
4. Otherwise: browser handles cursor movement normally

**Implementation**:
- Mock-text element mirrors content with unique span per char
- Gets Y position of each character
- Compares cursor Y to character Y positions
- Determines first/last row with O(1) lookup
- No expensive DOM queries per keystroke

---

## Complete Feature Inventory

Feature | Status | Location | Completeness
---------|--------|----------|---------------
Block creation | ✓ | plugins/struct.cljc | 100%
Block deletion | ✓ | plugins/struct.cljc | 100% (archive, not destroy)
Indent/Outdent | ✓ | plugins/struct.cljc | 100%
Move up/down | ✓ | plugins/struct.cljc | 100%
Multi-selection | ✓ | plugins/selection.cljc | 100%
Text editing | ✓ | plugins/editing.cljc | 100%
Split at cursor | ✓ | plugins/editing.cljc | 100%
Merge blocks | ✓ | plugins/editing.cljc | 100%
Fold/Expand | ✓ | plugins/folding.cljc | 100%
Zoom in/out | ✓ | plugins/folding.cljc | 100%
Text formatting | ✓ | plugins/text_formatting.cljc | 100%
Caret positioning | ✓ | components/block.cljs | 100%
Arrow navigation | ✓ | components/block.cljs | 100%
Undo/Redo | ✓ | kernel/history.cljc | 100%
Drag-drop core | ✓ | plugins/struct.cljc | 60% (logic ready, UI missing)
Block refs | ✓ | parser/block_refs.cljc | 100%
Embeds | ✓ | parser/embeds.cljc | 100%
Page refs | ✓ | parser/page_refs.cljc | 100%
Checkboxes | ✓ | plugins/smart_editing.cljc | 100%
List formatting | ✓ | plugins/smart_editing.cljc | 100%

**Overall Coverage**: 19/20 features complete = 95% (drag-drop UI needs implementation)

---

## Architecture Quality Observations

### Strengths

1. **Pure Kernel**: Three-op design is elegant and composable
   - All operations reduce to: create, place, update
   - No side effects in kernel layer
   - Fully auditable (all ops as EDN)

2. **Unified Intent System**: Single entry point for all state changes
   - Plugins register intent handlers
   - Intent → Ops → Transaction pipeline
   - No scattered event handlers

3. **Transaction Pipeline**: Robust validation and normalization
   - Prevents invalid operations
   - Catches cycles, missing references
   - Merges adjacent updates automatically

4. **Query Layer**: Centralized read access
   - Single source of truth for data access
   - Consistent patterns across codebase
   - Easy to optimize (would change one place)

5. **Session State Separation**: Clear ephemeral vs. persistent distinction
   - Editing/fold/zoom not in history (good UX)
   - Selection is persistent (supports undo of selections)
   - Two session nodes (selection + ui) separate concerns

6. **Anchor Algebra**: Deterministic positioning system
   - Supports `:first`, `:last`, `{:before id}`, `{:after id}`
   - Handles remove-before-place semantics correctly
   - All errors thrown with machine-parsable reasons

### Areas for Growth

1. **Drag-and-drop**: Core logic complete, UI layer needs implementation
   - Would be straightforward: add ondrag handlers, call `:move` intent
   - Drop zone detection is complex (Logseq uses detailed zone mapping)

2. **Performance**: Not optimized for large graphs
   - Re-derives all indexes on every transaction
   - Would need caching/incremental updates for 10k+ blocks

3. **Collaboration**: No multi-user support
   - Event sourcing makes this tractable
   - Would need OT/CRDT for concurrent edits

4. **Testing**: Property-based test suite is empty
   - Codebase is test-ready (pure functions, composable)
   - Tests would catch edge cases (e.g., invalid anchors)

---

## How to Use This Analysis

### For Implementation
1. Study `/Users/alien/Projects/evo/EVO_ARCHITECTURE_ANALYSIS.md` for complete details
2. Each feature has file paths and line numbers
3. Plugins show how to register new intents
4. Use keymap/bindings_data.cljc to add shortcuts

### For Testing Against Specification
1. Compare your spec against the "Feature Implementation Mapping" section
2. Each feature shows exactly where it's implemented
3. Follow the "How it works" code examples
4. Use REPL debugging patterns from "Developer Workflow"

### For Learning the Architecture
1. Start with the three operations (kernel/ops.cljc)
2. Read the transaction pipeline (kernel/transaction.cljc)
3. Study one plugin (e.g., plugins/selection.cljc)
4. Trace a feature end-to-end (keymap → intent → ops → db)

---

## Key Files to Study

### Must-Read (In Order)
1. `kernel/ops.cljc` - The three operations (foundation)
2. `kernel/intent.cljc` - Intent registry (how to add features)
3. `kernel/transaction.cljc` - Transaction pipeline (validation/apply)
4. `plugins/struct.cljc` - Structural editing (most complex plugin)
5. `shell/blocks_ui.cljs` - Event dispatch and rendering

### Nice-to-Have
- `kernel/db.cljc` - Database shape and derivation
- `kernel/query.cljc` - All read-only access patterns
- `components/block.cljs` - Component implementation (with refs/embeds)
- `keymap/bindings_data.cljc` - All keyboard shortcuts
- `plugins/selection.cljc` - Multi-selection logic

---

## Command Reference

### Enable Debug Logging
```clojure
;; In REPL
(set! (.-log js/console) js/console.log)
;; Check browser console for "Intent:" logs
```

### REPL Testing
```clojure
(require '[kernel.db :as db] '[kernel.api :as api])
(def db (db/empty-db))

;; Test indent
(def r (api/dispatch db {:type :indent :id "some-id"}))
(def db (:db r))
(:issues r)  ; nil means success
```

### Hot Reload Keymap
```clojure
(require '[keymap.bindings] :reload)
(keymap.bindings/reload!)
```

---

## Summary

The EVO project is a well-architected outliner that implements your specification almost completely:

- ✓ All core operations (move, indent, outdent, split, merge)
- ✓ Multi-selection with ranges
- ✓ Fold/zoom hierarchy navigation
- ✓ Caret positioning with boundary detection
- ✓ Text formatting
- ◐ Drag-drop (logic ready, UI needed)

The three-op kernel is elegant and the plugin system makes adding features straightforward. The transaction pipeline prevents invalid states. The code is pure, testable, and REPL-friendly.

Ready to dive deeper or implement the drag-drop UI?
