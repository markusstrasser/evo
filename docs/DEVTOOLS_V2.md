# Enhanced DevTools V2 with Dataspex Integration

## Overview

The enhanced devtools provides a modern, interactive data inspection UI with proper tree views, diffs, and visual/DB mismatch detection. Built on top of [Dataspex](https://github.com/cjohansen/dataspex) for professional-grade data visualization.

## Features

### 1. Interactive Tree View
- **Expandable/collapsible nodes** - Click to drill down into nested data structures
- **Type-aware rendering** - Maps, vectors, and sequences displayed with appropriate icons
- **Path tracking** - Clear visual hierarchy with indentation and expand indicators
- **Lazy loading** - Only render visible nodes for performance

### 2. Enhanced Diff Visualization
- **Color-coded changes** - Green for additions, red for removals, yellow for modifications
- **Path-based diffs** - See exactly where in the data structure changes occurred
- **Before/after comparison** - Side-by-side tree views or focused diff view
- **Change summaries** - Human-readable descriptions of what changed

### 3. DB State Inspection
- **Key metrics dashboard** - Total nodes, blocks, pages, selection state
- **Operation timeline** - Chronological log of all intents and operations
- **Entry selection** - Click any log entry to inspect its before/after state
- **Multiple view modes** - Split view, diff-only, or tree-only

### 4. Visual/DB Mismatch Detection
- **Visual state extraction** - What the user *should* see
- **DOM state comparison** - What's *actually* rendered
- **Automatic mismatch detection** - Catch rendering bugs early
- **Debug helpers** - REPL functions for manual inspection

### 5. Dataspex Integration
- **One-click inspection** - Send current DB to Dataspex browser extension
- **Atom tracking** - Dataspex automatically watches DB changes
- **Audit trail** - Full history of DB mutations with diffs
- **Cross-browser support** - Works with Chrome and Firefox extensions

## Usage

### In the UI

The enhanced devtools panel appears at the bottom of the blocks-ui app:

1. **DB State View** - Current metrics (nodes, selection, editing state)
2. **Operation Log** - Timeline of all operations (click to inspect)
3. **Entry Inspector** - Before/after comparison with three view modes:
   - **Split View** - Side-by-side tree views
   - **Diff Only** - Focused changes list
   - **Tree Only** - Current state tree

### In the REPL

```clojure
;; Inspect DB in Dataspex browser extension
(dataspex/inspect "App DB" @!db)

;; Compute diff between two states
(require '[dev.data-diff :as dd])
(dd/compare-db-states db-before db-after)

;; Extract visual state
(dd/extract-visual-state db "page-id")

;; Compare visual vs DB state
(dd/compare-visual-states db-before db-after "page-id")

;; Detect DB/visual mismatch
(dd/detect-db-visual-mismatch db expected-visual "page-id")

;; Access from debug helpers
(DEBUG.state)  ; Get current DB
```

### From Browser Console

```javascript
// Access DB
DEBUG.state()

// Inspect in Dataspex
dataspex.core.inspect("My Data", DEBUG.state())

// Get log entries
DEBUG.summary()
```

## Architecture

### Namespaces

- **`components.devtools-v2`** - Enhanced UI components
  - `TreeView` - Recursive tree renderer with expand/collapse
  - `DiffView` - Change visualization with color coding
  - `DBStateView` - Current DB metrics dashboard
  - `EntryInspector` - Log entry detail view
  - `DevToolsPanelV2` - Main panel composition

- **`dev.data-diff`** - Data diffing and comparison utilities
  - `render-data-tree` - Tree rendering via Dataspex
  - `compute-diff` - Diff computation with editscript
  - `compare-db-states` - DB-specific diffing
  - `compare-visual-states` - Visual representation diffing
  - `detect-db-visual-mismatch` - Mismatch detection

- **`dev.tooling`** - Operation logging (existing)
  - `log-dispatch!` - Record operation with before/after
  - `get-log` - Retrieve log history
  - `format-intent` - Human-readable intent formatting

### Data Flow

```
User Action (e.g., keystroke)
    ↓
handle-intent (shell.blocks-ui)
    ↓
db-before captured
    ↓
api/dispatch (kernel.api)
    ↓
db-after computed
    ↓
dev/log-dispatch! (records before/after)
    ↓
Devtools render (shows diff)
    ↓
Dataspex atom watch (tracks changes)
```

## View Modes

### Split View (Default)
- Shows **before** and **after** states side-by-side
- Ideal for seeing structural changes
- Expandable tree navigation

### Diff Only
- Focused list of **changes**
- Color-coded by change type (add/remove/modify)
- Shows paths and values
- Best for understanding what changed

### Tree Only
- Shows **current state** as expandable tree
- Good for exploring DB structure
- No before/after comparison

## Debugging Workflow

### Finding Visual Bugs

1. Perform action in UI
2. Check devtools log for latest entry
3. Select entry to view before/after
4. Switch to **Diff Only** to see what changed
5. If visual doesn't match DB:
   - Check `compare-visual-states` in REPL
   - Use `detect-db-visual-mismatch` for automated detection

### Inspecting Complex State

1. Click "Inspect in Dataspex" button
2. Open browser DevTools → Dataspex panel
3. Navigate through data with Dataspex's rich UI
4. View full history and diffs in audit trail

### REPL-First Debugging

```clojure
;; Get current state
(def db (DEBUG.state))

;; Compare with expected
(def expected {...})
(dd/compute-diff expected db)

;; Extract visual tree
(dd/extract-visual-state db "page-id")

;; Send to Dataspex for deep inspection
(dataspex/inspect "Debug State" db)
```

## Performance Notes

- **Tree expansion is lazy** - Only visible nodes are rendered
- **Diffs are computed on-demand** - Not stored in log entries
- **Log is ring buffer** - Limited to 100 entries (see `dev.tooling/MAX-LOG-ENTRIES`)
- **Dataspex runs in-process** - No network overhead for local inspection

## Browser Extension Setup

### Chrome
1. Install [Dataspex Chrome Extension](https://chromewebstore.google.com/detail/dataspex/blgomkhaagnapapellmdfelmohbalneo)
2. Open DevTools → Dataspex panel
3. Data from `dataspex/inspect` appears automatically

### Firefox
1. Install [Dataspex Firefox Extension](https://addons.mozilla.org/en-US/firefox/addon/dataspex/)
2. Configure devtools settings (see Dataspex docs)
3. Open DevTools → Dataspex panel

## Comparison: Old vs New Devtools

| Feature | Old Devtools | Enhanced V2 |
|---------|-------------|-------------|
| Tree view | ❌ Plain text dump | ✅ Interactive expand/collapse |
| Diffs | ❌ Text comparison | ✅ Color-coded structured diff |
| Visual state | ❌ None | ✅ Visual tree extraction |
| Mismatch detection | ❌ Manual inspection | ✅ Automated detection |
| External tools | ❌ None | ✅ Dataspex integration |
| View modes | ❌ Single view | ✅ Split/Diff/Tree modes |
| REPL utilities | ✅ Basic | ✅ Enhanced with diff |
| Performance | ✅ Fast | ✅ Lazy rendering |

## Future Enhancements

- [ ] Time-travel debugging (scrub through history)
- [ ] Visual diff highlighting (show exactly what moved in UI)
- [ ] Performance profiling (operation timing)
- [ ] Export/import DB snapshots
- [ ] Query builder for DB inspection
- [ ] Custom formatters for domain types
- [ ] Integration with Playwright tests (visual regression)

## Dependencies

- **Dataspex** `2025.10.2` - Core data inspection library
- **Editscript** (via Dataspec) - Diff computation
- **Replicant** - UI rendering

## References

- [Dataspex GitHub](https://github.com/cjohansen/dataspex)
- [Dataspex 10-minute tour](https://youtu.be/5AKvD3nGCYY)
- [Editscript](https://github.com/juji-io/editscript) - Diff algorithm
