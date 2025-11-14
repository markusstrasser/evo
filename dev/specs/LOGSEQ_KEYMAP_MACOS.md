# Logseq Keymap Reference - macOS

**Source**: `logseq/src/main/frontend/modules/shortcut/config.cljs`
**Date Extracted**: 2025-11-13
**Purpose**: Complete reference for basic editing/navigation keybindings on macOS

---

## Core Editing - Text Manipulation

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Backspace** | `:editor/backspace` | `editor-backspace` | `backspace` | Delete char before cursor |
| **Delete** | `:editor/delete` | `editor-delete` | `delete` | Delete char after cursor |
| **Enter** | `:editor/new-block` | `keydown-new-block-handler` | `enter` | Create new block (context-aware) |
| **Shift+Enter** | `:editor/new-line` | `keydown-new-line-handler` | `shift+enter` | Insert literal newline |
| **Cmd+B** | `:editor/bold` | `bold-format!` | `mod+b` | Bold selection |
| **Cmd+I** | `:editor/italics` | `italics-format!` | `mod+i` | Italic selection |
| **Cmd+Shift+H** | `:editor/highlight` | `highlight-format!` | `mod+shift+h` | Highlight selection |
| **Cmd+Shift+S** | `:editor/strike-through` | `strike-through-format!` | `mod+shift+s` | Strikethrough selection |

---

## Core Editing - Kill Commands (Emacs-style)

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Ctrl+L** | `:editor/clear-block` | `clear-block-content!` | `ctrl+l` | Clear entire block |
| **Ctrl+U** | `:editor/kill-line-before` | `kill-line-before!` | `ctrl+u` | Kill from cursor to start |
| **Ctrl+K** | `:editor/kill-line-after` | `kill-line-after!` | `false` (macOS) | NOT BOUND on macOS |
| **Ctrl+W** | `:editor/forward-kill-word` | `forward-kill-word` | `ctrl+w` | Kill word forward |
| **Alt+W** | `:editor/backward-kill-word` | `backward-kill-word` | `false` (macOS) | NOT BOUND on macOS |

---

## Core Editing - Word Navigation

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Ctrl+Shift+F** | `:editor/forward-word` | `cursor-forward-word` | `ctrl+shift+f` | Move cursor forward by word |
| **Ctrl+Shift+B** | `:editor/backward-word` | `cursor-backward-word` | `ctrl+shift+b` | Move cursor backward by word |
| **Alt+A** | `:editor/beginning-of-block` | `beginning-of-block` | `false` (macOS) | NOT BOUND on macOS |
| **Alt+E** | `:editor/end-of-block` | `end-of-block` | `false` (macOS) | NOT BOUND on macOS |

---

## Navigation - Arrow Keys (While Editing)

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Up** | `:editor/up` | `shortcut-up-down :up` | `up` | Navigate to block above |
| **Down** | `:editor/down` | `shortcut-up-down :down` | `down` | Navigate to block below |
| **Left** | `:editor/left` | `shortcut-left-right :left` | `left` | Move cursor left (or to prev block) |
| **Right** | `:editor/right` | `shortcut-left-right :right` | `right` | Move cursor right (or to next block) |
| **Ctrl+P** | `:editor/up` | `shortcut-up-down :up` | `ctrl+p` | Emacs-style Up alias |
| **Ctrl+N** | `:editor/down` | `shortcut-up-down :down` | `ctrl+n` | Emacs-style Down alias |

---

## Selection - Block Selection (Non-editing)

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Alt+Up** | `:editor/select-block-up` | `on-select-block :up` | `alt+up` | Select block above |
| **Alt+Down** | `:editor/select-block-down` | `on-select-block :down` | `alt+down` | Select block below |
| **Shift+Up** | `:editor/select-up` | `shortcut-select-up-down :up` | `shift+up` | Extend selection up |
| **Shift+Down** | `:editor/select-down` | `shortcut-select-up-down :down` | `shift+down` | Extend selection down |
| **Enter** (selected) | `:editor/open-edit` | `open-selected-block!` | `enter` | Enter edit mode on selected block |
| **Shift+Enter** (selected) | `:editor/open-selected-blocks-in-sidebar` | `open-selected-blocks-in-sidebar!` | `shift+enter` | Open in sidebar |
| **Backspace/Delete** (selected) | `:editor/delete-selection` | `delete-selection` | `backspace` or `delete` | Delete selected blocks |

---

## Selection - Operations

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Cmd+A** | `:editor/select-parent` | `select-parent` | `mod+a` | Select parent block |
| **Cmd+Shift+A** | `:editor/select-all-blocks` | `select-all-blocks!` | `mod+shift+a` | Select all visible blocks |
| **Escape** | `:editor/escape-editing` | `escape-editing` | (no binding, called programmatically) | Exit edit mode |

---

## Block Manipulation

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Tab** | `:editor/indent` | `keydown-tab-handler :right` | `tab` | Indent block |
| **Shift+Tab** | `:editor/outdent` | `keydown-tab-handler :left` | `shift+tab` | Outdent block |
| **Cmd+Shift+Up** | `:editor/move-block-up` | `move-up-down true` | `mod+shift+up` | Move block up |
| **Cmd+Shift+Down** | `:editor/move-block-down` | `move-up-down false` | `mod+shift+down` | Move block down |
| **Cmd+Shift+M** | `:editor/move-blocks` | `move-selected-blocks` | `mod+shift+m` | Move selected blocks |

