# Feature Audit: What Actually Works

**Date:** 2025-10-24
**Status:** Current state of implemented features

## Summary

**Core System:** ✅ Fully working (157 tests passing)
**Block Navigation:** ✅ Implemented and tested
**Block Editing:** ⚠️ Partial (structural editing only, no text editing)
**Plugins:** ✅ 6 plugins working

---

## ✅ Fully Implemented

### Core 3-Operation Kernel

**File:** `src/core/interpret.cljc`
**Status:** ✅ Complete, tested (651 assertions)

Operations:
- `:create-node` - Create new nodes
- `:place` - Place nodes in tree (before/after/first/last)
- `:update-node` - Update node properties

Features:
- Event sourcing (all changes are immutable ops)
- Transaction pipeline (normalize → validate → apply)
- Derived state computation (parent-of, siblings, traversal)
- Error handling with detailed issues

### History (Undo/Redo)

**File:** `src/core/history.cljc`
**Status:** ✅ Working in UI

Keyboard shortcuts:
- `Cmd+Z` - Undo
- `Cmd+Shift+Z` - Redo

Implementation:
- Stores snapshots before structural changes
- Works with intent system
- Tested in `blocks_ui.cljs`

### Intent Router (ADR-016)

**File:** `src/core/intent.cljc`
**Status:** ✅ Implemented, dual dispatch working

Architecture:
- `intent->ops` - Structural intents → core operations
- `intent->db` - View intents → direct DB updates
- `apply-intent` - Unified entry point

Implemented intents: **12 total**

#### Structural Intents (→ ops)

**From:** `src/plugins/struct/core.cljc`

1. `:indent-selected` - Indent selected blocks
2. `:outdent-selected` - Outdent selected blocks
3. `:delete-selected` - Delete selected blocks
4. `:reorder/children` - Reorder children to explicit order
5. `:reorder/move-blocks` - Move blocks after pivot

#### View Intents (→ db)

**From:** `src/plugins/selection/core.cljc`

1. `:select` - Replace selection with node IDs
2. `:extend-selection` - Add to selection
3. `:deselect` - Remove from selection
4. `:clear-selection` - Clear all selection
5. `:toggle-selection` - Toggle node in/out of selection
6. `:select-next-sibling` - Navigate to next sibling
7. `:select-prev-sibling` - Navigate to previous sibling
8. `:extend-to-next-sibling` - Extend selection down
9. `:extend-to-prev-sibling` - Extend selection up
10. `:select-parent` - Select parent of selection
11. `:select-all-siblings` - Select all siblings

### Block Navigation

**Status:** ✅ Fully implemented and tested

**Keyboard shortcuts** (from `blocks_ui.cljs`):

Selection:
- `Click` - Select block
- `Shift+Click` - Extend selection
- `Alt+↓` - Select next sibling
- `Alt+↑` - Select previous sibling
- `Shift+↓` - Extend to next sibling
- `Shift+↑` - Extend to previous sibling

Structure:
- `Tab` - Indent selected blocks
- `Shift+Tab` - Outdent selected blocks
- `Cmd+Shift+↑` - Move block up
- `Cmd+Shift+↓` - Move block down
- `Backspace` - Delete selected blocks (when multiple selected)

**Features:**
- Multi-select (range selection)
- Focus tracking (which block is "current")
- Anchor tracking (selection start point)
- Visual feedback (blue highlight for focus, light blue for selection)

### Derived Indexes

**File:** `src/core/db.cljc`
**Status:** ✅ Working, computed after every transaction

Core indexes:
- `:parent-of` - Map child → parent
- `:index-of` - Map child → position in parent
- `:prev-id-of` - Map node → previous sibling
- `:next-id-of` - Map node → next sibling
- `:pre` - Pre-order traversal index
- `:post` - Post-order traversal index
- `:id-by-pre` - Reverse lookup (index → node)

### Plugin System

