# Hotkey Bindings & UI Action Implementation Guide

## Architecture Overview

The hotkey and action system is built on three core layers:

1. **Keymap Layer** (`src/keymap/`) - Maps keyboard events to intent types
2. **Intent Layer** (`src/kernel/intent.cljc`) - Unified intent dispatch registry
3. **Plugin Layer** (`src/plugins/`) - Intent handlers that compile to operations
4. **UI Layer** (`src/shell/blocks-ui.cljs`, `src/components/block.cljs`) - Event listeners and dispatch

---

## Part 1: Hotkey Definitions

### Location: `/Users/alien/Projects/evo/src/keymap/bindings_data.cljc`

All keyboard bindings are defined in a single data structure organized by context:

```clojure
(def data
  {:non-editing [...] ;; Context: block navigation (not editing text)
   :editing [...] ;; Context: while editing block content
   :global [...]}) ;; Context: always active
```

#### Non-Editing Context (Navigation Mode)

These hotkeys work when blocks are selected but NOT in edit mode:

| Hotkey | Intent | Action |
|--------|--------|--------|
| `↑` Arrow Up | `{:type :selection :mode :prev}` | Move selection to previous sibling |
| `↓` Arrow Down | `{:type :selection :mode :next}` | Move selection to next sibling |
| `Alt+↑` Arrow Up | `{:type :selection :mode :prev}` | (Same as above) |
| `Alt+↓` Arrow Down | `{:type :selection :mode :next}` | (Same as above) |
| `Tab` | `:indent-selected` | Indent selected block(s) |
| `Shift+Tab` | `:outdent-selected` | Outdent selected block(s) |
| `Backspace` | `:delete-selected` | Delete selected block(s) to trash |
| `Enter` | `:create-and-enter-edit` | Create new block and enter edit mode |

#### Editing Context (Text Edit Mode)

These hotkeys work while editing block content:

| Hotkey | Intent | Action |
|--------|--------|--------|
| `Escape` | `:exit-edit` | Exit edit mode |
| `Tab` | `:indent-selected` | Indent the block (handled by global keymap) |
| `Shift+Tab` | `:outdent-selected` | Outdent the block (handled by global keymap) |
| `Cmd/Ctrl+Backspace` | `:merge-with-prev` | Merge with previous block |

#### Global Context (Always Active)

These hotkeys work in any mode:

**Multi-Selection & Navigation:**
| Hotkey | Intent | Action |
|--------|--------|--------|
| `Shift+↑` | `{:type :selection :mode :extend-prev}` | Extend selection to previous sibling |
| `Shift+↓` | `{:type :selection :mode :extend-next}` | Extend selection to next sibling |

**Block Movement:**
| Hotkey | Intent | Action |
|--------|--------|--------|
| `Cmd+Shift+↑` / `Alt+Shift+↑` | `:move-selected-up` | Move selected block(s) up one position |
| `Cmd+Shift+↓` / `Alt+Shift+↓` | `:move-selected-down` | Move selected block(s) down one position |

**Folding & Zoom:**
| Hotkey | Intent | Action |
|--------|--------|--------|
| `Cmd+;` | `{:type :toggle-fold}` | Toggle expand/collapse of focused block |
| `Cmd+↑` | `{:type :collapse}` | Collapse children of focused block |
| `Cmd+↓` | `{:type :expand-all}` | Recursively expand all descendants |
| `Cmd+.` | `{:type :zoom-in}` | Zoom into focused block |
| `Cmd+,` | `{:type :zoom-out}` | Zoom out to parent context |

**Smart Editing:**
| Hotkey | Intent | Action |
|--------|--------|--------|
| `Cmd+Enter` | `{:type :toggle-checkbox}` | Toggle checkbox state ([ ] ↔ [x]) |

**Undo/Redo (Direct Handling):**
| Hotkey | Action |
|--------|--------|
| `Cmd/Ctrl+Z` | Undo last operation |
| `Cmd/Ctrl+Shift+Z` | Redo last undone operation |

---

## Part 2: Hotkey Resolution Pipeline

### File: `/Users/alien/Projects/evo/src/keymap/core.cljc`

