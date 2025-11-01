# Hotkeys & Actions Documentation Index

This directory contains comprehensive documentation of the hotkey binding system and UI action implementation.

## Documents

### 1. HOTKEYS_AND_ACTIONS.md (617 lines)
**Comprehensive architecture guide**

The main reference covering:
- Architecture overview (4 layers: keymap, intent, plugin, UI)
- Complete hotkey definitions by context
- Hotkey resolution pipeline (DOM event → intent type)
- Intent registry & dispatch system
- All intent implementations by plugin
- UI event handling in detail
- Intent-to-operation compilation
- State management (ephemeral vs persistent)
- Data-driven binding system
- Keyboard event resolution details
- Common patterns & examples
- Testing hotkeys
- Architecture diagram

**Best for:** Understanding the complete system, debugging issues, extending functionality

### 2. HOTKEYS_QUICK_REFERENCE.md (166 lines)
**Quick lookup guide**

Quick tables for:
- All hotkeys by category (navigation, block ops, editing, movement, folding, etc.)
- Context modes (non-editing, editing, global)
- Special behaviors (start typing, boundary navigation, multi-selection)
- File locations
- Adding new hotkeys (4-step guide)
- Debugging commands

**Best for:** Quick lookups, memorizing hotkeys, adding new features

### 3. INTENTS_REFERENCE.md (453 lines)
**Complete intent catalog**

Detailed listing of:
- All registered intents with specifications
- Selection intents (unified reducer with 11 modes)
- Editing intents (6 total)
- Smart editing intents (4 total)
- Folding intents (4 total)
- Zoom intents (4 total)
- Structural intents - single (5 total)
- Structural intents - multi-selection (5 total)
- Movement/reordering (1 total)
- Reference intents (planned, not yet keybound)
- State summary
- Usage examples for REPL, UI, tests
- Implementation template
- Performance notes
- Extension guide

**Best for:** Intent reference, implementing handlers, testing intents

---

## Quick Navigation

### "I want to..."

| Goal | Document | Section |
|------|----------|---------|
| Learn the architecture | HOTKEYS_AND_ACTIONS.md | Part 1-5 |
| Find a hotkey | HOTKEYS_QUICK_REFERENCE.md | All Hotkeys |
| Add a new hotkey | HOTKEYS_QUICK_REFERENCE.md | Adding a New Hotkey |
| Understand intents | INTENTS_REFERENCE.md | Top section |
| Debug a hotkey | HOTKEYS_AND_ACTIONS.md | Part 11 |
| Implement an intent | INTENTS_REFERENCE.md | Template section |
| Understand state flow | HOTKEYS_AND_ACTIONS.md | Part 7 |
| Find a specific intent | INTENTS_REFERENCE.md | Index |
| Test an intent | INTENTS_REFERENCE.md | Testing Intents |

---

## File References

All documentation points to these source files:

### Core Hotkey System
| File | Purpose |
|------|---------|
| `src/keymap/bindings_data.cljc` | Hotkey definitions (38 lines) |
| `src/keymap/core.cljc` | Hotkey resolution (121 lines) |
| `src/keymap/bindings.cljc` | Binding registration (28 lines) |

### Intent System
| File | Purpose |
|------|---------|
| `src/kernel/intent.cljc` | Intent registry (79 lines) |
| `src/kernel/api.cljc` | Unified dispatch API (245 lines) |

### Intent Implementations (Plugins)
| File | Intents | Lines |
|------|---------|-------|
| `src/plugins/selection.cljc` | 1 (11 modes) | 170 |
| `src/plugins/editing.cljc` | 6 | 75 |
| `src/plugins/folding.cljc` | 8 | 186 |
| `src/plugins/smart_editing.cljc` | 4 | 110 |
| `src/plugins/struct.cljc` | 12 | 326 |
| `src/plugins/refs.cljc` | 0 (helper ops) | 257 |

### UI Event Handlers
| File | Purpose | Lines |
|------|---------|-------|
| `src/shell/blocks-ui.cljs` | Global keyboard handler | 315 |
| `src/components/block.cljs` | Block edit-mode handlers | 320 |

---

## Summary Statistics

### Hotkeys
- **Non-Editing Context:** 8 hotkeys
- **Editing Context:** 4 hotkeys
- **Global Context:** 12 hotkeys
- **Total:** 24 hotkeys (some overlap across contexts)

