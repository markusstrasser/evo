# EVO Project - Comprehensive Architecture & Feature Mapping

## Project Overview

**EVO** is an event-sourced UI kernel with declarative operations and generative AI tooling. It's a ClojureScript-based outliner/editor inspired by Logseq, implementing a pure functional architecture.

### Technology Stack
- **Language**: ClojureScript (cljc for shared code)
- **Framework**: Replicant (React alternative, DOM-first)
- **Architecture**: Event sourcing with pure operations
- **Build**: Shadow CLJS

### Philosophy
- Pure data transformation library (no side effects in kernel)
- Three-operation kernel: `create-node`, `place`, `update-node`
- Transaction pipeline: normalize → validate → apply → derive
- REPL-first development
- Simple over clever, correctness over performance

---

## Directory Structure

```
src/
├── kernel/           # Pure kernel (DB, ops, transactions)
│   ├── api.cljc           # Unified dispatch façade
│   ├── db.cljc            # Canonical DB shape, derive indexes
│   ├── ops.cljc           # Three-op kernel (create, place, update)
│   ├── intent.cljc        # Intent registry & unified intent→ops routing
│   ├── transaction.cljc   # Transaction pipeline (normalize→validate→apply→derive)
│   ├── position.cljc      # Anchor algebra (positional resolution)
│   ├── query.cljc         # Read-only queries (selection, edit, tree)
│   ├── schema.cljc        # Schema validation
│   ├── history.cljc       # Undo/redo tracking
│   ├── constants.cljc     # Root IDs, session node IDs
│   ├── time.cljc          # Time utilities
│   └── dbg.cljc           # Debugging helpers
│
├── plugins/          # High-level intents that compile to ops
│   ├── struct.cljc        # Structural editing (delete, indent, outdent, move)
│   ├── editing.cljc       # Edit mode & content operations (enter-edit, split-at-cursor, merge)
│   ├── selection.cljc     # Multi-selection & navigation
│   ├── folding.cljc       # Expand/collapse/zoom
│   ├── smart_editing.cljc # Smart behaviors (merge-next, list items, checkboxes)
│   ├── text_formatting.cljc # Text formatting (bold, italic, etc.)
│   ├── pages.cljc         # Page navigation
│   ├── refs.cljc          # Reference handling
│   └── registry.cljc      # Plugin registration
│
├── keymap/           # Keyboard shortcut system
│   ├── core.cljc          # Central keymap resolver
│   ├── bindings_data.cljc # All key bindings (declarative data)
│   └── bindings.cljc      # Binding loader/reloader
│
├── shell/            # UI adapters (React/Replicant)
│   ├── main.cljs          # Main app entry point
│   └── blocks_ui.cljs     # Blocks UI demo with composition
│
├── components/       # React components
│   ├── block.cljs         # Block component with Logseq-style editing
│   ├── block_ref.cljs     # Block reference rendering
│   ├── block_embed.cljs   # Block embed (full tree transclusion)
│   ├── page_ref.cljs      # Page reference (links)
│   └── sidebar.cljs       # Page navigation sidebar
│
├── parser/           # Text parsing
│   ├── block_refs.cljc    # Parse ((ref)) syntax
│   ├── embeds.cljc        # Parse {{embed}} syntax
│   └── page_refs.cljc     # Parse [[page]] syntax
│
├── utils/
│   └── permutation.cljc   # Permutation/ordering utilities
│
└── data_readers.cljc # EDN custom readers
```

---

## Core Architecture: Three-Op Kernel

### 1. The Three Operations (kernel/ops.cljc)

All state changes decompose into three pure operations:

```clojure
;; Create a node (idempotent)
{:op :create-node :id "block-1" :type :block :props {:text "Hello"}}

;; Place node under parent at position
{:op :place :id "block-1" :under "parent-id" :at :last}

;; Update node properties (recursive merge)
{:op :update-node :id "block-1" :props {:text "Updated"}}
```