The keymap resolver converts raw keyboard events to intent types:

```
DOM KeyboardEvent
    ↓
keymap/parse-dom-event → {:key "Enter" :mod true :shift false :alt false}
    ↓
keymap/resolve-intent-type → Looks up binding in context-specific registry
    ↓
Returns intent-type (keyword or map)
```

**Key Functions:**

1. **`parse-dom-event(e)`** - Extracts modifiers and key from DOM event
   - Returns: `{:key "A" :mod false :shift false :alt false}`
   - Handles: `metaKey`, `ctrlKey`, `shiftKey`, `altKey`

2. **`resolve-intent-type(event, db)`** - Resolves keyboard event to intent
   - Determines context (`:editing` or `:non-editing`) from DB
   - Checks context-specific bindings first
   - Falls back to `:global` bindings
   - Returns: intent-type keyword or nil

3. **`register!(context, bindings)`** - Registers bindings for a context
   - Called at startup by `keymap.bindings/reload!`
   - Formats: `[[{:key "X"} intent-type] ...]`

---

## Part 3: Intent Registry & Dispatch

### File: `/Users/alien/Projects/evo/src/kernel/intent.cljc`

Central registry for all intents (high-level actions):

**Registration API:**
```clojure
(intent/register-intent! :my-action
  {:doc "Description of action"
   :spec [:map [:type [:= :my-action]] ...]
   :handler (fn [db {:keys [...]}] 
              ;; Returns: vector of operations
              [{:op :update-node ...}])})
```

**Dispatch Entry Points:**
```clojure
;; Main dispatch (returns {:db new-db :issues [...]})
(api/dispatch db {:type :select :ids "a"})

;; With trace (debug/REPL)
(api/dispatch* db {:type :select :ids "a"})

;; Throws on error
(api/dispatch! db {:type :select :ids "a"})
```

---

## Part 4: Intent Implementations

Intents are implemented as handlers in plugin namespaces:

### A. Selection Intents (`src/plugins/selection.cljc`)

**Intent: `:selection`**
Unified selection reducer with multiple modes:

```clojure
{:type :selection
 :mode :replace     ;; Replace selection with given IDs
 :ids "block-id"}

;; Modes:
:replace      ;; Set selection to IDs (last becomes focus)
:extend       ;; Add IDs to selection (supports range selection)
:deselect     ;; Remove IDs from selection
:toggle       ;; Toggle ID in/out of selection
:clear        ;; Clear all selection
:next         ;; Select next sibling
:prev         ;; Select previous sibling
:extend-next  ;; Extend selection to next sibling
:extend-prev  ;; Extend selection to previous sibling
:parent       ;; Select parent (if unique)
:all-siblings ;; Select all siblings
```

**State Stored:** `db[:nodes :session/selection]`
```clojure
{:nodes #{...block-ids...}  ;; Set of selected IDs
 :focus "current-id"        ;; Current focus block
 :anchor "anchor-id"}       ;; For range selection
```

### B. Editing Intents (`src/plugins/editing.cljc`)

| Intent | Purpose | State |
|--------|---------|-------|
| `:enter-edit` | Enter edit mode for a block | Ephemeral (UI node) |
| `:exit-edit` | Exit edit mode | Ephemeral (UI node) |
| `:update-content` | Update block text | Persistent (stored in undo history) |
| `:update-cursor-state` | Track cursor position (first/last row) | Ephemeral (UI node) |
| `:merge-with-prev` | Merge block with previous, delete current | Persistent |
| `:split-at-cursor` | Split block at cursor position | Persistent |

**Edit State Stored:** `db[:nodes :session/ui :props :editing-block-id]`

### C. Folding & Zoom Intents (`src/plugins/folding.cljc`)

**Fold State:** `db[:nodes :session/ui :props :folded]` (set of block-ids)

| Intent | Args | Effect |
|--------|------|--------|
| `:toggle-fold` | `{:block-id}` | Toggle expand/collapse state |
| `:expand-all` | `{:block-id}` | Recursively expand block & descendants |
| `:collapse` | `{:block-id}` | Collapse block (hide children) |
| `:toggle-all-folds` | `{:root-id}` | Expand all if any collapsed, else collapse all top-level |