---

## Block Folding/Expanding

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Cmd+Down** | `:editor/expand-block-children` | `expand!` | `mod+down` | Expand children |
| **Cmd+Up** | `:editor/collapse-block-children` | `collapse!` | `mod+up` | Collapse children |
| **Cmd+;** | `:editor/toggle-block-children` | `toggle-collapse!` | `mod+;` | Toggle fold |

---

## Zoom (Focus)

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Cmd+.** | `:editor/zoom-in` | `zoom-in!` | `mod+.` or `mod+shift+.` | Zoom into block |
| **Cmd+,** | `:editor/zoom-out` | `zoom-out!` | `mod+,` | Zoom out |

---

## Clipboard

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Cmd+C** | `:editor/copy` | `shortcut-copy` | `mod+c` | Copy blocks |
| **Cmd+Shift+C** | `:editor/copy-text` | `shortcut-copy-text` | `mod+shift+c` | Copy as plain text |
| **Cmd+X** | `:editor/cut` | `shortcut-cut` | `mod+x` | Cut blocks |

---

## Undo/Redo

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Cmd+Z** | `:editor/undo` | `history/undo!` | `mod+z` | Undo |
| **Cmd+Shift+Z** | `:editor/redo` | `history/redo!` | `mod+shift+z` | Redo |
| **Cmd+Y** | `:editor/redo` | `history/redo!` | `mod+y` | Redo (alternative) |

---

## Special Actions

| Key | Logseq Intent | Handler | macOS Binding | Notes |
|-----|---------------|---------|---------------|-------|
| **Cmd+Enter** | `:editor/cycle-todo` | `cycle-todo!` | `mod+enter` | Toggle checkbox/TODO state |
| **Cmd+O** | `:editor/follow-link` | `follow-link-under-cursor!` | `mod+o` | Follow link at cursor |
| **Cmd+Shift+O** | `:editor/open-link-in-sidebar` | `open-link-in-sidebar!` | `mod+shift+o` | Open link in sidebar |
| **Cmd+L** | `:editor/insert-link` | `html-link-format!` | `mod+l` | Insert HTML link |
| **Cmd+Shift+E** | `:editor/copy-embed` | `copy-current-block-embed` | `mod+shift+e` | Copy block embed |
| **Cmd+Shift+V** | `:editor/paste-text-in-one-block-at-point` | `editor-on-paste-raw!` | `mod+shift+v` | Paste as plain text |
| **Cmd+Shift+R** | `:editor/replace-block-reference-at-point` | `replace-block-reference-with-content-at-point` | `mod+shift+r` | Replace block ref with content |

---

## Critical Behaviors (Verified from Source)

### Enter Key Behavior

**When block is selected (not editing)**:
- Binding: `enter`
- Intent: `:editor/open-edit`
- Handler: `editor-handler/open-selected-block! :right e`
- Behavior: Enters edit mode, cursor at `:max` (end of text)
- Source: `config.cljs:296-298`, `editor.cljs:3426-3439`

**When editing a block**:
- Binding: `enter`
- Intent: `:editor/new-block`
- Handler: `editor-handler/keydown-new-block-handler`
- Behavior: Creates new block below, moves cursor to new block
- Source: `config.cljs:206-208`

### Escape Key Behavior

**When editing**:
- Binding: `[]` (no direct key binding, called programmatically)
- Intent: `:editor/escape-editing`
- Handler: `editor-handler/escape-editing` (NO ARGS)
- Behavior: Exits edit mode WITHOUT selecting block (because `select?` parameter is nil by default)
- Source: `config.cljs:196-198`, `editor.cljs:3897-3910`

---

## Modifier Key Mapping

- `mod` → `Cmd` on macOS (Command key)
- `ctrl` → `Ctrl` on macOS (Control key)
- `alt` → `Option` on macOS (Alt/Option key)
- `shift` → `Shift` on macOS

---

## Implementation Notes

1. **Context-dependent bindings**: Many keys have different behavior in different contexts:
   - Enter while editing → create new block
   - Enter on selected block → enter edit mode
   - Backspace while editing → delete character
   - Backspace on selected block → delete block

2. **Emacs-style navigation**: Logseq supports Emacs-style navigation on macOS:
   - Ctrl+P/N for Up/Down
   - Ctrl+Shift+F/B for word navigation
   - Ctrl+U/W for kill commands

3. **macOS-specific bindings**: Some keys are NOT bound on macOS (marked as `false` in config):
   - Alt+K (kill-line-after) - not bound
   - Alt+W (backward-kill-word) - not bound
   - Alt+A/E (beginning/end of block) - not bound

4. **Selection vs Editing modes**: Different keybindings apply in different contexts:
   - `:non-editing` - When blocks are selected but not being edited
   - `:editing` - When inside a contenteditable block
   - `:global` - Available in all contexts