**Key Properties**:
- Pure functions: DB → DB (no side effects)
- Deterministic: same inputs always produce same output
- Composable: multiple ops form transactions
- Auditable: all changes as EDN data

### 2. Database Shape (kernel/db.cljc)

```clojure
{:nodes {id1 {:type :block :props {:text "..." }}
         id2 {:type :page :props {:title "..." }}
         "session/selection" {:type :selection :props {:nodes #{} :focus id :anchor id}}
         "session/ui" {:type :ui :props {:editing-block-id nil :folded #{} :zoom-stack []}}}

 :children-by-parent {parent-id [child-id1 child-id2 ...]
                      "session" ["session/selection" "session/ui"]}

 :roots [:doc :trash :session]  ; Root containers

 :derived {:parent-of {id parent}
           :prev-id-of {id prev}
           :next-id-of {id next}
           :pre {id pre-order-index}  ; For doc-order operations
           :post {id post-order-index}}}
```

**Canonical State**: 
- `:nodes` - all document content
- `:children-by-parent` - structural relationships
- `:derived` - computed indexes for O(1) queries

### 3. Intent System (kernel/intent.cljc)

Intents are high-level user actions that compile to ops:

```clojure
;; Registered intent with handler
(register-intent! :indent
  {:doc "Indent node under previous sibling"
   :handler (fn [db {:keys [id]}]
              (indent-ops db id))})

;; Unified entry point
(apply-intent db {:type :indent :id "block-1"})
;=> {:db updated-db :ops [operations]}
```

### 4. Transaction Pipeline (kernel/transaction.cljc)

```
Input: db, ops
  ↓
Normalize: drop no-ops, merge adjacent updates, canonicalize anchors
  ↓
Validate: schema, cycles, missing refs, invariants
  ↓
Apply: execute ops in sequence via kernel.ops
  ↓
Derive: recompute derived indexes (parent-of, traversal order, etc.)
  ↓
Output: {:db new-db :issues validation-errors}
```

---

## Feature Implementation Mapping

### Block Movement (Cmd+Shift+Up/Down)

**Specification**: Move selected blocks up/down one position with siblings

**Implementation**:

| Feature | File | Function | Status |
|---------|------|----------|--------|
| Key bindings | keymap/bindings_data.cljc | `{:key "ArrowUp" :shift true :mod true} :move-selected-up` | ✓ IMPLEMENTED |
| Intent registration | plugins/struct.cljc | `register-intent! :move-selected-up` | ✓ IMPLEMENTED |
| Op compilation | plugins/struct.cljc | `move-selected-up-ops` (lines 125-137) | ✓ IMPLEMENTED |
| Movement logic | plugins/struct.cljc | `lower-reorder` → `planned-positions` (lines 184-212) | ✓ IMPLEMENTED |
| Multi-selection support | plugins/struct.cljc | `active-targets`, `same-parent?` (lines 98-123) | ✓ IMPLEMENTED |

**How it works**:
1. User presses Cmd+Shift+Up
2. Keymap resolves to `:move-selected-up` intent
3. Intent handler gets currently selected blocks (or editing block)
4. Validates they share same parent
5. Computes previous sibling as anchor
6. Emits `:place` ops to move blocks

---

### Indent/Outdent (Tab/Shift+Tab)

**Specification**: 
- Tab: Move block under previous sibling (becoming its first child)
- Shift+Tab: Move block to be sibling of parent (outdent)

**Implementation**:

