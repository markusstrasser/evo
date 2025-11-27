# UI State Machine

Evo implements an explicit UI state machine for Logseq parity. All UI interactions must respect this three-state model.

## States

```
┌─────────────┐   click-block    ┌─────────────────┐
│   :idle     │ ───────────────▶ │   :selection    │
│ • no cursor │ ◀─────────────── │ • blue frames   │
│ • no blue   │  escape/bg-click │ • focus block   │
└──────┬──────┘                  └────────┬────────┘
       │                                  │
       │ type-to-edit / Cmd+Enter         │ Enter / start typing
       ▼                                  ▼
┌──────────────┐   Escape / blur   ┌─────────────────┐
│  :editing    │ ─────────────────▶│   :selection    │
│ • caret      │                   │                 │
│ • contentEdit│                   │                 │
└──────────────┘                   └─────────────────┘
```

| State | Description | Visual Cue |
|-------|-------------|------------|
| `:idle` | No block selected or editing | Nothing highlighted |
| `:selection` | One or more blocks selected | Blue highlight frames |
| `:editing` | One block in edit mode | Caret visible, contenteditable |

## State Determination

The current state is derived from session data:

```clojure
(defn current-state [session]
  (let [editing-id (get-in session [:ui :editing-block-id])
        selection-nodes (get-in session [:selection :nodes] #{})]
    (cond
      (some? editing-id)       :editing
      (seq selection-nodes)    :selection
      :else                    :idle)))
```

## Intent → State Requirements

Not all intents work in all states. This table shows which states allow each intent:

### Selection State Only

| Intent | Description |
|--------|-------------|
| `:enter-edit` | Enter key on selected block |
| `:open-in-sidebar` | Shift+Enter on selection |

### Editing State Only

| Intent | Description |
|--------|-------------|
| `:navigate-with-cursor-memory` | Arrow up/down while editing |
| `:navigate-to-adjacent` | Arrow left/right at text boundary |
| `:context-aware-enter` | Enter while editing (split/create) |
| `:smart-split` | Enter (alias for context-aware-enter) |
| `:insert-newline` | Shift+Enter (soft break) |
| `:delete` | Backspace/Delete |
| `:merge-with-prev` | Backspace at block start |
| `:merge-with-next` | Delete at block end |
| `:update-content` | Typing text |
| `:paste-text` | Paste clipboard |

### Editing OR Selection

| Intent | Description |
|--------|-------------|
| `:exit-edit` | Escape key |
| `:indent` | Tab |
| `:outdent` | Shift+Tab |
| `:move-selected-up` | Mod+Shift+Up |
| `:move-selected-down` | Mod+Shift+Down |
| `:toggle-subtree` | Collapse/expand |
| `:copy-blocks` | Copy |
| `:cut-blocks` | Cut |

### Any State (Universal)

| Intent | Description |
|--------|-------------|
| `:selection` | Select blocks (click, Shift+click) |
| `:toggle-fold` | Bullet click |
| `:zoom-in` / `:zoom-out` | Zoom navigation |
| `:undo` / `:redo` | History navigation |

## Key Constraint: Enter-Edit from Idle

**Critical rule**: You cannot enter edit mode directly from idle state.

```
idle → enter-edit ❌  (blocked)
idle → selection → enter-edit ✅  (correct flow)
```

This matches Logseq's behavior where you must first select a block (click) before you can edit it (Enter or double-click).

## Idle State Guards

In idle state, most intents are no-ops. This prevents accidental modifications:

- `:enter-edit` - No block to edit
- `:context-aware-enter` - No block to split
- `:delete` - No block to delete
- `:indent` / `:outdent` - No block to move
- `:merge-with-prev` / `:merge-with-next` - No block to merge
- `:insert-newline` - No block for newline
- `:exit-edit` - Not editing

## API Reference

```clojure
(require '[kernel.state-machine :as sm])

;; Query current state
(sm/current-state session)          ; => :idle | :selection | :editing
(sm/in-editing-state? session)      ; => boolean
(sm/in-selection-state? session)    ; => boolean
(sm/in-idle-state? session)         ; => boolean

;; Validate intents
(sm/intent-allowed? session intent) ; => boolean
(sm/allowed-intents session)        ; => #{:selection :undo ...}

;; Debugging
(sm/describe-state session)         ; => {:state :editing :description "..." :details {...}}
(sm/print-state session)            ; Prints to console
```

## E2E Test Integration

Tests can debug intent blocking via `TEST_HELPERS`:

```javascript
const debug = await page.evaluate((intent) => {
  return window.TEST_HELPERS.debugIntent(intent);
}, { type: 'enter-edit', 'block-id': 'abc' });

console.log(debug);
// {
//   allowed: false,
//   currentState: 'idle',
//   intentType: 'enter-edit',
//   requiredStates: ['selection'],
//   reason: 'Intent :enter-edit requires states #{:selection} but current state is :idle'
// }
```

See `docs/E2E_TESTING.md` for test helpers that manage state transitions.

## Implementation

Source: `src/kernel/state_machine.cljc`

The state machine is consulted by the intent handler before dispatching operations. If an intent is blocked, it becomes a no-op with no side effects.