**Zoom State:** `db[:nodes :session/ui :props :zoom-stack]` and `:zoom-root`

| Intent | Args | Effect |
|--------|------|--------|
| `:zoom-in` | `{:block-id}` | Push to zoom stack, set as rendering root |
| `:zoom-out` | `{}` | Pop from zoom stack, return to parent context |
| `:zoom-to` | `{:block-id}` | Jump to breadcrumb position in zoom stack |
| `:reset-zoom` | `{}` | Clear zoom stack, return to document root |

### D. Smart Editing Intents (`src/plugins/smart_editing.cljc`)

| Intent | Purpose | Example |
|--------|---------|---------|
| `:merge-with-next` | Merge block with next sibling | Delete key at end of block |
| `:unformat-empty-list` | Remove list marker from empty item | `- ` → `` |
| `:split-with-list-increment` | Split with auto-numbered list | `1. ` → `2. ` |
| `:toggle-checkbox` | Toggle checkbox state | `[ ]` ↔ `[x]` |

### E. Structural Intents (`src/plugins/struct.cljc`)

**Single Selection:**

| Intent | Args | Effect |
|--------|------|--------|
| `:delete` | `{:id}` | Move node to :trash |
| `:indent` | `{:id}` | Move node under previous sibling |
| `:outdent` | `{:id}` | Move node to be sibling of parent |
| `:create-and-place` | `{:id :parent :after?}` | Create block at location |
| `:create-and-enter-edit` | `{}` | Create & enter edit mode in one intent |

**Multi-Selection (applies to all selected or editing block):**

| Intent | Effect |
|--------|--------|
| `:delete-selected` | Delete all selected nodes |
| `:indent-selected` | Indent all selected nodes |
| `:outdent-selected` | Outdent all selected nodes |
| `:move-selected-up` | Reorder: move up one position |
| `:move-selected-down` | Reorder: move down one position |

**Movement/Reordering:**

| Intent | Args | Effect |
|--------|------|--------|
| `:move` | `{:selection [...] :parent :anchor}` | Reorder nodes with anchor positioning |

---

## Part 5: UI Event Handling

### Global Keyboard Handler (`src/shell/blocks-ui.cljs`)

Function: `handle-global-keydown(e)`

**Event Flow:**
```
DOM KeyboardEvent
    ↓
Parse event: keymap/parse-dom-event(e)
    ↓
Resolve intent: keymap/resolve-intent-type(event, db)
    ↓
Check context:
  - If editing + arrow at boundary → navigate blocks
  - If intent found → dispatch
  - If printable character + not editing → enter edit mode
  - If Cmd/Ctrl+Z → undo/redo
    ↓
Dispatch intent: handle-intent({:type ...})
```

**Special Handling:**

1. **Logseq-style arrow navigation in edit mode:**
   - Arrow Up at start of block → exit edit, go to prev block, enter edit at END
   - Arrow Down at end of block → exit edit, go to next block, enter edit at START

2. **"Start typing" mode:**
   - Printable character with focus (not editing) → enter edit mode
   - Enables Logseq's UX of typing to start editing

3. **Undo/Redo:**
   - `Cmd+Z` → undo
   - `Cmd+Shift+Z` → redo
   - Direct handling (not in keymap yet)

### Block Component Handler (`src/components/block.cljs`)

Function: `handle-keydown(e, db, block-id, on-intent)`

**Handles within edit mode only:**

1. **Arrow keys** - Cursor boundary detection
   - At first row: Up key navigates to previous block
   - At last row: Down key navigates to next block
   - Uses mock-text element (Logseq technique) for row detection

2. **Enter** - Create new block
   - Dispatches: `{:type :create-and-place ...}`
   - Places new block after current

3. **Escape** - Exit edit mode
   - Dispatches: `{:type :exit-edit}`

4. **Backspace** - Delete or merge
   - At start: merge with previous block
   - Empty: delete and navigate to previous

**Note:** Tab/Shift+Tab handled by global keymap (`:editing` context)

---