### Intents
- **Total Intent Types:** 36+
- **Selection Intents:** 1 (with 11 modes)
- **Editing Intents:** 6
- **Smart Editing:** 4
- **Folding:** 4
- **Zoom:** 4
- **Structural (single):** 5
- **Structural (multi):** 5
- **Movement:** 1

### State
- **Ephemeral (UI-only):** 6 properties
- **Persistent (in history):** 4 categories
- **Total Operations:** 3 (create-node, update-node, place)

---

## Key Concepts

### Layers
```
DOM Keyboard Event
    ↓ (parse-dom-event)
Normalized Event {:key "A" :mod false :shift false :alt false}
    ↓ (resolve-intent-type, context-aware)
Intent Type :indent-selected
    ↓ (apply-intent)
Handler executes
    ↓ (compiles to ops)
Operations [{:op :place ...}]
    ↓ (interpret through transaction pipeline)
Updated DB (with history recording)
```

### Contexts
- **Non-Editing:** Block selected, not in text edit mode
- **Editing:** Cursor blinking in block text
- **Global:** Always active, overrides context

### Intent to Operations
All intents compile to 3-operation kernel:
1. `:create-node` - Create new node
2. `:update-node` - Update properties
3. `:place` - Move/position node

### State Types
- **Ephemeral:** UI state (edit mode, folding, zoom) - not in history
- **Persistent:** Content state (text, structure) - recorded in undo/redo

---

## Related Documentation

- **CLAUDE.md** - Project philosophy and development setup
- **src/kernel/README** - Kernel architecture (if exists)
- **docs/REPLICANT.md** - Event handler syntax details
- **test/integration/keybinding_test.cljc** - Hotkey integration tests

---

## How to Use These Docs

### For Beginners
1. Start with HOTKEYS_QUICK_REFERENCE.md to see the hotkeys
2. Read HOTKEYS_AND_ACTIONS.md Part 1-2 for basic understanding
3. Try dispatching an intent in REPL (see INTENTS_REFERENCE.md "How to Use")

### For Feature Development
1. Check HOTKEYS_QUICK_REFERENCE.md "Adding a New Hotkey"
2. Reference INTENTS_REFERENCE.md for intent patterns
3. Use HOTKEYS_AND_ACTIONS.md Part 10 for common patterns

### For Debugging
1. Use HOTKEYS_QUICK_REFERENCE.md "Debugging Hotkeys"
2. Check HOTKEYS_AND_ACTIONS.md Part 11 for testing techniques
3. Reference INTENTS_REFERENCE.md "How to Use in Code"

### For Architecture Questions
1. Read HOTKEYS_AND_ACTIONS.md Parts 1-7 in order
2. See architecture diagram in Part 12
3. Reference file locations in this document

---

## Quick Commands

### In REPL

List all intents:
```clojure
(api/list-intents)
```

Resolve a hotkey:
```clojure
(keymap/resolve-intent-type {:key "Tab" :shift true} db)
;=> :outdent-selected
```

Dispatch an intent:
```clojure
(api/dispatch db {:type :indent-selected})
;=> {:db new-db :issues []}
```

Reload hotkeys after editing:
```clojure
(keymap.bindings/reload!)
```

### In Browser DevTools

Show current state:
```javascript
DEBUG.summary()
```

Show all events:
```javascript
DEBUG.inspectEvents()
```

---

## Document Statistics

| Document | Lines | Sections | Tables |
|----------|-------|----------|--------|
| HOTKEYS_AND_ACTIONS.md | 617 | 12 | 25+ |
| HOTKEYS_QUICK_REFERENCE.md | 166 | 6 | 8 |
| INTENTS_REFERENCE.md | 453 | 25+ | 8+ |
| **Total** | **1236** | **43+** | **41+** |

---

## Version Info

- Created: November 1, 2025
- Based on commit: main branch (see git log for current state)
- Scanned files: 13 core files (keymap, intent, plugins, UI)
- Total coverage: ~2,500 lines of source code analyzed

---

## Contributing to Documentation

When adding new hotkeys or intents:

1. Update `src/keymap/bindings_data.cljc` (hotkeys)
2. Update `src/plugins/*.cljc` (intent handler)
3. Update HOTKEYS_AND_ACTIONS.md (Part 1 & 4)
4. Update HOTKEYS_QUICK_REFERENCE.md (tables)
5. Update INTENTS_REFERENCE.md (new intent section)

---

Last Updated: 2025-11-01