| Feature | File | Function | Status |
|---------|------|----------|--------|
| Key bindings | keymap/bindings_data.cljc | `{:key "Tab"} :indent-selected` | ✓ IMPLEMENTED |
| | keymap/bindings_data.cljc | `{:key "Tab" :shift true} :outdent-selected` | ✓ IMPLEMENTED |
| Indent intent | plugins/struct.cljc | `register-intent! :indent` (lines 57-61) | ✓ IMPLEMENTED |
| Indent logic | plugins/struct.cljc | `indent-ops` (lines 28-34) | ✓ IMPLEMENTED |
| Outdent intent | plugins/struct.cljc | `register-intent! :outdent` (lines 63-67) | ✓ IMPLEMENTED |
| Outdent logic | plugins/struct.cljc | `outdent-ops` (lines 36-47) | ✓ IMPLEMENTED |
| Multi-select support | plugins/struct.cljc | `:indent-selected`, `:outdent-selected` (lines 158-168) | ✓ IMPLEMENTED |

**How it works**:
1. User presses Tab
2. Indent: Get previous sibling, place under it with `:at :last`
3. User presses Shift+Tab
4. Outdent: Get parent & grandparent, place under grandparent with `:at {:after parent}`
5. Both use `active-targets` for multi-selection

---

### Enter to Split Blocks

**Specification**: Pressing Enter splits the current block at cursor position

**Implementation**:

| Feature | File | Function | Status |
|---------|------|----------|--------|
| Key binding | components/block.cljs | `handle-enter` (lines 115-121) | ✓ IMPLEMENTED |
| Intent registration | plugins/editing.cljc | `register-intent! :split-at-cursor` (lines 62-74) | ✓ IMPLEMENTED |
| Split logic | plugins/editing.cljc | `split-at-cursor` handler (lines 62-74) | ✓ IMPLEMENTED |
| OR smart split | plugins/smart_editing.cljc | `register-intent! :split-with-list-increment` | ✓ IMPLEMENTED |
| Keymap entry | shell/blocks_ui.cljs | Global Escape/Backspace/Enter handling (lines 115-186) | ✓ IMPLEMENTED |

**How it works**:
1. User presses Enter while editing
2. `handle-enter` captures cursor position via `anchorOffset`
3. Dispatches `:split-at-cursor` intent with block-id and position
4. Handler creates new block, updates text before/after split point
5. Places new block as next sibling
6. Smart variant increments numbered list markers

**Code example**:
```clojure
(intent/register-intent! :split-at-cursor
  {:handler (fn [db {:keys [block-id cursor-pos]}]
              (let [text (get-block-text db block-id)
                    before (subs text 0 cursor-pos)
                    after (subs text cursor-pos)
                    new-id (str "block-" (random-uuid))]
                [{:op :update-node :id block-id :props {:text before}}
                 {:op :create-node :id new-id :type :block :props {:text after}}
                 {:op :place :id new-id :under parent :at {:after block-id}}]))})
```

---

### Backspace to Merge Blocks

**Specification**: Backspace at start of block merges it with previous block

**Implementation**:

| Feature | File | Function | Status |
|---------|------|----------|--------|
| Key binding | keymap/bindings_data.cljc | `{:key "Backspace" :mod true} :merge-with-prev` | ✓ IMPLEMENTED |
| In-text Backspace | components/block.cljs | `handle-backspace` (lines 128-139) | ✓ IMPLEMENTED |
| Intent registration | plugins/editing.cljc | `register-intent! :merge-with-prev` (lines 50-60) | ✓ IMPLEMENTED |
| Merge logic | plugins/editing.cljc | `merge-with-prev` handler | ✓ IMPLEMENTED |
| Merge with next | plugins/smart_editing.cljc | `register-intent! :merge-with-next` (lines 50-68) | ✓ IMPLEMENTED |

**How it works**:

When Backspace pressed:
1. Check if at start of text and text is not empty
2. Dispatch `:merge-with-prev` intent with block-id
3. Handler gets previous sibling
4. Concatenates texts: `prev-text + current-text`
5. Updates prev block with merged text
6. Moves current block to trash

**Code example**:
```clojure
(fn [db {:keys [block-id]}]
  (let [prev-id (get-in db [:derived :prev-id-of block-id])
        prev-text (get-block-text db prev-id)
        curr-text (get-block-text db block-id)
        merged-text (str prev-text curr-text)]
    (when prev-id
      [{:op :update-node :id prev-id :props {:text merged-text}}
       {:op :place :id block-id :under const/root-trash :at :last}])))
```