**File:** `src/plugins/registry.cljc`
**Status:** ✅ Working

Features:
- Plugin registration (`register!`, `unregister!`)
- Auto-run all plugins after transactions
- Merge results into `db[:derived]`
- Error isolation (one plugin crash doesn't fail others)

**Active plugins:** 6

1. **Selection** (`plugins.selection.core`)
   - Manages `:selection` state (not `:derived`)
   - Focus, anchor, multi-select
   - Selection navigation intents

2. **Struct** (`plugins.struct.core`)
   - First/last child indexes
   - Depth calculation
   - Indent/outdent/delete intents
   - Reordering intents

3. **Refs** (`plugins.refs.core`)
   - Typed references (`:link`, `:highlight`)
   - Outgoing refs per node
   - Backlinks grouped by kind
   - Citation counts
   - Dangling ref detection

4. **Siblings Order** (`plugins.siblings_order.cljc`)
   - (Minimal plugin for testing)

5. **Permute** (`plugins.permute.core`)
   - Permutation algebra for reordering
   - Used by struct plugin

6. **Registry** (meta-plugin)
   - Manages plugin lifecycle

### Catalog (NEW)

**File:** `src/plugins/catalog.cljc`
**Status:** ✅ Just created (2025-10-24)

Documents all derived data for LLM discovery:
- Core DB indexes (7 entries)
- Refs plugin data (5 entries)
- Selection state (3 entries)
- Struct plugin data (4 entries)
- Canonical data (3 entries)

---

## ⚠️ Partially Implemented

### Block Editing (Text)

**Status:** ❌ NOT implemented

What works:
- ✅ Structural editing (move, indent, outdent, delete)
- ✅ Update node properties via `:update-node` op

What's missing:
- ❌ Text input/editing UI
- ❌ Cursor position tracking
- ❌ Enter to create new block
- ❌ Backspace at start to merge with previous
- ❌ Inline formatting (bold, italic, etc.)

**Current workaround:** Can only edit via REPL:
```clojure
(interpret! [{:op :update-node :id "a" :props {:text "New text"}}])
```

### Collapse/Expand

**Status:** ❌ NOT implemented

From `blocks_ui.cljs:138-143`:
```clojure
;; Collapse/Expand
(and mod? (= key "ArrowUp"))
(do (.preventDefault e)
    (js/console.log "Collapse (not implemented)"))
```

Keyboard shortcuts defined but no implementation.

### Search/Filter

**Status:** ❌ NOT implemented

No search functionality exists.

---

## ❌ Not Implemented (Proposals Only)

### Query/Render Component Pattern

**File:** `.architect/proposals/query-render-component-pattern.md`
**Status:** Proposal only, no implementation

### Component Manifests (Codex Pattern)

**File:** `.architect/adr/ADR-014-component-discovery-via-catalog.md`
**Status:** Rejected in ADR

### Auto-wiring/Resolution Layer

**Status:** Explicitly rejected (ADR-014)

---

## Test Coverage

**Total:** 157 tests, 651 assertions, 0 failures

**By module:**
- `core.db` - Derived indexes
- `core.interpret` - Transaction pipeline (comprehensive)
- `core.history` - Undo/redo
- `core.schema` - Validation
- `plugins.selection` - Selection state
- `plugins.struct` - Structural editing
- `plugins.refs` - References and backlinks
- `plugins.permute` - Permutation algebra
- Integration tests - Edge cases, plugin interactions

---

## UI Demo Status

**File:** `src/app/blocks_ui.cljs`
**Status:** ✅ Working demo

**What you can do:**
1. Click blocks to select
2. Shift+click to extend selection
3. Use keyboard shortcuts (see navigation section)
4. See visual feedback (selection highlighting)
5. Undo/redo structural changes
6. View debug panel (selection state, undo/redo status)

**What you can't do:**
1. Edit text (no contenteditable)
2. Create new blocks (no Enter key)
3. Merge blocks (no Backspace at start)
4. Collapse/expand (not implemented)
5. Search/filter blocks

---

## Logseq Feature Parity

**Reference:** Logseq E2E tests (`~/Projects/best/logseq/clj-e2e/test/`)

### Core Outliner Features

| Feature | Logseq | Evo | Notes |
|---------|--------|-----|-------|
| **Block Creation** | ✅ | ❌ | Logseq: Enter key creates new block |
| **Text Editing** | ✅ | ❌ | Logseq: contenteditable with cursor tracking |
| **Indent (Tab)** | ✅ | ✅ | **Same** - indent block with children |
| **Outdent (Shift+Tab)** | ✅ | ✅ | **Same** - outdent block with children |
| **Move Up (Meta+Shift+↑)** | ✅ | ✅ | **Same** - move selected blocks up |
| **Move Down (Meta+Shift+↓)** | ✅ | ✅ | **Same** - move selected blocks down |
| **Delete (Backspace)** | ✅ | ✅ | **Same** - delete selected blocks |
| **Merge Blocks (Delete at end)** | ✅ | ❌ | Logseq: Delete at end merges with next |
| **Multi-select (Shift+↑/↓)** | ✅ | ✅ | **Same** - range selection |
| **Undo/Redo (Cmd+Z)** | ✅ | ✅ | **Same** - undo structural changes |

### Selection & Navigation

| Feature | Logseq | Evo | Notes |
|---------|--------|-----|-------|
| **Click to select** | ✅ | ✅ | **Same** |
| **Shift+Click extend** | ✅ | ✅ | **Same** |
| **Arrow Up/Down** | ✅ | ✅ (Alt+) | Different shortcuts |
| **Shift+Up/Down** | ✅ | ✅ | **Same** - extend selection |
| **Select all siblings** | ✅ | ✅ | Evo: implemented but no shortcut |
| **Select parent** | ✅ | ✅ | Evo: implemented but no shortcut |

### Block Features

| Feature | Logseq | Evo | Notes |
|---------|--------|-----|-------|
| **Collapse/Expand** | ✅ | ❌ | Logseq: Cmd+↑/↓ |
| **References (data)** | ✅ | ✅ | **Same** - typed refs (`:link`, `:highlight`) |
| **Backlinks (data)** | ✅ | ✅ | **Same** - grouped by kind |
| **Citations count** | ✅ | ✅ | **Same** - in derived indexes |
| **Tags/Properties** | ✅ | ❌ | Logseq: toggle properties, set tags |
| **Convert to page** | ✅ | ❌ | Logseq: `#Page` tag |

### Page Management

| Feature | Logseq | Evo | Notes |
|---------|--------|-----|-------|
| **Create page** | ✅ | ❌ | |
| **Navigate pages** | ✅ | ❌ | |
| **Page embed** | ✅ | ❌ | |
| **Move blocks to page** | ✅ | ❌ | Logseq: Cmd+Shift+M |

### Advanced Features

| Feature | Logseq | Evo | Notes |
|---------|--------|-----|-------|
| **Search** | ✅ | ❌ | |
| **Graph view** | ✅ | ❌ | |
| **Daily notes** | ✅ | ❌ | |
| **Templates** | ✅ | ❌ | |
| **Queries** | ✅ | ❌ | |
| **Linked references UI** | ✅ | ❌ | Have data, no UI |

---

## Key Insights from Logseq Tests

### Indent/Outdent Behavior

**From:** `outliner_basic_test.clj:31-48`

Logseq indents/outdents **block with all its children**:
- Indent b2 → b2 and b3 both indent
- Outdent b2 → b2, b3, b4, b5 all outdent together

**Evo:** ✅ **Same behavior** (see `plugins.struct.core/indent-ops`)

### Move Up/Down Behavior

**From:** `outliner_basic_test.clj:67-77`

Logseq moves selected blocks while preserving structure:
- Select b3, b4 → Move up 2 times → [b3 b4 b1 b2]
- Children move with parents

**Evo:** ✅ **Same behavior** (see `:reorder/move-blocks` intent)

### Delete Behavior

**From:** `outliner_basic_test.clj:96-105`

Logseq deletes block **with all children**:
- Delete b2 (which has children b3, b4) → deletes all three

**Evo:** ✅ **Same behavior** (see `:delete-selected` intent)

### Multi-select Behavior

**From:** `outliner_basic_test.clj:69-74`

Logseq uses `Shift+ArrowUp/Down` for range selection:
- Start at b4
- Shift+Up twice → selects b4, b3, b2
- Operations apply to all selected

**Evo:** ✅ **Same behavior** (see selection plugin)

---

## What Evo Actually Needs for Solo Use

Based on Logseq feature set and tests:

### Priority 1: Text Editing (BLOCKER)
- ❌ Contenteditable blocks
- ❌ Cursor position tracking
- ❌ Enter to create new block
- ❌ Backspace/Delete to merge blocks
- ❌ Text input handling

**Without this, it's not usable.**

### Priority 2: Collapse/Expand
- ❌ Store collapsed state
- ❌ Hide/show children
- ❌ Keyboard shortcuts (Cmd+↑/↓)

**Needed for large outlines (>20 blocks).**

### Priority 3: Page Management
- ❌ Multiple pages
- ❌ Page navigation
- ❌ Create/delete pages

**Needed for organizing multiple documents.**

---

## What Evo Does Better

1. **Event Sourcing Architecture**
   - Logseq has event sourcing now (db-based mode) but built later
   - Evo: designed event-sourced from start

2. **Pure 3-Operation Kernel**
   - Logseq: larger operation set, more complexity
   - Evo: minimal kernel (create, place, update), everything else derives

3. **Plugin Architecture**
   - Logseq: monolithic with plugin API bolted on
   - Evo: plugins are first-class (selection, refs, struct all plugins)

4. **Test Coverage**
   - Logseq: E2E tests via browser automation (slower)
   - Evo: Pure function tests (faster, 157 tests run in <2s)

5. **Intent Router Pattern**
   - Logseq: event handlers spread across codebase
   - Evo: unified intent->ops and intent->db multimethods

---

## Conclusion (Updated)

**What Evo has:** Solid foundation with better architecture than Logseq.

**What Evo needs:** Basic text editing to be usable. Everything else is implementation, not architecture.

**Recommendation:**
1. Implement contenteditable text editing (1-2 days)
2. Add collapse/expand (half day)
3. Then evaluate if query/render pattern is needed based on real UI complexity

**Don't bikeshed component patterns** until you have working text editing!

---

## Next Steps (If Building for Solo Use)

### Priority 1: Basic Text Editing

**Required for solo use:**
1. Make blocks contenteditable
2. Handle Enter to create new block
3. Handle Backspace at start to merge
4. Update `:text` property on blur

**Estimated work:** 1-2 days

### Priority 2: Collapse/Expand

**Required for large outlines:**
1. Add `:collapsed?` to node properties
2. Implement intents (`:collapse`, `:expand`)
3. Update render to hide collapsed children
4. Add keyboard shortcuts (Cmd+↑/↓)

**Estimated work:** Half day

### Priority 3: Page Navigation

**Required for multiple documents:**
1. Create page list view
2. Navigate between pages
3. Create new pages
4. Link between pages

**Estimated work:** 1-2 days

---

## Conclusion

**What actually works:** Core system (operations, intents, plugins) is solid and well-tested.

**What's missing for solo use:** Text editing is the main blocker. Without contenteditable, you can only modify structure, not content.

**Component architecture discussion:** The catalog-only pattern is documented but hasn't been stress-tested with real UI components yet. The `blocks_ui.cljs` example uses simple inline functions, which works fine.

**Recommendation:** Before designing complex component patterns (query/render, manifests, etc.), implement basic text editing to validate the system with real usage.