## Part 6: Intent to Operations Compilation

All intents compile to the 3-operation kernel:

```clojure
{:op :create-node  :id :type :props}  ;; Create new node
{:op :update-node  :id :props}        ;; Update properties
{:op :place        :id :under :at}    ;; Move/position node
```

**Example Chain:**

```clojure
;; User presses Tab while block "a" is selected
Hotkey: Tab
  ↓
Intent: {:type :indent-selected}
  ↓
Handler: (plugins.struct/apply-to-active-targets db indent-ops)
  ↓
Operations: [{:op :place :id "a" :under "prev-sibling-of-a" :at :last}]
  ↓
Transaction: Validates ops, applies to DB, derives indexes, records history
  ↓
Result: DB updated, selection/undo state recorded
```

---

## Part 7: State Management

### Session State (Ephemeral, Not in History)

Stored in `db[:nodes :session/ui :props]`:
- `:editing-block-id` - Currently editing block
- `:cursor-position` - Where cursor should be (`:start` or `:end`)
- `:cursor` - Map of block-id → `{:first-row? :last-row?}`
- `:folded` - Set of folded block IDs
- `:zoom-stack` - Navigation history for zoom
- `:zoom-root` - Current zoom root

**Why ephemeral?** These don't affect content, just UI state. They're not recorded in undo history.

### Persistent State (In History)

All structural and content changes go through undo/redo:
- Block creation/deletion
- Text content changes
- Tree structure (parent/child relationships)
- Selection changes (also in undo!)

---

## Part 8: Data-Driven Binding System

### Why Data-Driven?

1. **Hot-reload:** Edit `bindings_data.cljc`, call `(reload!)`, bindings update
2. **Single source of truth:** All bindings in one place
3. **Easy to introspect:** List all bindings at runtime
4. **Extensible:** Plugins can register additional bindings

### Extension Points

```clojure
;; 1. Add binding to bindings_data.cljc
{:editing [[{:key "M" :mod true} :my-new-action]]}

;; 2. Register intent handler in a plugin
(intent/register-intent! :my-new-action
  {:doc "..."
   :spec [...]
   :handler (fn [db intent] [...ops...])})

;; 3. Reload bindings
(keymap.bindings/reload!)
```

---

## Part 9: Keyboard Event Resolution Details

### Modifier Keys

```clojure
:mod   → Cmd (macOS) or Ctrl (Windows/Linux)
:shift → Shift key
:alt   → Alt (option on macOS)
```

### Key Matching

Exact match required:
- Key: exact string match (`"Enter"`, `"Tab"`, `"ArrowUp"`)
- Modifiers: must match (`:mod true` requires Cmd/Ctrl)

```clojure
;; Example: Match only "Tab" with no modifiers
{:key "Tab"}                    ;; ✓ matches Tab alone

;; Example: Match "Tab" with Shift
{:key "Tab" :shift true}        ;; ✓ matches Shift+Tab

;; Match fails if:
{:key "Tab" :shift true}        ;; ✗ doesn't match Tab alone
```

---

## Part 10: Common Patterns & Examples

### Pattern 1: Simple Hotkey → Intent

```clojure
;; Binding
[{:key "Tab"} :indent-selected]

;; Resolution
(resolve-intent-type {:key "Tab" :mod false :shift false :alt false} db)
;=> :indent-selected

;; Dispatch
(api/dispatch db {:type :indent-selected})
;=> {:db new-db :ops [{:op :place ...}]}
```

### Pattern 2: Modal Hotkeys (Context-Aware)

```clojure
;; In :editing context, Tab indents
[{:key "Tab"} :indent-selected]

;; In :non-editing context, Tab also indents
[{:key "Tab"} :indent-selected]

;; In :global context, Cmd+Enter toggles checkbox (always)
[{:key "Enter" :mod true} {:type :toggle-checkbox}]
```

### Pattern 3: Intent with Parameters

```clojure
;; Keymap
[{:key "Enter" :mod true} {:type :toggle-checkbox}]

;; Intent injected with block-id
{:type :toggle-checkbox :block-id "focused-block"}

;; Handler
(fn [db {:keys [block-id]}]
  (let [text (get-block-text db block-id)
        new-text (toggle-checkbox-text text)]
    [{:op :update-node :id block-id :props {:text new-text}}]))
```