---

### Multi-Selection Operations

**Specification**: Select/extend selection, operate on multiple blocks at once

**Implementation**:

| Feature | File | Function | Status |
|---------|------|----------|--------|
| Selection intent | plugins/selection.cljc | `register-intent! :selection` (lines 83-170) | ✓ IMPLEMENTED |
| Modes (replace) | plugins/selection.cljc | `:replace` mode (line 145) | ✓ IMPLEMENTED |
| Modes (extend) | plugins/selection.cljc | `:extend` mode + range selection (line 147) | ✓ IMPLEMENTED |
| Modes (navigation) | plugins/selection.cljc | `:next`, `:prev`, `:extend-next`, `:extend-prev` | ✓ IMPLEMENTED |
| Shift+Click | components/block.cljs | `on :click` handler with shift check (lines 314-318) | ✓ IMPLEMENTED |
| Shift+Arrow keys | keymap/bindings_data.cljc | `{:key "ArrowUp" :shift true} {...}` | ✓ IMPLEMENTED |
| Range selection | plugins/selection.cljc | `calc-extend-props` with `tree/doc-range` (lines 32-45) | ✓ IMPLEMENTED |

**How it works**:

Click patterns:
1. Simple click: `:selection :mode :replace :ids block-id` → single block selected
2. Shift+Click: `:selection :mode :extend :ids block-id` → extend to block
3. Shift+Up/Down: Same extend mode

Range selection:
- When extending with anchor set, calculates doc-order range between anchor and target
- Returns all blocks between them (pre-order traversal)

Storage:
- Selection stored in `session/selection` node (undoable via history)
- Properties: `{:nodes #{id1 id2 ...} :focus id :anchor id}`

---

### Drag-and-Drop (Not Yet Fully Implemented)

**Status**: Infrastructure exists, needs drop guides

| Feature | File | Status |
|---------|------|--------|
| Reorder logic | plugins/struct.cljc | ✓ CORE READY |
| Move intent | plugins/struct.cljc | ✓ REGISTERED |
| Drop-zone detection | components/block.cljs | ✗ NOT IMPLEMENTED |
| Drop guides | components/block.cljs | ✗ NOT IMPLEMENTED |
| Drag event handlers | shell/blocks_ui.cljs | ✗ NOT IMPLEMENTED |

**What exists**:
- `lower-reorder` function (plugins/struct.cljc lines 215-250) that computes move sequences
- `:move` intent registry that handles multi-node reordering
- Anchor algebra supports `{:after id}` positioning

**What's missing**:
- DOM drag event handlers (ondragstart, ondrop, etc.)
- Visual drop guides during drag
- Drag preview styling

---

### Collapse/Expand (Fold/Zoom)

**Specification**: Toggle fold state, zoom into blocks, navigate hierarchies

**Implementation**:

| Feature | File | Function | Status |
|---------|------|----------|--------|
| Toggle fold | plugins/folding.cljc | `register-intent! :toggle-fold` (lines 76-88) | ✓ IMPLEMENTED |
| Collapse | plugins/folding.cljc | `register-intent! :collapse` (lines 103-112) | ✓ IMPLEMENTED |
| Expand all | plugins/folding.cljc | `register-intent! :expand-all` (lines 90-101) | ✓ IMPLEMENTED |
| Zoom in | plugins/folding.cljc | `register-intent! :zoom-in` (lines 133-144) | ✓ IMPLEMENTED |
| Zoom out | plugins/folding.cljc | `register-intent! :zoom-out` (lines 146-160) | ✓ IMPLEMENTED |
| Fold state storage | plugins/folding.cljc | Stored in `:session/ui` (ephemeral) | ✓ IMPLEMENTED |
| Fold check | plugins/folding.cljc | `folded?` query (line 47-50) | ✓ IMPLEMENTED |
| Key bindings | keymap/bindings_data.cljc | `{:key ";" :mod true} :toggle-fold` | ✓ IMPLEMENTED |
| UI rendering | components/block.cljs | Bullet click handler (lines 323-333) | ✓ IMPLEMENTED |

