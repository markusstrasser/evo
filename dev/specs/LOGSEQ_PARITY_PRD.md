# Logseq Interaction Parity – Product Requirement Document

## 1. Purpose & Scope
Deliver desktop Logseq (macOS build 0.10.x) behavior in Evo for editing, navigation, selection, structural changes, pointer gestures, slash commands, quick switcher, clipboard, and supporting UI. Applies to all outline contexts (pages, journals, zoomed blocks, sidebar).

## 2. Goals
1. Match Logseq muscle memory across keyboard and pointer interactions.
2. Keep state transitions (idle ⇄ selection ⇄ edit) identical, including folding/zoom edge cases.
3. Provide testable acceptance criteria for QA automation and manual certification.

## 3. Non-Goals
- Introducing new features beyond Logseq’s current desktop UX.
- Mobile/touch-specific interactions.
- Architectural guidance (Nexus, Replicant, etc.).

## 4. Personas
| Persona | Expectation |
|---------|-------------|
| Power user | Every shortcut behaves like Logseq; no surprise focus jumps. |
| Structural editor | Drag, Alt-drag, indent/outdent, move up/down preserve tree shape. |
| Note writer | Paste, slash commands, quick switcher, type-to-edit feel instantaneous. |
| QA/Support | Deterministic scenarios to verify parity. |

## 5. Functional Requirements (FR)
Each FR is a testable statement; see §6 for example BDD snippets.

### 5.1 Idle State (No Selection, No Edit)
- **FR-Idle-01**: In fully idle state, Enter/Backspace/Tab/Shift+Enter/Shift+Arrow/Cmd+Enter are no-ops.
- **FR-Idle-02**: ArrowDown selects first visible block; ArrowUp selects last.
- **FR-Idle-03**: Typing a printable character immediately enters edit mode on the focused block and appends that character.

### 5.2 State Machine & Selection Integrity
- **FR-State-01**: Edit mode and selection never overlap; entering edit clears selection.
- **FR-State-02**: Escape while editing → idle (no selection). Escape while viewing → clears selection.
- **FR-State-03**: If any selected block disappears (deleted, folded, moved outside scope), clear selection entirely.

### 5.3 Visible Scope, Zoom, Folding
- **FR-Scope-01**: Navigation/selection/structural commands operate only within visible outline root (page or zoom block).
- **FR-Scope-02**: Attempts to outdent/move beyond zoom root are no-ops.
- **FR-Scope-03**: Folded descendants are skipped. Editing a folded block auto-expands it; deleting a folded block deletes its whole subtree.

### 5.4 Navigation – Edit Mode
- **FR-NavEdit-01**: Up/Down maintain grapheme column; jump only after reaching first/last visual row.
- **FR-NavEdit-02**: Left at position 0 → previous visible block end; Right at end → next visible block start (parents/children included).
- **FR-NavEdit-03**: Single-block outlines never exit the block via arrows.
- **FR-NavEdit-04**: Shift+Arrow at row boundaries seeds selection with editing block, exits edit, extends selection by one.

### 5.5 Navigation – View Mode
- **FR-NavView-01**: ArrowUp/Down traverse previous/next visible block, skipping hidden.
- **FR-NavView-02**: Shift+Arrow maintains direction; contracting removes one block until only anchor remains.
- **FR-NavView-03**: Shift+Click selects range of visible blocks only.
- **FR-NavView-04**: Shift+Enter on selection opens blocks in sidebar while maintaining selection.

### 5.6 Editing Commands & Text Behavior
- **FR-Edit-01**: Enter splits block; caret starts at new block pos 0. Enter at position 0 inserts empty block above. Empty list Enter = unformat + create peer.
- **FR-Edit-02**: Shift+Enter inserts newline inside block.
- **FR-Edit-03**: Backspace at start merges with previous; Delete at end merges with next (children re-parented).
- **FR-Edit-04**: Whitespace-only blocks behave as empty for delete/merge/indent.
- **FR-Edit-05**: Tab/Shift+Tab indent/outdent: indent makes block last child of previous sibling; outdent keeps children; indenting first child/outdenting root block is no-op.
- **FR-Edit-06**: Tab inside fenced/inline code inserts literal tab/spaces.
- **FR-Edit-07**: After split/indent/outdent/move, caret stays with the moved block; undo/redo restores caret/selection context.

### 5.7 Structural Moves & Dragging
- **FR-Move-01**: Cmd+Shift+ArrowUp/Down reorder selection while preserving internal order.
- **FR-Move-02**: Climb/descend semantics mirror Logseq; operations with no target are no-ops.
- **FR-Move-03**: Dragging bullet reorders blocks; Alt+drag inserts block reference at drop target without moving source.

### 5.8 Clipboard & Paste
- **FR-Clipboard-01**: Cmd+C copies block with metadata; Cmd+Shift+C copies plain text; Cmd+Option+C copies block reference; Cmd+Shift+V pastes plain text.
- **FR-Clipboard-02**: Cutting blocks clears selection afterward.
- **FR-Clipboard-03**: Paste semantics—single newlines stay inline; blank lines split into multiple blocks (with list markers preserved).

### 5.9 Pointer & Hover
- **FR-Pointer-01**: Alt+Click on bullet toggles entire subtree.
- **FR-Pointer-02**: Hovering block reference shows preview; Cmd+Click opens in sidebar.

### 5.10 Slash Commands & Quick Switcher
- **FR-Slash-01**: Typing `/` opens inline palette without scrolling; navigation via Arrow keys, Enter, Escape works as Logseq.
- **FR-QuickSwitch-01**: Cmd+K/Cmd+P opens quick switcher overlay with instant filtering; Arrow navigation & Enter/Escape match Logseq behavior.

### 5.11 Undo/Redo
- **FR-Undo-01**: Undo/redo restore document content *and* interaction state (editing block, caret/selection) per discrete action.

## 6. Acceptance Test Examples (Gherkin)
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
(Extend with scenarios for paste, drag-alt, slash commands, etc.)

## 7. Traceability Matrix (excerpt)
| FR ID | Spec Section | Tests Needed | Status |
|-------|--------------|--------------|--------|
| FR-Idle-01..03 | §1 | Unit + E2E | TODO |
| FR-Scope-01..03 | §5/§7 | Unit + E2E | TODO |
| FR-NavEdit-01..04 | §2 | Unit + E2E | TODO |
| FR-Edit-01..07 | §4 | Unit + E2E | TODO |
| FR-Move-01..03 | §5 | Unit + E2E | TODO |
| FR-Clipboard-01..03 | §9 | Unit + E2E | TODO |
| FR-Slash-01 / FR-QuickSwitch-01 | §9 | Integration | TODO |
| FR-Undo-01 | §9 | Unit + E2E | TODO |

Update this matrix as implementations land.

## 8. References
- `dev/specs/LOGSEQ_SPEC.md` – behavior description (must stay synced with this PRD).
- `dev/specs/LOGSEQ_PARITY.md` – gap tracker (link each gap to FR IDs).
- Archived research: `dev/specs/archive/EDGE_CASES.md`, `dev/logseq-interaction-edge-cases.md`.