### Pattern 4: Multi-Target Intent

```clojure
;; Intent: :delete-selected
;; Handler applies to ALL selected blocks

(defn- active-targets [db]
  (let [selected (q/selection db)
        editing-id (q/editing-block-id db)]
    (or selected (when editing-id [editing-id]))))

;; Result: Each block gets its own :place op to :trash
```

---

## Part 11: Testing Hotkeys

### List All Intents

```clojure
(api/list-intents)
;=> {:select {:doc "..." :spec [...]}
;    :indent {:doc "..." :spec [...]}}
```

### Dispatch Intent in REPL

```clojure
(let [{:keys [db issues]} (api/dispatch db {:type :select :ids "a"})]
  (if (empty? issues)
    (prn "Success:" db)
    (prn "Errors:" issues)))
```

### Check Hotkey Binding

```clojure
(keymap/resolve-intent-type {:key "Tab" :mod false :shift false :alt false} db)
;=> :indent-selected
```

### Reload Hotkeys

```clojure
(keymap.bindings/reload!)
;=> :ok
```

---

## Part 12: Quick Reference

### All Hotkeys at a Glance

| Context | Hotkey | Intent |
|---------|--------|--------|
| **Any** | `↑↓` | Navigate selection |
| | `Shift+↑↓` | Extend selection |
| | `Cmd/Alt+Shift+↑↓` | Move up/down |
| | `Cmd+;` | Toggle fold |
| | `Cmd+↑↓` | Collapse/expand |
| | `Cmd+.,` | Zoom in/out |
| | `Cmd+Enter` | Toggle checkbox |
| | `Cmd+Z` / `Shift+Cmd+Z` | Undo/redo |
| **Non-Editing** | `Tab` / `Shift+Tab` | Indent/outdent |
| | `Backspace` | Delete |
| | `Enter` | Create & enter |
| **Editing** | `Escape` | Exit |
| | `Cmd+Backspace` | Merge with prev |
| | `Enter` | Create new block |

---

## Architecture Summary

```
┌─────────────────────────────────────────────────┐
│           DOM Event Listener                    │
│         (global keydown handler)                │
└────────────────────┬────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────┐
│          Keymap Resolution                      │
│  (parse event → resolve intent type)            │
│       src/keymap/core.cljc                      │
└────────────────────┬────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────┐
│        Intent Dispatch                          │
│  (find registered handler)                      │
│     src/kernel/intent.cljc                      │
└────────────────────┬────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────┐
│       Handler (Plugin Layer)                    │
│  (intent → ops compiler)                        │
│       src/plugins/*.cljc                        │
└────────────────────┬────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────┐
│      Transaction Pipeline                      │
│  (validate → apply → derive → history)          │
│   src/kernel/transaction.cljc                   │
└────────────────────┬────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────┐
│          Updated DB                            │
│  (rendered via atom watch)                      │
└─────────────────────────────────────────────────┘
```

---

## Files Index

| File | Purpose |
|------|---------|
| `src/keymap/bindings_data.cljc` | All hotkey definitions |
| `src/keymap/core.cljc` | Hotkey resolution (parsing + matching) |
| `src/keymap/bindings.cljc` | Binding registration & hot-reload |
| `src/kernel/intent.cljc` | Intent registry & dispatch |
| `src/kernel/api.cljc` | Unified API façade (dispatch, list-intents) |
| `src/plugins/selection.cljc` | Selection intents handler |
| `src/plugins/editing.cljc` | Edit mode intents handler |
| `src/plugins/folding.cljc` | Fold/zoom intents handler |
| `src/plugins/smart_editing.cljc` | Smart editing intents handler |
| `src/plugins/struct.cljc` | Structural intents handler (indent, delete, move) |
| `src/plugins/refs.cljc` | Reference intents (links, citations) |
| `src/shell/blocks-ui.cljs` | Global keyboard handler |
| `src/components/block.cljs` | Block component & edit-mode handlers |