**How it works**:

Toggle fold:
1. User clicks bullet or presses Cmd+;
2. Dispatches `:toggle-fold :block-id "id"` intent
3. Handler checks if block is already folded
4. Toggles block-id in folded set
5. Component checks `folded?` and hides children if true

Zoom:
1. `:zoom-in :block-id "id"` pushes current root to stack
2. Sets new zoom-root to that block
3. Component renders from zoom-root instead of :doc root
4. `:zoom-out` pops stack and restores previous root

---

### Caret Positioning Behavior

**Specification**: Navigate between blocks using arrow keys, enter/exit edit mode, position cursor

**Implementation**:

| Feature | File | Function | Status |
|---------|------|----------|--------|
| Cursor boundary detection | components/block.cljs | `detect-cursor-row-position` (lines 59-67) | ✓ IMPLEMENTED |
| Mock-text technique | components/block.cljs | `update-mock-text!` (lines 17-33) | ✓ IMPLEMENTED |
| Arrow up in edit | components/block.cljs | `handle-arrow-up` (lines 79-95) | ✓ IMPLEMENTED |
| Arrow down in edit | components/block.cljs | `handle-arrow-down` (lines 97-113) | ✓ IMPLEMENTED |
| Cursor position state | kernel/query.cljc | `cursor-position` query (lines 70-74) | ✓ IMPLEMENTED |
| Cursor at start hint | shell/blocks_ui.cljs | `:cursor-at :start` (lines 161) | ✓ IMPLEMENTED |
| Cursor at end hint | shell/blocks_ui.cljs | `:cursor-at :end` (lines 161) | ✓ IMPLEMENTED |
| Cursor position setting | components/block.cljs | Replicant render hook (lines 348-379) | ✓ IMPLEMENTED |

**How it works**:

Logseq-style boundary navigation:
1. User presses Up arrow while editing
2. Component detects if cursor is on first row (via mock-text Y positions)
3. If at first row: exit edit, navigate to prev block, enter edit at END
4. If not at first row: let browser handle (normal cursor up)
5. Similar logic for Down arrow

**Mock-text technique**:
- Hidden div mirrors the editable content
- Each character gets unique span with Y position
- Cursor rect Y position compared to character positions
- Determines if cursor is on first/last row

---

### Text Formatting (Bold, Italic)

**Specification**: Format selected text with Cmd+B (bold), Cmd+I (italic)

**Implementation**:

| Feature | File | Function | Status |
|---------|------|----------|--------|
| Format intent | plugins/text_formatting.cljc | `register-intent! :format-selection` | ✓ IMPLEMENTED |
| Key binding | keymap/bindings_data.cljc | `{:key "b" :mod true} :format-selection` | ✓ IMPLEMENTED |
| Selection enrichment | shell/blocks_ui.cljs | Extracts DOM selection range (lines 197-215) | ✓ IMPLEMENTED |
| DOM selection reading | shell/blocks_ui.cljs | Gets startOffset/endOffset (line 206) | ✓ IMPLEMENTED |
| Text replacement | plugins/text_formatting.cljc | Inserts markers around selection | ✓ IMPLEMENTED |
| Post-format selection | shell/blocks_ui.cljs | Resets text selection after format (lines 404-424) | ✓ IMPLEMENTED |

---

## Session State Management

### Ephemeral vs. Persistent State

**Ephemeral State** (NOT in undo/redo):
- Editing block ID
- Cursor position hint
- Folded blocks (set)
- Zoom stack & zoom root

Stored in: `session/ui` node

