# Logseq Desktop Behavior Spec (macOS)

Canonical, implementation-agnostic description of the Logseq desktop "feel" (macOS build 0.10.x). Every statement below comes from observing and reading upstream Logseq; use it as the ground truth before mapping behavior into another client.

_Last verification:_ 2025‑12‑12 against `~/Projects/best/logseq` (main). Re-run the walkthrough any time upstream changes.

---

## Table of Contents

- [Related Documents](#related-documents)
- [1. Objective & Scope](#1-objective--scope)
- [2. Observation Workflow](#2-observation-workflow)
- [3. Behavior Themes](#3-behavior-themes)
- [5. macOS Keymap Coverage](#5-macos-keymap-coverage-extracted-from-modulesshortcutconfigcljs)
- [0. Behavior Map & References](#0-behavior-map--references)
- [1. Global Interaction Model](#1-global-interaction-model)
- [2. Cursor Placement & Navigation](#2-cursor-placement--navigation)
- [3. Text Selection Rules](#3-text-selection-rules)
- [4. Editing Actions](#4-editing-actions)
- [5. Structural Editing](#5-structural-editing)
- [6. Fold & Zoom Constraints](#6-fold--zoom-constraints)
- [7. Additional Interaction Patterns](#7-additional-interaction-patterns)
- [8. Extensibility & Hooks Architecture](#8-extensibility--hooks-architecture)
- [9. Summary Checklist](#9-summary-checklist-behavioral)

---

## Related Documents

This spec has been split into focused documents for easier reference:

| Document | Scope |
|----------|-------|
| **[STRUCTURAL_EDITING.md](STRUCTURAL_EDITING.md)** | Core editor mechanics: state machine, navigation, cursor/selection, editing actions, tree operations, folding/zoom, undo/redo. These are fundamental to any outliner. |
| **[LOGSEQ_UI_FEATURES.md](LOGSEQ_UI_FEATURES.md)** | Logseq-specific UI: slash commands, quick switcher, sidebar, advanced clipboard, drag & drop, move dialog, properties, templates. |

**This file** remains the comprehensive reference with full Logseq source links and verification notes.

---

## 1. Objective & Scope

- Capture every observable desktop interaction (state machine, keyboard, pointer, clipboard, slash palette, quick switcher, undo/redo) exactly as Logseq ships.
- Focus on macOS keybindings (Logseq varies bindings per platform).
- Out of scope: mobile/touch flows or downstream architectural decisions.

## 2. Observation Workflow

1. `cd ~/Projects/best/logseq` (main branch). Use `OVERVIEW.md` there as your file map.
2. For each interaction area (state machine, navigation, selection, editing, structural moves, folding/zoom, clipboard, slash palette, quick switcher, undo/redo, mouse gestures) inspect the upstream files listed in §3.
3. When uncertain, run the Logseq desktop app and reproduce the scenario manually; record any edge cases in this spec with links back to the upstream code you verified.

### Interaction Area Source Map (from `logseq/OVERVIEW.md`)

| Area | Primary files | Notes |
|------|---------------|-------|
| State machine & editor loop | `src/main/frontend/handler/editor.cljs`, `state.cljs`, `components/editor.cljs` | Contains idle/selection/edit transitions, keyboard handlers. |
| Selection helpers | `state.cljs`, `util.cljs`, `components/block.cljs`, `components/selection.cljs` | Direction tracking, Shift+Arrow seeding, Shift+Click. |
| Navigation | `handler/editor.cljs` (`shortcut-up-down`, `select-up-down`, `shortcut-left-right`), `util.cljc`, `util/cursor.cljs` | Grapheme-aware cursor memory, DOM-order traversal. |
| Structural edits | `modules/outliner/op.cljs`, `handler/block.cljs`, `handler/dnd.cljs` | Create/place/update ops, move/indent/drag semantics. |
| Folding & zoom | `handler/editor.cljs` (expand/collapse), `state.cljs` (zoom roots) | Cmd+Up/Down/; plus page/zoom boundaries. |
| Clipboard & paste | `handler/editor.cljs` (copy/cut), `handler/paste.cljs`, `commands.cljs`, `utils.js` (clipboard API) | Metadata-preserving block copy, segmented paste, macro detection, MIME types. |
| Slash palette & quick switcher | `commands.cljs`, `handler/command_palette.cljs`, `components/search.cljs` | `/` inline menu, Cmd+K/P overlay. |
| Undo/redo | `handler/history.cljs`, `undo_redo.cljs`, `state.cljs` | Restores text, block structure, caret/selection, UI state. |
| Pointer + mouse gestures | `components/block.cljs`, `components/dnd.cljs`, `modules/outliner/tree.cljs` | Alt+click folding, Shift+click ranges, drag/drop + Alt reference insert. |
| Plugin system & hooks | `handler/plugin.cljs`, `state.cljs` (plugin atoms), `logseq/api.cljs` | Hook installation, command registration, renderer hooks, storage. |

## 3. Behavior Themes

The tables below summarize the interaction promises users rely on.

### 3.1 Personas
| Persona | Expectation |
|---------|-------------|
| **Power user** | Every shortcut behaves like Logseq; no surprise focus jumps or scroll glitches. |
| **Structural editor** | Indent/outdent/move/drag keeps tree topology identical to Logseq. |
| **Note writer** | Paste, slash palette, quick switcher, and inline typing feel instantaneous. |
| **QA / Tester** | Deterministic scenarios exist for each shortcut so regressions are obvious. |

### 3.2 Functional Requirement Catalog
(FR IDs referenced throughout this document)
- **FR-Idle-01..03** – Idle guard + type-to-edit contract.
- **FR-State-01..03** – Exclusive edit/selection states & clearing rules.
- **FR-Scope-01..03** – Visible-outline boundaries (page, zoom, folding).
- **FR-NavEdit-01..04** – Editing-mode arrows, cursor memory, Shift+Arrow seeding.
- **FR-NavView-01..04** – View-mode selection, Shift+Click, sidebar open.
- **FR-Edit-01..07** – Enter/Shift+Enter, Backspace/Delete, whitespace, caret restore.
- **FR-Move-01..03** – Indent/outdent rules, climb/descend, drag/drop parity.
- **FR-Clipboard-01..06** – Copy/cut/reference variants, paste semantics, MIME types, graph mismatch, code block paste.
- **FR-Pointer-01..02** – Alt+click folding toggle, hover preview + Cmd+click open.
- **FR-Slash-01 / FR-QuickSwitch-01** – Slash palette + Cmd+K/P quick switcher.
- **FR-Undo-01..04** – Undo/redo restores caret + selection context, stack architecture, batching rules, edge cases.
- **FR-Plugin-01..03** – Hook installation, command registration, renderer hooks.

### 3.3 Acceptance Examples (BDD snippets)
```
Scenario: Idle guard
  Given no block is selected or editing
  When the user presses Enter
  Then no new blocks are created and focus remains idle
```
```
Scenario: Zoom boundary
  Given the user zoomed into block Z
  And block B is the first child of Z
  When the user presses Shift+ArrowUp while B is editing
  Then B stays selected and no blocks outside Z become selected
```

## 5. macOS Keymap Coverage (extracted from `modules/shortcut/config.cljs`)

### 5.1 Editing & Formatting
| Key | Intent | Handler | Notes |
|-----|--------|---------|-------|
| Enter | `:editor/new-block` | `keydown-new-block-handler` | Context-aware split (above when caret=0, below otherwise). |
| Shift+Enter | `:editor/new-line` | `keydown-new-line-handler` | Inserts literal newline inside block. |
| Backspace/Delete | `:editor/backspace` / `:editor/delete` | Component handlers | Merge/trim semantics, skip keymap to avoid double-dispatch. |
| Cmd+B / Cmd+I / Cmd+Shift+H / Cmd+Shift+S | `:editor/bold` / `:editor/italics` / ... | Format helpers | Works on selection; same combos as Logseq. |
| Ctrl+L / Ctrl+U / Ctrl+W | Clear/kill commands | `clear-block-content!`, `kill-line-before!`, `forward-kill-word` | Emacs-style controls (Ctrl modifier, not Cmd). |

### 5.2 Editing Navigation
| Key | Intent | Behavior |
|-----|--------|----------|
| ArrowUp/ArrowDown | `:navigate-with-cursor-memory` | Maintains grapheme column; no-ops at page edges. |
| Ctrl+P / Ctrl+N | Aliases for Up/Down | Provided for Emacs users. |
| ArrowLeft/ArrowRight | `:navigate-to-adjacent` | At boundaries, jumps to previous/next visible block (parent/child aware). |

### 5.3 Selection & View Mode
| Key | Intent | Notes |
|-----|--------|-------|
| Shift+ArrowUp/Down | `:selection/extend-prev-next` | Incremental range with direction tracking, seeded when exiting edit via boundary. |
| Alt+ArrowUp/Down | `:editor/select-block-up/down` | Jumps entire blocks without extending range. |
| Enter (selection) | `:editor/open-edit` | Enters edit on focused block. |
| Shift+Enter (selection) | `:editor/open-selected-blocks-in-sidebar` | Opens highlighted blocks in sidebar. |
| Cmd+A / Cmd+Shift+A | `:editor/select-parent` / `:editor/select-all-blocks` | Page/zoom aware. |

### 5.4 Structural & Folding
| Key | Intent | Behavior |
|-----|--------|----------|
| Tab / Shift+Tab | `:indent-selected` / `:outdent-selected` | Indent/outdent selection; guard zoom roots and root-level blocks. |
| Cmd+Shift+Up/Down | `:move-selected-up/down` | Reorders selection; climbs/descends when at boundaries (FR-Move-01..03). |
| Cmd+Shift+M | `:editor/move-blocks` | Opens move dialog to reposition selection under arbitrary target, matching Logseq’s “Move blocks to…” workflow. |
| Cmd+Up/Down / Cmd+; | `:editor/collapse-block-children` / `:editor/expand-block-children` / toggle | Folding shortcuts respect current selection/edit block. |
| Cmd+. / Cmd+, | `:editor/zoom-in` / `:editor/zoom-out` | Zoom root stack maintained in `state.cljs`. |

### 5.5 Clipboard, Slash, Quick Switcher
| Key | Intent | Notes |
|-----|--------|-------|
| Cmd+C / Cmd+Shift+C / Cmd+X | Copy block w/ metadata / copy plain text / cut | Uses Logseq’s block serialization (HTML + Markdown) plus custom MIME payload. |
| Cmd+Shift+V | Paste as plain text | Calls `paste-handler/editor-on-paste-raw!`. |
| Cmd+Shift+E / Cmd+Shift+R | Copy embed / replace block ref with content | Reference utilities in `handler/editor.cljs`. |
| Cmd+L / Cmd+Shift+O | Insert link / open link in sidebar | Link helpers in `commands.cljs`. |
| `/` | Slash palette | Opens inline command menu anchored to caret. |
| Cmd+K | Quick switcher (`:go/search :global`) | Overlay search implemented in `components/search.cljs`. |
| Cmd+Shift+P | Command palette | Command overlay in `handler/command_palette.cljs`. |
| Cmd+Enter | `:editor/cycle-todo` | Toggles TODO/checkbox values. |

### 5.6 Undo / Redo
| Key | Intent | Notes |
|-----|--------|-------|
| Cmd+Z / Cmd+Shift+Z / Cmd+Y | `:editor/undo` / `:editor/redo` | Restores block content + selection/caret using history stack.

# LOGSEQ_SPEC.md — Editing, Navigation, Selection, and Structural Behaviors

## 0. Behavior Map & References

| FR ID | Behavior Summary | Spec Section | Logseq Source (verification) |
|-------|------------------|--------------|------------------------------|
| FR-Idle-01..03 | Idle-state guard + type-to-edit | §1 | `src/main/frontend/handler/editor.cljs` (`select-first-last`, `keydown-new-block-handler`) |
| FR-State-01..03 | Edit/view exclusivity & selection clearing | §1 | `state.cljs`, `util.cljs` selection helpers |
| FR-Scope-01..03 | Visible-outline boundaries (page, zoom, folding) | §5, §6 | `util/get-prev-block-non-collapsed`, `state/zoom-in!` |
| FR-NavEdit-01..04 | Editing-mode arrows + Shift+Arrow seeding | §2 | `handler/editor.cljs` (`shortcut-up-down`, `shortcut-left-right`) |
| FR-NavView-01..04 | Selection-mode arrows, Shift+Click, Shift+Enter sidebar | §3 | `handler/editor.cljs` (`select-up-down`, `shortcut-select-up-down`) |
| FR-Edit-01..07 | Enter/Shift+Enter, Backspace/Delete, whitespace, caret restore | §4 | `handler/editor.cljs` (`keydown-new-block`, `keydown-backspace-handler`) |
| FR-Move-01..03 | Indent/outdent rules, climb/descend, drag & Alt-drag | §5 | `outliner/core.cljs`, `handler/editor.cljs/move-up-down`, `handler/dnd.cljs` |
| FR-Clipboard-01..06 | Copy/cut/reference, paste semantics, MIME types, edge cases | §7.6–§7.7 | `modules/shortcut/config.cljs`, `handler/paste.cljs`, `utils.js` |
| FR-Pointer-01..02 | Pointer gestures (bullet click collapse, Shift/Cmd multi-select, Alt-drag refs) | §7.3 | `frontend/components/block.cljs`, `frontend/handler/dnd.cljs` |
| FR-Slash-01 | Slash command palette & inline workflows | §7.4 | `frontend/commands.cljs`, `frontend/handler/editor.cljs` |
| FR-QuickSwitch-01 | Quick switcher + command palette overlay | §7.5 | `frontend/handler/command_palette.cljs`, `frontend/components/search.cljs`, `modules/shortcut/config.cljs` |
| FR-Undo-01..04 | Undo/redo stack, cursor restore, batching, edge cases | §7.8 | `state.cljs`, `handler/history.cljs`, `undo_redo.cljs` |
| FR-Plugin-01..03 | Plugin hooks, command extension, renderer hooks | §8 | `handler/plugin.cljs`, `state.cljs`, `logseq/api.cljs` |

> **Verification note:** Behaviors confirmed against `~/Projects/best/logseq` (main, 2025‑12‑12). Re-run validation whenever upstream changes.

**Target application:** Logseq desktop (macOS build 0.10.x).  \n**Purpose:** Canonical record of user-observable behavior—implementation-specific notes live elsewhere.

---

## 1. Global Interaction Model

### 1.1 State Machine

Logseq enforces a strict, mutually exclusive state machine for block interaction:

```
           Cmd/Ctrl+Enter           (new block)  
      ┌──────────────────────────────────────────┐
      │                                          ▼
┌──────────────┐   Enter block   ┌────────────────────┐
│ View (Idle)  │ ───────────────▶ │ View (Selection)   │
│ • no cursor  │   (click block) │ • blue selection   │
│ • no blue    │ ◀───────────────│ • focus == anchor  │
│   frames     │  Escape / click │ • no caret         │
└──────┬───────┘  background     └────────┬───────────┘
       │                                  │
       │ Cmd+Enter / double-click         │ Enter / start typing
       ▼                                  ▼
┌──────────────┐   Escape / blur   ┌────────────────────┐
│ Edit Mode    │ ─────────────────▶│ View (Idle)        │
│ • content-   │                   │                    │
│   editable   │                   │                    │
│ • caret shown│                   │                    │
│ • selection Ø│                   │                    │
└──────────────┘                   └────────────────────┘
```

**Invariants:**
- Edit mode (`Mode: Edit`) and selection state (`Mode: View` with `[*]`) never coexist.
- Transitioning *into* edit mode clears `selection`, `focus`, and ensures exactly one block has `:editing-block-id`.
- Transitioning *out* of edit mode leaves selection cleared unless a view-mode gesture immediately re-selects blocks.
- Background clicks and Escape (when not editing) clear selection.
- In the fully idle state (no block selected, no block editing), pressing Enter, Backspace, Tab, Shift+Enter, Shift+Arrow, or Cmd+Enter does nothing—Logseq never creates or deletes blocks from that state. The only keys that take effect are ArrowUp/ArrowDown (which select the first/last visible block) and printable characters (which immediately enter edit mode on the focused block and insert the character at the end).

### 1.2 Interaction State Mirrors

Logseq keeps ephemeral interaction data inside `frontend.state` (see `state.cljs`). Evo’s `session/*` nodes must mirror the same slots so undo/redo, Shift+Arrow, and slash palette flows behave exactly the way the upstream UI expects.

| Session slot | Logseq source | Meaning |
|--------------|---------------|---------|
| `session/ui.editing-block-id` | `:editor/editing?`, `:editor/block` | Which block is in edit mode and therefore owns the live textarea. |
| `session/ui.cursor-position` | `:editor/cursor-range`, `state/get-editor-last-pos` | Stores the last saved caret offset (`:start`, `:end`, or numeric) per block so returning to a block restores the caret. |
| `session/ui.cursor-memory` | `:editor/last-saved-cursor` | Grapheme column memory `{block-uuid {:line-pos n :direction dir}}` reused by `shortcut-up-down`. |
| `session/selection.nodes` | `:selection/blocks` | Vector of DOM nodes (ordered) that are currently highlighted. |
| `session/selection.focus` | `state/get-selection-blocks` + last entry | The block that range operations treat as the “focused” endpoint. |
| `session/selection.anchor` | `:selection/start-block` | The DOM node seeded by the very first Shift+Click; used by `highlight-selection-area!` for contiguous ranges. |
| `session/selection.direction` | `:selection/direction` (`nil`/`:up`/`:down`) | Governs whether Shift+Arrow extends or contracts the selection. Opposite directions call `drop-last-selection-block!`. |
| `session/selection.flags.selected-all?` | `:selection/selected-all?` | Tracks whether `Cmd+Shift+A` already scooped the full visible outline (prevents duplicate work). |
| `session/ui.inline-action` | `:editor/action` + `:editor/action-data` | Encodes transient overlays such as slash palette input steps, block search, and inline property dialogs. |

`state/set-selection-blocks!` normalizes DOM nodes, reapplies the `.selected` class, updates `:selection/direction`, and publishes `[:editor/load-blocks ids]`. `state/clear-selection!` wipes nodes, direction, anchor, and fires `[:editor/hide-action-bar]`. Pointer gestures (see §7.3) use `state/set-selection-start-block!` to seed anchors so the next Shift+Click can compute ranges via `editor-handler/highlight-selection-area!`, which in turn walks the DOM with `util/get-nodes-between-two-nodes`. Implementations that only store an unordered set of IDs cannot reproduce Logseq's extend/contract semantics.

### 1.3 Focus Transitions During Structural Operations

When Enter creates a new block, focus must move from the old contenteditable to the newly created block's editor. This creates a race condition: the old block loses focus (triggering blur) before the new block's DOM exists.

**Logseq's Approach: Deferred Focus via `requestAnimationFrame`**

Logseq does **not** suppress blur handlers. Instead, it defers the focus call until after the DOM update:

1. `insert-new-block!` stores an `edit-block-fn` closure in state (`:editor/edit-block-fn`)
2. The outliner transaction commits, updating the database
3. After transaction, `modules/outliner/pipeline.cljs` calls `(util/schedule edit-block-f)`
4. `util/schedule` is `requestAnimationFrame` (~16ms)
5. The deferred function calls `edit-block!` on the new block

```clojure
;; From frontend/handler/editor.cljs:insert-new-block!
(p/do!
  (state/set-state! :editor/edit-block-fn edit-block-f)  ;; Store focus fn
  result-promise                                          ;; Wait for transaction
  (clear-when-saved!))                                    ;; Cleanup

;; From modules/outliner/pipeline.cljs (after tx commit)
(let [edit-block-f @(:editor/edit-block-fn @state/state)]
  (state/set-state! :editor/edit-block-fn nil)
  (when edit-block-f
    (util/schedule edit-block-f)))  ;; rAF-deferred focus
```

**Evo's Approach: Blur Suppression Flag**

Evo takes a different approach - suppressing the blur handler during structural operations:

1. Before structural intents (indent, outdent, move, enter), set `keep-edit-on-blur` flag (200ms timeout)
2. Blur handler checks flag and skips `:exit-edit` if set
3. Intent creates new block and sets `editing-block-id` to new block
4. DOM updates, new contenteditable gets focus
5. Flag expires or is cleared

Both approaches solve the same problem: preventing blur from clearing edit state before the new block can receive focus.

| Aspect | Logseq | Evo |
|--------|--------|-----|
| Mechanism | Deferred focus via rAF | Blur suppression flag |
| Timing | ~16ms (single rAF) | ~32ms (double rAF) |
| Blur fires | Yes, but edit state already cleared by transaction | No, suppressed |
| Complexity | Requires state slot + pipeline hook | Requires intent classification |

**Implementation Note for Evo:**

The `structural-intents` set in `shell/editor.cljs` must include all intents that:
1. Cause DOM re-render (blur fires on old element)
2. Need to maintain edit mode in a different block

```clojure
(def structural-intents
  #{:indent-selected :outdent-selected :move-selected-up :move-selected-down
    :delete-selected :context-aware-enter})
```

Missing an intent from this set causes focus loss when that intent fires during edit mode.

---

## 2. Cursor Placement & Navigation

### 2.1 Vertical Navigation in Edit Mode

| Scenario | Before | Action | After | Notes |
|----------|--------|--------|-------|-------|
| Same-column Down | `A: "Hello ^world"` → `B: "First⏎Second line"` | `↓` | Caret lands in B’s last line, same visual column | Column stored via `GraphemeSplitter` line-pos, reused on target block |
| Short Target Up   | `A: "Hi"`, `B: "foo^ bar"` | `↑` | Caret in A at end | Falls back to `:end` when target shorter |
| Boundary No-op    | Single block editing | `↑` | No change | No exception thrown |

**Implementation Requirements:**
- Use grapheme-aware counting (`js/Intl.Segmenter`/`GraphemeSplitter`) to calculate `line-pos` and convert back to character offsets.
- `:navigate-with-cursor-memory` must skip folded descendants and blocks outside current zoom root using the same filters as `util/get-prev-block-non-collapsed` in Logseq.
- Missing siblings result in a no-op with the caret left at boundary (`:cursor-position` resets to `:start`/`(count text)`), never an exception.
- Single-block pages: when the entire visible outline consists of one block, ArrowUp/ArrowDown in view mode keep that block selected, and ArrowUp/ArrowDown in edit mode only move the caret within the block (never exiting). Outdent/Move-Up commands on that block are hard no-ops because there is no valid target.

### 2.2 Vertical Navigation in View Mode

- `ArrowUp/ArrowDown` when not editing move `:focus` through visible siblings, skipping folded subtrees and respecting zoom root.
- **When no block is focused** (e.g., after Escape clears selection):
  - `ArrowDown` selects the **first** visible block in the current page/zoom
  - `ArrowUp` selects the **last** visible block in the current page/zoom
- `Shift+Arrow` extends the range selection: anchor remains first selected block, focus becomes next visible sibling, range includes all blocks between anchor and focus in document order.
- Selection operations never modify `session/ui`.

### 2.3 Horizontal Boundary Navigation

- `ArrowLeft` at column 0 dispatches `:navigate-to-adjacent {:direction :up :cursor-position :max}`.
- `ArrowRight` at end of text dispatches `:navigate-to-adjacent {:direction :down :cursor-position 0}`.
- Interior positions defer to browser defaults.

---

## 3. Text Selection Rules

1. While editing, the caret (`|`) and intra-block text selection (`«…»`) are visible. No block is marked `[*]`.
2. While viewing, selection is represented by block highlighting (`[*]`); no caret is visible.
3. `Shift+Arrow` behavior:
   - If caret is not on first/last rendered row, let the browser extend text selection. Logseq does this by allowing the native event to proceed.
   - When caret reaches first/last row, `Shift+Arrow` should prevent default, exit edit mode, and dispatch `{:type :selection :mode :extend-prev/next}`.
4. `Cmd+A` while editing selects entire block text on first press; second press (with full text selected) exits edit mode and selects the block (`[*]`).
5. `Cmd+Shift+A` selects all visible blocks within current page/zoom root.
6. Escape behavior:
   - In edit mode: leaves edit, focus becomes `[*]` block.
   - In view mode: clears selection (no `[*]`).
7. Selection integrity: if any block in the current selection disappears (deleted, moved outside the visible outline, or hidden by folding/zoom), Logseq clears the entire selection immediately so there are never dangling highlights.

---

## 4. Editing Actions

| Action | Behavior | Notes |
|--------|----------|-------|
| `Enter` | Dispatch `:smart-split` (context-aware). Splits block unless inside code fence / markdown structure where newline is expected. **Empty list items are a single-step “unformat + create peer”**: when the caret sits on a bullet/number that has no content, Logseq removes the list marker *and* inserts a sibling block at the parent level in the same keypress (see `frontend.handler.editor/keydown-new-block`, branch `thingatpt/list-item`). | Mirrors Logseq’s smart editing plugin. |
| `Shift+Enter` | Insert literal newline inside block (no new block). | Handled by keymap + smart editing.
| `Backspace` | - With selection: delete selection.  - At start of block: merge into previous sibling, re-parent children onto previous block, place caret at boundary.  - Empty block: delete block and move focus to previous sibling. | Matches `handler/editor.cljs:keydown-backspace-handler`.
| `Delete` | At end of block: merge with next block, prioritising first child then next sibling. Re-parent children accordingly. |
| `Cmd+O` | Follow link under caret (page refs open page, block refs scroll into view). |
| `Cmd+Shift+H` | Toggle highlight `^^…^^` preserving text selection. |

Additional guarantees:
- Blocks containing only whitespace behave like empty blocks for editing commands: Backspace/Delete remove them, Tab/Shift+Tab indent/outdent them, and merge operations treat them as zero-length.
- Inside fenced code blocks or inline code spans, pressing Tab inserts a literal tab (or configured spaces) instead of indenting the block.
- After structural edits (split, indent/outdent, move up/down), the caret remains inside the block that was edited/moved in its new location. Undo/redo restores both the content and the caret/selection state exactly as it was when the edit occurred.

Undo granularity: each editing intent must emit minimal structural ops (`:update-node`, `:place`) to integrate with history.

---

## 5. Structural Editing

### 5.1 Indent/Outdent

- `Tab` (`:indent-selected`) moves active block/selection under previous visible sibling. Outdent behavior depends on `:editor/logical-outdenting?` (assumed `true` for this spec):
  - Block moves to position immediately AFTER its parent (becomes sibling of parent).
  - Right siblings remain under original parent (no "kidnapping").
  - Outdenting is prevented when parent is a root (`:doc`, `:journal`).
- When the previous sibling has children, indenting makes the block the *last* child of that sibling (after all existing children).
- **Collapsed expansion on indent:** When the target sibling (left sibling) is collapsed, Logseq automatically **expands** it so the user can see where their block went. Without this, indented blocks "disappear" into an invisible container. (Source: `deps/outliner/src/logseq/outliner/core.cljs` line 1106-1108)
- Outdenting a block that has children keeps those children attached to the block; they do not remain under the old parent.
- Indenting the first child (no previous sibling) and outdenting the root-most block are both hard no-ops.
- **Non-consecutive selection rejection:** Logseq explicitly rejects indent/outdent on non-consecutive multi-block selections. When blocks are selected that are not adjacent siblings, the operation is silently ignored to prevent unexpected structural changes. (Source: `deps/outliner/src/logseq/outliner/core.cljs` line 1079-1081)
- Multi-selection runs through `frontend.handler.block/get-top-level-blocks`, which replaces clones with their original blocks and filters out nested duplicates so the outliner never tries to move the same subtree twice.
- After `indent-outdent-blocks!` finishes, Logseq re-queries the DOM (`js/setTimeout 100ms`) and calls `state/set-selection-blocks!` so the highlight follows the moved blocks. Evo must preserve the same rehydration or Shift+Arrow immediately breaks after an indent.

### 5.2 Move Up/Down

- `Alt+Shift+ArrowUp/Down` reorder siblings while preserving selection order (stable move).
- **Climb semantics:** when a block is the first child and the user presses `Mod/Alt+Shift+Up`, Logseq “climbs” it out—moving it to become the previous sibling of its parent. Likewise, pressing `Mod/Alt+Shift+Down` on the last child pushes it into the next visible sibling chain. The behavior is implemented in `frontend.handler.editor/move-up-down` + `outliner-op/move-blocks-up-down!` and covered by `test-move-blocks-up-down`.
- When editing, dedicated intents (`:move-block-up-while-editing`) perform the same structural move without exiting edit mode.
- When a selection already sits at the absolute top (no climb target) or bottom (no descend target) of the visible outline, `Cmd+Shift+ArrowUp/Down` are strict no-ops.

### 5.3 Delete Selected

- Moves selected blocks to `const/root-trash`. Children are moved with the parent (same as Logseq).

### 5.4 Drag & Drop (Mouse)

- Logseq exposes drag handles on blocks. Dropping without modifiers performs the same structural move as `move-blocks`. Holding **Alt** during drop inserts a block reference instead of moving the source (`frontend.handler.dnd/move-blocks` checks `event.altKey` and calls `ref/->block-ref`).
- Dragging into the top/bottom “hit zones” reuses the same “climb” semantics described above—dropping at the top of a block with Alt released moves the dragged block before the target’s parent when appropriate.
- Dropping onto a page whose format (Markdown vs Org) differs from the source triggers the warning “Those two pages have different formats.” and aborts the move. This guard (`state/pub-event! [:notification/show …]`) prevents Logseq from silently reformatting content; Evo must retain it.
- Any parity-seeking implementation must reproduce both behaviors once drag-and-drop support lands.

## 6. Fold & Zoom Constraints

- All navigation/selection intents must consult derived indexes that exclude folded descendants and nodes outside current zoom root. Equivalent to Logseq’s `util/get-prev-block-non-collapsed` / `get-next-block-non-collapsed`.
- `Cmd+.` / `Cmd+Shift+.` invoke `editor-handler/zoom-in!`. If a block is editing, Logseq saves it, sets `:editing-block-id` to the block’s UUID, and `route-handler/redirect-to-page!` so the block becomes the zoom root; if nothing is editing it simply uses `window.history.forward`. `Cmd+,` mirrors this: while editing it climbs to the parent page/block (walking `db/get-block-parent`), otherwise it calls `window.history.back`.
- Folding state lives under node props (e.g., `{:props {:folded? true}}`). Navigation should traverse siblings via derived links, skipping folded subtrees entirely.
- Editing a folded block automatically expands it so the user can see its children while typing.
- Delete operations applied to a folded block remove the entire subtree (exactly as if it were expanded).
- While zoomed into block `Z`, any operation that would move a block outside of `Z` (outdent, Shift+Arrow extend, Cmd+Shift+Arrow move) is a no-op; the zoom root is treated as a hard boundary.

## 7. Additional Interaction Patterns

Beyond keyboard navigation and structural edits, Logseq users rely on several subtle cues:

### 7.1 Type-to-edit
- When a block is selected (but not editing), pressing any printable key instantly enters edit mode, appends that character, and positions the caret after it. Selections across multiple blocks keep their visual highlight, but only the focused block begins editing.

### 7.2 Selection-mode shortcuts
- `Shift+Enter` on a selected block opens that block (or the entire selection) in the right sidebar. The main outline keeps focus where it was.
- `Cmd+A` cycles: first press selects text in the current block (when editing), second press selects the block, third press selects its parent, fourth selects the entire visible outline. Users expect this cycle everywhere.

### 7.3 Pointer gestures
- `block-content-on-pointer-down` short-circuits when the target is a link, table, attachment, code toggle, etc. (see `target-forbidden-edit?`). Everywhere else it clears selection, saves the current block, and either toggles selection or enters edit mode.
- **Shift+Click** seeding: the first Shift+Click stores `:selection/start-block`; subsequent Shift+Clicks highlight every DOM node between anchor and target via `editor-handler/highlight-selection-area!`. If the user reverses direction, `drop-last-selection-block!` removes blocks one by one to match Logseq’s contract.
- **Cmd (Meta)+Click** toggles individual blocks in/out of the current selection without touching the anchor. **Cmd+Shift+Click** appends contiguous ranges to an existing multi-selection so users can build disjoint sets, matching Logseq’s `{:append? true}` path.
- **Shift+Click on bullets or block references** calls `state/sidebar-add-block!` so the block (or referenced block) opens in the right sidebar. Hovering a block reference shows the preview tooltip, and default clicks follow the reference (PDF annotations jump into the PDF panel; whiteboard refs honor portal context).
- On mobile, each tap toggles selection membership (no modifier keys). When the final selected block is removed, `:mobile/show-action-bar?` is cleared so the bulk action bar hides exactly like upstream.
- Drag handles behave the same as keyboard moves: the ghost indicator mirrors DOM order, and holding **Alt** during drop converts the gesture into “insert block reference” (see §5.4).

### 7.4 Slash commands & autocomplete
- Typing `/` records the trigger in `:editor/action` (`:commands`) and spawns the inline palette at the caret. Arrow keys navigate, Enter dispatches the highlighted command vector, and Escape clears `:editor/action` without mutating the block.
- Every slash entry in `frontend.commands` returns a vector of steps (e.g., `[[:editor/input ...] [:editor/show-input {...}] ...]`). The same command often has DB-only and file graph branches (`db-based-embed-block`, `file-based-embed-block`, `query-steps`, `calc-steps`). Evo needs to keep the branching so `/embed` and `/query` behave correctly on both backends.
- Multi-step commands (link, image link, Zotero, properties) use `state/set-editor-show-input!` to render inline forms. The palette stores placeholders, autofocus targets, and even secondary prompts (see `link-steps`, `image-link-steps`).
- Plugins extend the palette through `register-slash-command` / `*extend-slash-commands`. The extension hook appends custom entries with their own action vectors, so the spec must guarantee a comparable API.
- Additional triggers (`#`, `:` and `command-ask` for `\`) reuse the same infrastructure for tags, properties, and whiteboard macros; all of them rely on fast `search.cljs` filtering with incremental updates every keypress.

### 7.5 Quick switcher / global search
- `Cmd+K` calls `frontend.modules.shortcut.config/go/search :global`, which first escapes edit mode (`editor-handler/escape-editing {:select? true}`) and then routes to the quick switcher overlay (`route-handler/go-to-search!`). Results stream from `components/search.cljs` and update every keystroke; Arrow keys move the highlight, Enter opens the selected page/block, Escape exits and restores the previous focus.
- `Cmd+Shift+K` scopes the overlay to the current page (`:current-page` mode). Both modes reuse the same component and key handling.
- `Cmd+Shift+P` switches to the command palette view (`:commands`). `frontend.handler.command-palette` registers built-in shortcuts, merges plugin registrations, and sorts everything by invoke frequency using a local `commands-history` ring buffer. When a command executes, `add-history` records its timestamp so frequently used commands float to the top automatically.
- `register-global-shortcut-commands` ensures every entry in `modules/shortcut/config.cljs` with a global binding also surfaces inside the palette, so users can discover keyboard shortcuts even if they forget the chord.

### 7.6 Clipboard permutations
- `Cmd+C` delegates to `shortcut-copy`: selections call `copy-selection-blocks` (returns Markdown, HTML, and the custom MIME `web application/logseq`), editing with no range calls `copy-current-block-ref` (copies `((uuid))`), and editing with a text range falls back to the browser's native copy. If the caret lives in a PDF pane, Logseq pulls the highlight text instead.
- `Cmd+Shift+C` maps to `shortcut-copy-text`, which copies the currently selected blocks but strips Logseq metadata so users can paste into other editors without list markers, then falls back to the browser copy command when not in selection mode.
- `Cmd+Shift+E` copies the current block as an embed (`{{embed (())}}`) and `Cmd+Shift+R` replaces the block reference under the caret with its resolved content; both functions live in `handler/editor.cljs`.
- Cut operations set `:editor/block-op-type` to `:cut` so paste handlers know whether to keep UUIDs and how to build revert transactions.
- Block copy/cut writes Markdown, HTML, and the custom MIME payload to the clipboard. On paste, `get-copied-blocks` refuses to import data when the clipboard's `:graph` doesn't match the current repo, mirroring Logseq's guard rails.

#### 7.6.1 Clipboard MIME Types & Data Format
Logseq writes three MIME types simultaneously to the clipboard (via Async Clipboard API):
- `text/plain`: Plain text for external apps
- `text/html`: HTML representation preserving list structure
- `web application/logseq`: Custom format containing PR-stringified EDN map:
  ```clojure
  {:graph "repo-name"
   :embed-block? false
   :blocks [{:block/uuid "..." :block/content "..." ...}]}
  ```

**Platform limitations:**
- Android: Clipboard API fallback due to permission errors (uses `navigator.clipboard.writeText` only)
- Capacitor: Native bridge for mobile platforms
- Graceful degradation: Falls back to text-only if `ClipboardItem` unsupported

#### 7.6.2 Cut vs Copy Behavior Differences
| Aspect | Copy | Cut |
|--------|------|-----|
| `:editor/block-op-type` | `:copy` | `:cut` |
| UUID handling on paste | New UUIDs generated | Original UUIDs preserved |
| Revert transaction | None | Stored in `[:editor/last-replace-ref-content-tx repo]` |
| Source blocks | Unchanged | Deleted immediately after clipboard write |

**Cut exclusions** (blocks silently skipped):
- Query blocks (`data-query="true"`)
- Transclude blocks (`data-transclude="true"`)
- Property value containers

### 7.7 Paste semantics
- `Cmd+Shift+V` routes to `paste-handler/editor-on-paste-raw!`, which deletes the current selection and inserts clipboard text verbatim (no Markdown parsing, no block splitting).
- Standard paste first checks for the custom MIME payload (`web application/logseq`). If the clipboard graph matches the current repo, Logseq pastes the serialized blocks (keeping UUIDs for cut operations and replaying revert transactions when necessary). Only when that payload is absent does it fall back to HTML/text parsing.
- `paste-text-or-blocks-aux` detection order: attachments (if files present and the user prefers file pasting), rich text (`html-parser/convert`), macro URLs (`wrap-macro-url` for video/Twitter), block refs (pasting `((uuid))` inside `(( ))` only inserts the ID), and finally plain text.
- Multi-paragraph pastes are split whenever blank lines appear. `paste-segmented-text` also injects list markers so Markdown/Org bullets continue to render as lists when pasted into Logseq; single newlines stay inside the current block as literal `\n` characters.
- When the paste payload contains `<whiteboard-tldr>` metadata, the handler extracts the JSON blob and injects the referenced shapes instead of raw text, matching Logseq's whiteboard copy/paste workflow.
- Link-aware pasting: if the user selects a `[label](url)` range (Markdown) or `[[page][label]]` (Org), pasting a new URL replaces only the target portion, not the label, by way of `selection-within-link?`.

#### 7.7.1 Paste Edge Cases (Verified Against Upstream)

**Graph mismatch detection:**
- Blocks only paste if `:graph` field matches current repo
- Mismatch: Falls back to plain text paste (no cross-graph block references)
- Embed block validation: Prevents embedding parent blocks as their own properties (shows error notification)

**Paste into code blocks:**
- `editing-display-type-block?`: Detects Logseq display-type properties
- `thingatpt/markdown-src-at-point`: Detects markdown fenced code (```)
- `thingatpt/org-admonition&src-at-point`: Detects org source blocks
- **Edge case**: Plain text paste only (no block parsing) when cursor inside code block
- **iOS exception**: Skips special handling (uses native iOS paste behavior)

**Multi-paragraph segmentation rules:**
- Splits on 2+ consecutive newlines: `#"(?:\r?\n){2,}"`
- Auto-prefixes each paragraph with bullet if not already formatted:
  - Markdown: `- ` prefix
  - Org: `* ` prefix
- Detection regex: `#"(?m)^\s*(?:[-+*]|#+)\s+"` (Markdown) / `#"(?m)^\s*\*+\s+"` (Org)

**Block ref in parentheses context:**
- Pasting `((block-ref-uuid))` between parens `(())`
- Result: Inserts UUID only (strips outer parens to avoid `((((uuid))))`)

**URL auto-wrapping macros:**
- Twitter/X links: Wrapped as `{{twitter <url>}}`
- YouTube/video links: Wrapped as `{{video <url>}}`
- Selected text + URL paste: Converts to `[selected-text](url)`

**Paste position & nesting logic:**
- **Target resolution**: Defaults to editing block, falls back to parent page
- **Empty block with children**: Places as previous sibling instead of child
- **Nested blocks into empty target**: Only pastes if parent has children
- **Replace-empty-target optimization**: Single-block paste replaces empty target content

**File/image paste:**
- Checks both HTML and files (prioritizes files if present)
- Calls `upload-asset!` with block ID and file list
- Uses block's `:block/format` for proper markup

**DB-based vs file-based graph differences:**
- DB graphs: Strips `:block/tags`, applies `db-content/replace-tags-with-id-refs`
- File graphs: Preserves properties as strings

### 7.8 Undo/redo focus memory
- Undo and redo reapply not just document content but also the interaction state: if the user was editing block B when the change occurred, undo returns the caret to block B (or reselects it) exactly as Logseq does.
- `frontend.handler.history/undo!` and `redo!` debounce requests (20 ms), pause history (`:history/paused?`), save the current block, and then either merge the serialized UI state (`ui-state-str`) back into `frontend.state` or restore the caret from the stored `editor-cursors`. Evo must mirror this so routing (page vs block) and selection survive the undo stack.

#### 7.8.1 History Stack Architecture
Logseq maintains two repo-indexed stacks (via atoms):
- `*undo-ops`: Operations to undo
- `*redo-ops`: Operations to redo

**Stack constraints:**
- Max 100 operations per stack; when exceeded, oldest 50% discarded
- Each "operation" is a composite vector of multiple items

**Operation types recorded per change:**
```clojure
;; Composite undo operation example:
[
  [::record-editor-info {:block-uuid "..." :start-pos 10 :end-pos 15 :container-id ...}]
  [::db-transact {:tx-data [...datoms...] :tx-meta {...} :added-ids #{...} :retracted-ids #{...}}]
]

;; UI state operation (route changes):
[[::ui-state "transit-serialized-string"]]
```

| Type | Content | Purpose |
|------|---------|---------|
| `::record-editor-info` | cursor pos, block UUID, container | Restore cursor position |
| `::db-transact` | tx-data (datoms), metadata, IDs | Apply reversed datoms |
| `::ui-state` | serialized route + sidebar state | Redirect + restore UI |

#### 7.8.2 Cursor Restoration Logic
```clojure
;; For undo: use FIRST cursor in operation
;; For redo: use LAST cursor (or first if unavailable)
;; Undo prefers start-pos; redo prefers end-pos
```

Editor info captured **after** editor mounts (via `did-mount!`), **skipped during active undo/redo** to prevent recursion.

#### 7.8.3 Batching Rules (What Counts as One Undo Step)
Operations recorded only when ALL conditions met:
- `client-id` matches local client (no remote ops)
- Has `outliner-op` in tx-meta
- Not explicitly disabled (`gen-undo-ops? != false`)
- Not daily journal creation (`create-today-journal?` absent)

**Structural operation metadata:**
- Indent/outdent: `{:outliner-op :move-blocks :real-outliner-op :indent-outdent}`
- Move-blocks: `{:outliner-op :move-blocks}`
- All attribute changes bundled in single datom set

#### 7.8.4 Undo/Redo Edge Cases

**Undo after page navigation:**
- Route changes captured separately via `::ui-state`
- If undo encounters UI state first:
  1. Triggers route redirect to previous page
  2. Restores: `sidebar-open?`, `sidebar-collapsed-blocks`, `sidebar/blocks`
  3. Does NOT restore editor cursor (different page context)
- Allowed routes: `:home`, `:page`, `:page-block`, `:all-journals`

**Undo with collapsed blocks:**
- `ui/sidebar-collapsed-blocks` = set of collapsed block IDs
- Restored as part of `::ui-state` operation (not in DB transaction)

**Block moved with target deleted:**
- Error: "This block has been moved or its target has been deleted"
- Recovery: History cleared, user must manually redo operation

**Undo delete when new children added:**
- Error: "Children still exists"
- Recovery: Operation skipped, history cleared

**Non-local transactions (RTC/sync):**
- Extra validation checks for children conflicts
- Only local client's operations in local undo stack

**Undo disabled contexts:**
- Inside code blocks (`:editor/code-block-context` set)
- Current block saved before undo (`editor/save-current-block!`)
- Editor actions cleared (`state/clear-editor-action!`)

#### 7.8.5 Debouncing & Async Handling
- **Debounce window**: 20ms (prevents rapid Cmd+Z spam)
- **Async sequencing**: Previous undo must complete before next starts
- **History pause**: Set `true` during restore, prevents recording intermediate states

#### 7.8.6 Datom Reversal Logic
When undoing:
1. `retracted` datoms become `add` operations (restore deleted data)
2. `added` datoms become `retract` operations (undo created data)
3. **Ref validation**: Only restore references if target entity still exists
4. **Cascade deletes**: Uses `:db/retractEntity` for deleted blocks

For redo: Applies reversed datoms in forward order, re-validates entity existence.

Downstream parity trackers should reference these expectations whenever a client diverges so QA can verify the difference explicitly.

### 7.9 Page Management

Logseq treats pages as first-class entities with specific UI patterns for creation, deletion, and renaming.

#### 7.9.1 Page Creation
- **Via sidebar**: "New page" button prompts for title, creates page with one empty block, navigates to it
- **Via page reference**: Clicking `[[NonExistent Page]]` auto-creates the page and navigates
- **Via quick switcher**: Typing a non-matching name and pressing Enter creates + navigates
- **Validation**: Page titles are trimmed, empty titles rejected
- **Default content**: New pages always get one empty block (never truly empty)

#### 7.9.2 Page Deletion
- **Confirmation**: Logseq shows `js/confirm("Are you sure you want to delete this page?")` dialog
- **Success feedback**: Toast notification "Page X was deleted successfully!" (`:success` type)
- **Soft delete**: Page and all descendants moved to internal trash (not permanently erased)
- **Current page handling**: If deleting current page, switches to another page (first available)
- **Special case**: Deleting the "home" page may enable journals mode (context-dependent)

**Source**: `frontend/handler/page.cljs`, `delete!` function

#### 7.9.3 Page Rename
- **Inline editing**: Click on page title to enter edit mode
- **Commit on blur/Enter**: Saves new title
- **Cancel on Escape**: Reverts to previous title
- **Validation**: Empty titles rejected, whitespace trimmed
- **Reference updates**: All `[[OldName]]` references updated to `[[NewName]]` across graph

**Source**: `frontend/components/page.cljs`, title component with editable state

#### 7.9.4 Notification/Toast System
Logseq uses a toast notification system for feedback:

| Type | Style | Auto-dismiss | Use case |
|------|-------|--------------|----------|
| `:success` | Green accent | ~2000ms | Successful operations |
| `:warning` | Yellow/amber | Stays | Warnings, requires attention |
| `:error` | Red | Stays | Failures, needs user action |
| `:info` | Blue | ~2000ms | Informational messages |

**Implementation**: `frontend/handler/notification.cljs`, renders via `frontend/components/notification.cljs`

**Key behaviors:**
- Positioned at bottom-center of viewport
- Stacks when multiple notifications active
- Click-to-dismiss on any notification
- Auto-dismiss timers configurable per notification

#### 7.9.5 Evo Divergence: Undo Pattern
Evo uses a different UX pattern for page deletion:

| Aspect | Logseq | Evo |
|--------|--------|-----|
| Confirmation | Upfront `js/confirm` dialog | None (delete immediately) |
| Feedback | Success toast after | Toast with **Undo** button |
| Recovery | No undo (soft-deleted) | 5-second undo window |

**Rationale**: The "delete + undo" pattern reduces friction for exploratory workflows while maintaining recoverability. Users who delete by mistake can undo within the toast timeout. This matches modern design patterns (Gmail, Slack, etc.).

### 7.10 Left Sidebar Structure

Logseq's left sidebar (`components/left_sidebar.cljs`) has a specific hierarchy and interaction model.

#### 7.10.1 Sidebar Sections (Top to Bottom)

| Section | Contents | Collapsible | Notes |
|---------|----------|-------------|-------|
| **Navigation** | Journals, Flashcards, Graph View, All Pages | No | Filterable via gear icon |
| **Favorites** | User-pinned pages | Yes | Draggable reordering |
| **Recent** | Last 15-20 distinct pages | Yes | Auto-populated, excludes hidden |

**Navigation items** (customizable visibility):
- Journals (calendar icon) - becomes "Home" when journals disabled
- Flashcards (infinity icon) - shows card count badge
- Graph View (hierarchy icon)
- All Pages (files icon)
- Whiteboards (writing icon) - file-based graphs only
- Tags/Assets/Tasks (hash icon) - db-based graphs only

#### 7.10.2 Page Item Affordances

Each page item in sidebar has:

```
┌────────────────────────────────────────┐
│  📄 Page Title                    •••  │  ← dots menu on hover
└────────────────────────────────────────┘
```

**Visual states:**
- Default: Icon + title
- Hover: Reveals three-dot menu (`.group-hover:block`)
- Active/current: Highlighted background
- Untitled pages: Title at 50% opacity

**Dots menu actions:**
- ⭐ Add to favorites / Unfavorite
- 📁 Open in folder (Electron only)
- 📄 Open with default app (Electron only)
- 📐 Open in right sidebar

**Right-click context menu:** Same actions as dots menu

**Keyboard/click modifiers:**
- Click: Navigate to page
- Shift+Click: Open in right sidebar
- Cmd/Ctrl+Click: (no special behavior in sidebar)

#### 7.10.3 Sidebar Interactions

- **Collapsible sections**: Chevron toggle, state persisted
- **Virtualized scrolling**: For performance with many pages
- **Resizable width**: Drag edge, stored in localStorage
- **Scroll shadow**: "is-scrolled" class adds top shadow
- **Touch support**: Swipe gestures on mobile

**Source**: `frontend/components/left_sidebar.cljs:46-538`

### 7.11 Daily Journals

Journals are date-based pages with specific behaviors but fewer constraints than expected.

#### 7.11.1 Journal Creation

- **Auto-created on startup** via `page-handler/create-today-journal!`
- **Conditions for auto-creation:**
  - Journals feature enabled (always true for db-based graphs)
  - App not in loading/importing state
  - Not in publishing mode
  - Today's journal doesn't already exist
- **Tagged** with `:logseq.class/Journal` class (db-based graphs)

#### 7.11.2 Journal Date Format

- **User-configurable** via `state/get-date-formatter`
- **Default formats supported:**
  - "MMM do, yyyy" (e.g., "Dec 14th, 2025")
  - "yyyy-MM-dd" (e.g., "2025-12-14")
- **Validation**: `valid-journal-title?` ensures format compliance
- **Parsing**: Uses cljs-time with `safe-journal-title-formatters`

#### 7.11.3 Journal Constraints (or lack thereof)

| Operation | Allowed? | Notes |
|-----------|----------|-------|
| **Delete** | ✅ Yes | No special protection |
| **Rename** | ✅ Yes | No special protection |
| **Edit content** | ✅ Yes | Same as regular pages |
| **Add to favorites** | ✅ Yes | Same as regular pages |

**Protected pages** (cannot be deleted):
- "contents" page (hardcoded special page)
- Pages with `built-in?` flag (db-based graphs)
- Pages in publishing mode

**Journals have NO delete protection** - surprising but verified in source.

#### 7.11.4 Journal Navigation

- **All Journals view**: Virtualized list, latest marked with `.journal-last-item`
- **Prev/Next navigation**: `go-to-prev-journal!` / `go-to-next-journal!`
- **Date math**: Uses cljs-time to add/subtract days

#### 7.11.5 Journal Visual Treatment

- **Same icon as regular pages** (file icon)
- **No special badge or indicator** in sidebar
- **Distinguished by title format only** (date string)

**Source**: `frontend/handler/journal.cljs`, `frontend/components/journal.cljs`

### 7.12 Page Ordering in Lists

#### 7.12.1 Favorites
- **User-defined order** via drag-and-drop
- **Persisted** in user config
- **Draggable** using `on-drag-end` handler

#### 7.12.2 Recent Pages
- **Most recent first** (reverse chronological)
- **Limited to 15 distinct pages** (stored)
- **Display up to 20** in UI
- **Excludes hidden pages**

#### 7.12.3 All Pages View
- **Sortable by**: Title (A-Z), Created, Updated
- **Filterable by**: Search query, tags
- **Paginated** for large graphs

---

## 8. Extensibility & Hooks Architecture

Logseq exposes a comprehensive plugin system for extending behavior. Understanding these patterns is valuable for designing comparable extension points.

### 8.1 Plugin System Overview

**Core entry point**: `frontend/handler/plugin.cljs`

Plugin lifecycle managed via LSPluginCore JavaScript library:
1. **Registration**: Plugin metadata stored in `:plugin/installed-plugins`
2. **Lifecycle events**: registered, reloaded, unregistered, disabled, enabled
3. **Async loading**: Preboot plugins load before UI; async plugins load after

### 8.2 Event-Driven Architecture (Pub/Sub)

```clojure
;; Publishing events
(state/pub-event! [:event-key arg1 arg2 ...])

;; Events dispatched via multimethod
(defmulti handle first)
(defmethod handle :graph/added [_] ...)
```

**Key events plugins can observe:**
- `:graph/added` - Graph loaded
- `:plugin/hook-db-tx` - Database transaction occurred
- `:exec-plugin-cmd` - Plugin command execution
- `:rebuild-slash-commands-list` - Command palette rebuild
- `:editor/load-blocks` - Blocks loaded for editing

### 8.3 Hook Installation API

```clojure
;; Install hook
(state/install-plugin-hook pid hook opts)  ;; opts can be true or custom config

;; Uninstall hook
(state/uninstall-plugin-hook pid hook-or-all)

;; Hooks stored in: :plugin/installed-hooks -> hook -> {pid opts}
```

**Hook categories:**

| Category | Example Hooks | Description |
|----------|---------------|-------------|
| **App hooks** | `:graph-after-indexed`, `:today-journal-created`, `:theme-changed` | Application lifecycle |
| **Editor hooks** | `:editor/hook`, `slot:UUID` | Editor-specific events, per-block UI injection |
| **DB hooks** | `:db/changed`, `block:UUID` | Database changes, block-specific with tx data |
| **Command hooks** | `:before-command-invoked TYPE`, `:after-command-invoked TYPE` | Pre/post any command |

**Hook invocation flow:**
```clojure
(hook-plugin :app "graph-after-indexed" {:graph repo} plugin-id)
(hook-plugin :editor "slot:uuid" {:block block} plugin-id)
(hook-plugin :db "block:uuid" {:tx-data datoms :tx-meta meta} plugin-id)
```

### 8.4 Command Extension Mechanisms

#### Slash Commands (Plugin)
```clojure
;; Registration
(register-plugin-slash-command pid [cmd actions])
;; Stored in: :plugin/installed-slash-commands[pid][cmd] = actions
;; Triggers: :rebuild-slash-commands-list event
```

#### Simple Commands (Palette + Keybinding)
```clojure
;; Registration
(register-plugin-simple-command pid {:keys [type key label desc keybinding]} action)
;; Modes: :global, :editing, :non-editing
;; Auto-registers keybindings if provided
```

**Command execution triggers**: `[:exec-plugin-cmd pid cmd-key args]`

### 8.5 Renderer Hooks

| Renderer Type | Purpose | Registration Key |
|---------------|---------|------------------|
| **Fenced code** | Language-specific code block rendering | `register-fenced-code-renderer` |
| **Block slot** | Per-block UI injection points | `slot:UUID` format |
| **Route** | Custom route-based rendering | `register-route-renderer` |
| **Daemon** | Background/persistent components | `register-daemon-renderer` |
| **Extensions** | Enhance existing extensions | `register-extensions-enhancer` |

### 8.6 Block Change Tracking

```clojure
(defn hook-plugin-block-changes [{:keys [blocks tx-data tx-meta]}]
  ;; For each block with installed hook:
  (let [type (str "block:" (:block/uuid b))]
    (hook-plugin-db type {:block b :tx-data ... :tx-meta ...})))
```

Plugins register for specific block UUIDs; hooks fire on any transaction affecting those blocks.

### 8.7 Plugin State Schema

```clojure
:plugin/installed-plugins         ;; {pid -> metadata}
:plugin/installed-themes          ;; [{:pid :url :mode ...}]
:plugin/installed-slash-commands  ;; {pid -> {cmd -> actions}}
:plugin/installed-ui-items        ;; {pid -> [[type opts pid]]}
:plugin/installed-hooks           ;; {hook -> {pid -> opts}}
:plugin/installed-resources       ;; {pid -> {type -> {key -> opts}}}
:plugin/installed-services        ;; {pid -> {:search {name -> opts}}}
:plugin/simple-commands           ;; {pid -> [[type cmd action pid]]}
:plugin/preferences               ;; User preferences JSON
```

### 8.8 Plugin API Surface (Key Functions)

**Plugin Management:**
- `install_plugin_hook`, `uninstall_plugin_hook`
- `should_exec_plugin_hook`
- `load_plugin_config`, `load_plugin_readme`

**Storage:**
- `write_dotdir_file`, `read_dotdir_file` (`.logseq/` directory)
- `write_plugin_storage_file`, `read_plugin_storage_file`
- `save_user_preferences`, `load_user_preferences`

**Editor APIs** (50+ functions):
- `get_current_block`, `edit_block`, `insert_block`, `remove_block`
- `get_current_page`, `create_page`, `delete_page`, `rename_page`
- Tree operations, selection management

**DB APIs:**
- `datascript_query`, `custom_query` (DataScript access)

### 8.9 Extension Design Principles (Observed)

1. **Atom-based state**: All plugin state in `state/state` atom
2. **Async channels**: `core.async` for event batching/processing
3. **Multimethods**: Extensible event dispatch
4. **Keyword namespacing**: `:plugin/xxx` keys, `:plugin.pid/xxx` commands
5. **Registry atoms**: Local tracking for fenced-code, route renderers
6. **Deferred promises**: `pub-event!` returns deferred for async handling
7. **Normalized storage**: Plugin data in `.logseq/` directory with JSON files

### 8.10 Extensibility Constraints

- **No protocol-based hooking** in kernel (simple function calls only)
- **Synchronous hooks** for DB changes (no async DB hooks)
- **Plugin isolation**: Limited to JS sandbox (LSPluginCore handles)
- **Hook installation** requires plugin registered in state
- **Block-specific hooks** identified by UUID strings (hyphens become underscores)

---

## 9. Summary Checklist (Behavioral)

### Core Interaction
- [ ] Edit ↔ View states never overlap; Escape/background click clears selection instantly.
- [ ] Cursor memory keeps grapheme column across blocks and honors fold/zoom scope.
- [ ] Shift+Arrow only exits text selection at visual boundaries, then extends block selection one step at a time.
- [ ] Structural moves (indent/outdent, Cmd+Shift+Arrow climb/descend, drag/drop with Alt for references) match Logseq's tree rules.
- [ ] Enter/Shift+Enter, Backspace/Delete, whitespace trimming, and merge semantics behave exactly like Logseq.
- [ ] Keymap parity: Cmd+A cycle, Cmd+Shift+A select-all-visible, Cmd+O follow link, Slash palette, Cmd+K + Cmd+Shift+P overlays.
- [ ] Type-to-edit, sidebar open-on-Shift+Enter, bullet-click collapse plus Shift/Meta pointer gestures, hover previews, and quick switcher UX feel identical to Logseq.

### Clipboard (FR-Clipboard-01..06)
- [ ] Three MIME types written: `text/plain`, `text/html`, `web application/logseq`
- [ ] Cut preserves UUIDs on paste; copy generates new UUIDs
- [ ] Graph mismatch detection falls back to plain text paste
- [ ] Paste into code blocks inserts plain text only (no parsing)
- [ ] Multi-paragraph paste splits on 2+ consecutive newlines
- [ ] Block ref in parens context strips outer parens
- [ ] URL auto-wrapping for Twitter/YouTube links
- [ ] Link-aware pasting replaces URL portion only when selection within link

### Undo/Redo (FR-Undo-01..04)
- [ ] History stack max 100 ops; oldest 50% discarded when exceeded
- [ ] Cursor restored: undo uses first cursor, redo uses last cursor
- [ ] Batching: single outliner-op = one undo entry
- [ ] Route changes captured as `::ui-state` operations separately
- [ ] Undo disabled inside code blocks
- [ ] Debounce 20ms prevents rapid Cmd+Z spam
- [ ] Block-moved-or-target-deleted gracefully clears history

### Extensibility (FR-Plugin-01..03)
- [ ] Hooks installable via `install_plugin_hook` API
- [ ] Slash commands extendable via `register-plugin-slash-command`
- [ ] Block change tracking via `block:UUID` hooks
- [ ] Fenced code renderers registrable per language

This document supersedes all prior Logseq parity specs. Any future deviations must be recorded in `LOGSEQ_PARITY.md`.
