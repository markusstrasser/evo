# Hotkeys Quick Reference

## All Hotkeys by Category

### Navigation (↑↓)
| Hotkey | Context | Action |
|--------|---------|--------|
| `↑` / `↓` | Non-editing | Move selection to prev/next block |
| `Shift+↑` / `Shift+↓` | Any | Extend selection prev/next |

### Block Operations (Tab, Backspace, Enter)
| Hotkey | Context | Action |
|--------|---------|--------|
| `Tab` | Non-editing | Indent block |
| `Shift+Tab` | Non-editing | Outdent block |
| `Backspace` | Non-editing | Delete block to trash |
| `Enter` | Non-editing | Create new block & enter edit |

### Editing (Inside Text)
| Hotkey | Context | Action |
|--------|---------|--------|
| `Escape` | Editing | Exit edit mode |
| `↑` / `↓` | Editing | Navigate blocks at boundary |
| `Enter` | Editing | Create new block below |
| `Cmd/Ctrl+Backspace` | Editing | Merge with previous block |

### Movement (Cmd+Shift+Arrow or Alt+Shift+Arrow)
| Hotkey | Action |
|--------|--------|
| `Cmd+Shift+↑` | Move selected block(s) up |
| `Cmd+Shift+↓` | Move selected block(s) down |
| `Alt+Shift+↑` | Move selected block(s) up (alternative) |
| `Alt+Shift+↓` | Move selected block(s) down (alternative) |

### Folding & Navigation (Cmd+)
| Hotkey | Action |
|--------|--------|
| `Cmd+;` | Toggle collapse/expand |
| `Cmd+↑` | Collapse (hide children) |
| `Cmd+↓` | Expand all (recursively) |
| `Cmd+.` | Zoom in (focus block) |
| `Cmd+,` | Zoom out (back to parent) |

### Smart Features
| Hotkey | Action |
|--------|--------|
| `Cmd+Enter` | Toggle checkbox [ ] ↔ [x] |

### History
| Hotkey | Action |
|--------|--------|
| `Cmd+Z` | Undo |
| `Cmd+Shift+Z` | Redo |

---

## Context Modes

### Non-Editing (Block Navigation)
- Block is selected but cursor is not in text
- Tab/Shift+Tab indent/outdent
- Backspace deletes
- Enter creates new block

### Editing (Text Edit)
- Block has focus, cursor is blinking in text
- Arrow keys edit text (unless at boundary)
- Escape exits edit mode
- Tab/Shift+Tab handled by global keymap
- Cmd+Backspace merges blocks

### Global (Always Active)
- Cmd+Shift+Arrow moves blocks
- Cmd+; folds/unfolds
- Cmd+. zooms in
- Cmd+, zooms out
- Cmd+Enter toggles checkbox
- Cmd+Z undoes

---

## Special Behaviors

### "Start Typing" (Logseq-style)
When a block is selected:
- Press any printable character → automatically enters edit mode
- No need to click or press Enter first

### Boundary Navigation (Logseq-style)
While editing:
- Arrow Up at START of block → exits edit, goes to prev block, enters at END
- Arrow Down at END of block → exits edit, goes to next block, enters at START
- This enables seamless navigation without manual mode switching

### Multi-Selection
- Click to select single block
- Shift+Click to extend selection
- Shift+Arrow to extend selection
- Then Tab/Shift+Tab/Backspace apply to all selected blocks

---

## File Locations

| Feature | File |
|---------|------|
| Hotkey Definitions | `src/keymap/bindings_data.cljc` |
| Hotkey Resolution | `src/keymap/core.cljc` |
| Intent Handlers | `src/plugins/*.cljc` |
| Global Keyboard Handler | `src/shell/blocks-ui.cljs` |
| Block Edit Handler | `src/components/block.cljs` |

---

## Adding a New Hotkey

### 1. Define Binding
Edit `src/keymap/bindings_data.cljc`:
```clojure
{:key "Y"} {:type :my-action}
```

### 2. Implement Handler
Register in a plugin (e.g., `src/plugins/struct.cljc`):
```clojure
(intent/register-intent! :my-action
  {:doc "Does something"
   :spec [...]
   :handler (fn [db intent]
              [{:op :update-node ...}])})
```

### 3. Reload
In REPL or dev: `(keymap.bindings/reload!)`

### 4. Test
```clojure
(api/dispatch db {:type :my-action})
```

---

## Debugging Hotkeys

### List all intents
```clojure
(api/list-intents)
```

### Resolve a hotkey
```clojure
(keymap/resolve-intent-type {:key "Tab" :shift true} db)
;=> :outdent-selected
```

### Dispatch manually
```clojure
(let [{:keys [db issues]} (api/dispatch db {:type :indent-selected})]
  (if (empty? issues) db (prn "Error:" issues)))
```

### Check binding in non-editing context
```clojure
(keymap/resolve-intent-type {:key "Enter"} db)
;=> :create-and-enter-edit
```