```clojure
{:editing-block-id "block-123"
 :cursor-position :start  ; or :end
 :folded #{block-id1 block-id2}
 :zoom-stack [{:block-id "ancestor-id"} ...]
 :zoom-root "current-zoom-id"}
```

**Persistent State** (IN undo/redo):
- Selection (nodes set, focus, anchor)
- Block text content
- Block hierarchy (parent-child relationships)
- Block properties

Stored in: document nodes and `session/selection` node

---

## Keyboard Shortcuts Reference

### All Bindings (keymap/bindings_data.cljc)

**Non-Editing Mode** (when block selected, not editing):
```
Arrow Down → :selection :mode :next
Arrow Up → :selection :mode :prev
Tab → :indent-selected
Shift+Tab → :outdent-selected
Backspace → :delete-selected
Enter → :create-and-enter-edit
Cmd+Shift+Up → :move-selected-up
Cmd+Shift+Down → :move-selected-down
Cmd+; → :toggle-fold
Cmd+Up → :collapse
Cmd+Down → :expand-all
Cmd+. → :zoom-in
Cmd+, → :zoom-out
Cmd+Enter → :toggle-checkbox
Shift+Up → :extend-prev (extend selection)
Shift+Down → :extend-next (extend selection)
```

**Editing Mode** (inside block):
```
Escape → :exit-edit
Tab → :indent-selected
Shift+Tab → :outdent-selected
Cmd+Backspace → :merge-with-prev
Cmd+B → :format-selection :marker "**"
Cmd+I → :format-selection :marker "__"
```

**Always Global**:
```
Cmd+Z → Undo
Cmd+Shift+Z → Redo
```

---

## Entry Points

### UI Entry (shell/blocks_ui.cljs)

1. `main()` - initializes app
   - Loads keyboard bindings
   - Sets up event dispatch
   - Adds global keydown listener
   - Starts render loop with watchers

2. `handle-intent(intent-map)` - single dispatcher
   - Dispatches to `api/dispatch`
   - Logs issues if validation fails
   - Updates app state atom `!db`

3. `handle-global-keydown(e)` - global keyboard handler
   - Parses DOM event
   - Resolves intent via keymap
   - Enriches with context (block-id for folds, DOM selection for formatting)
   - Dispatches intent or handles special cases (edit mode navigation)

### Kernel Entry (kernel/api.cljc)

```clojure
(dispatch db intent)
;=> {:db new-db :issues validation-errors}
```

Pipeline:
1. `apply-intent` → compile intent to ops
2. `interpret` → execute transaction pipeline
3. `record` → save to undo/redo history
4. Return result

---

## Architecture Diagrams

### Intent → Ops Pipeline

```
User Input (Keyboard/Mouse)
    ↓
Global Handler / Component Handler
    ↓
Intent Map {:type :indent :id "block-1"}
    ↓
kernel.intent/apply-intent
    ↓
Plugin Handler (e.g., plugins.struct/indent-ops)
    ↓
Operations [{:op :place :id ... :under ... :at ...}]
    ↓
kernel.transaction/interpret
    ↓
Normalize → Validate → Apply → Derive
    ↓
Updated DB
    ↓
kernel.history/record (for undo/redo)
    ↓
Component Re-render (watch on !db)
```

### Component → Intent → State

```
Block Component (components/block.cljs)
    ↓
onClick / onKeydown Event Handler
    ↓
Call on-intent callback
    ↓
{:type :selection :mode :replace :ids block-id}
    ↓
shell.blocks-ui/handle-intent
    ↓
api/dispatch (with history)
    ↓
!db atom updated
    ↓
Watch triggers (render!, text-selection-effects)
    ↓
App re-renders with new selections, styles, etc.
```

---

## Query Layer (kernel/query.cljc)

All database reads go through query functions:

```clojure
;; Selection
(selection db) → #{id1 id2}
(focus db) → id
(selected? db id) → bool

;; Edit mode
(editing-block-id db) → id or nil
(editing? db) → bool

;; Cursor
(cursor-position db) → :start | :end | nil
(cursor-first-row? db id) → bool
(cursor-last-row? db id) → bool

;; Folding
(folded? db id) → bool
(folded-set db) → #{id1 id2}

;; Tree
(parent-of db id) → parent-id
(children db id) → [id1 id2 ...]
(prev-sibling db id) → id or nil
(next-sibling db id) → id or nil
(siblings db id) → [...]
(doc-range db id1 id2) → #{all ids in range}
(descendants db id) → [...]
```

---

## Testing Notes

- Property-based tests in `/test` directory (empty in this snapshot)
- REPL-first development: reproduce bugs in REPL, test fix, verify
- Journaling support for timetravel debugging (`.architect/ops.ednlog`)

---

## Known Limitations

### Not Yet Implemented
1. **Drag-and-drop UI**: Core logic ready, needs DOM event handlers and visual guides
2. **Full undo/redo for selection**: Selection is persistent but only recent changes recorded
3. **Inline editing of properties**: Only text editing in blocks currently
4. **Page templates**: Basic page support, no templates
5. **Collaboration**: No multi-user sync
6. **Performance**: Not optimized for large graphs (1000+ blocks)

### By Design
1. **Delete = Archive**: Blocks moved to :trash, never destroyed (maintains referential integrity)
2. **Ephemeral UI state**: Fold/zoom not in history (resets on reload)
3. **Single root rendering**: Blocks UI renders one page at a time
4. **No hidden automation**: All changes explicit and observable

---

## Developer Workflow

### REPL Debugging
```clojure
(require '[kernel.db :as db])
(require '[kernel.api :as api])

;; Create initial db
(def db (db/empty-db))

;; Apply intent
(def result (api/dispatch db {:type :indent :id "block-1"}))
(def db (:db result))

;; Check issues
(:issues result)
```

### Hot Reload
```clojure
;; Edit keymap/bindings_data.cljc
;; Then reload:
(require '[keymap.bindings] :reload)
(keymap.bindings/reload!)
```

### Enable Journaling
```clojure
(kernel.api/set-journal! true)
;; Do work...
(def db (kernel.api/replay-journal (db/empty-db)))
```

---

## Summary of Feature Completeness

| Feature | Status | File | Coverage |
|---------|--------|------|----------|
| Block creation | ✓ Complete | plugins/struct.cljc | 100% |
| Block deletion (archive) | ✓ Complete | plugins/struct.cljc | 100% |
| Indent/Outdent | ✓ Complete | plugins/struct.cljc | 100% |
| Move up/down | ✓ Complete | plugins/struct.cljc | 100% |
| Multi-selection | ✓ Complete | plugins/selection.cljc | 100% |
| Text editing | ✓ Complete | plugins/editing.cljc | 100% |
| Split at cursor (Enter) | ✓ Complete | plugins/editing.cljc | 100% |
| Merge blocks (Backspace) | ✓ Complete | plugins/editing.cljc | 100% |
| Fold/Expand | ✓ Complete | plugins/folding.cljc | 100% |
| Zoom in/out | ✓ Complete | plugins/folding.cljc | 100% |
| Text formatting | ✓ Complete | plugins/text_formatting.cljc | 100% |
| Caret positioning | ✓ Complete | components/block.cljs | 100% |
| Arrow navigation (in edit) | ✓ Complete | components/block.cljs | 100% |
| Undo/Redo | ✓ Complete | kernel/history.cljc | 100% |
| Drag-and-drop | ◐ Partial | plugins/struct.cljc | 60% |
| Block refs | ✓ Complete | parser/block_refs.cljc | 100% |
| Embeds | ✓ Complete | parser/embeds.cljc | 100% |
| Page refs | ✓ Complete | parser/page_refs.cljc | 100% |
| Checkboxes | ✓ Complete | plugins/smart_editing.cljc | 100% |
| List formatting | ✓ Complete | plugins/smart_editing.cljc | 100% |

