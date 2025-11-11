# Visual DSL - Communication Tool Only

**Purpose**: Human-readable notation for discussing tree state, cursor, and selection.
**Not for code**: Keep actual tests data-based. This is just for communication.

---

## The Two Mutually Exclusive Global States

### 🎯 CRITICAL CONSTRAINT

**Edit mode and block selection CANNOT coexist.**

```
If editing block → No blocks selected
If blocks selected → Not editing any block
```

This is fundamental to Logseq's UX but was never explicitly stated in specs.

---

## Edit Mode

**One block is being edited with cursor inside. No block selection possible.**

### Notation

```
:E                           ← Global state indicator
  - AAA
    - BBB
      - He|llo               ← Cursor at position 2 in "Hello"
        - World
  - CCC
```

### Legend for Edit Mode
- `:E` = Edit mode (global state)
- `|` = Cursor position
  - `Hello|` = cursor after "Hello" (pos 5)
  - `He|llo` = cursor between "He" and "llo" (pos 2)
  - `|Hello` = cursor at start (pos 0)
- Indentation = tree nesting
- The block with `|` is the one being edited (contenteditable focused)

### Example: Cursor at Various Positions

```
:E
  - AAA
    - |BBB                   ← Cursor at start of "BBB"
  - CCC
    - DD|D                   ← Cursor in middle of "DDD"
  - EEE|                     ← Cursor at end of "EEE"
```

---

## View Mode (No Selection)

**Not editing, no blocks selected. Just viewing.**

### Notation

```
:V                           ← View mode, nothing selected
  - AAA
    - BBB
      - CCC
  - DDD
```

### Legend
- `:V` = View mode (global state)
- No special markers = just viewing
- No cursor anywhere
- No blocks selected

---

## View Mode (With Block Selection)

**Not editing. Multiple blocks selected with blue background.**

### Notation

```
:V                           ← View mode with selection
  - AAA
    -*BBB ^                  ← Selected (anchor block)
    -*CCC ~                  ← Selected (focus block)
  - DDD
```

### Legend for Selection
- `*` prefix = block is selected (blue background)
- `^` = anchor block (where selection started)
- `~` = focus block (where selection ended)
- Anchor/focus matters for Shift+Arrow expansion

### Example: Multi-Block Selection

```
:V
  - AAA
    -*BBB ^                  ← Started selection here (Shift+Click)
    -*CCC                    ← Also selected
    -*DDD                    ← Also selected
    -*EEE ~                  ← Ended selection here (Shift+Down)
  - FFF
```

### Example: Single Block Selected

```
:V
  - AAA
    -*BBB ^~                 ← Anchor and focus on same block
  - CCC
```

---

## State Transitions

### View → Edit

**Action**: Click a block

```
BEFORE:                      AFTER:
:V                           :E
  - AAA                        - AAA
    - BBB                        - |BBB          ← Now editing
  - CCC                        - CCC
```

### Edit → View (Clear)

**Action**: Press Escape

```
BEFORE:                      AFTER:
:E                           :V
  - AAA                        - AAA
    - BBB|                       - BBB          ← No longer editing
  - CCC                        - CCC
```

### View with Selection → Edit

**Action**: Click a selected block

```
BEFORE:                      AFTER:
:V                           :E
  - AAA                        - AAA
    -*BBB ^~                     - |BBB         ← Selection cleared, now editing
  - CCC                        - CCC
```

Selection is **always cleared** when entering edit mode.

### View with Selection → View Clear

**Action**: Press Escape or click background

```
BEFORE:                      AFTER:
:V                           :V
  - AAA                        - AAA
    -*BBB ^                      - BBB          ← Selection cleared
    -*CCC ~                      - CCC
  - DDD                        - DDD
```

---

## Markdown Formatting for Readability

### Using Code Blocks with Syntax Highlighting

```clojure
;; Before operation
:E
  - AAA
    - Hello|                 ; Editing this
  - BBB

;; After indent
:E
  - AAA
    - BBB
      - Hello|               ; Still editing, now nested deeper
```

### Using Collapsible Sections

<details>
<summary>Complex multi-level tree example</summary>

```
:V
  - Root
    -*Level1-A ^
      - Level2-A
      - Level2-B
    -*Level1-B
    -*Level1-C ~
  - Another Root
```

</details>

### Using Tables for Before/After

| Before | After | Action |
|--------|-------|--------|
| `:E`<br>`- AAA`<br>`- BBB\|` | `:E`<br>`- AAA`<br>`  - BBB\|` | Indent BBB (Tab) |
| `:V`<br>`- AAA`<br>`-*BBB ^~` | `:E`<br>`- AAA`<br>`- \|BBB` | Click BBB (enter edit) |

---

## Common Scenarios

### Scenario 1: Editing and Navigation

```
GIVEN:
:E
  - AAA
    - Hello|                 ← Cursor at end
  - BBB

WHEN: Press Down Arrow

THEN:
:E
  - AAA
    - Hello
  - |BBB                     ← Cursor moved to start of BBB
```

### Scenario 2: Selection Expansion

```
GIVEN:
:V
  - AAA
    -*BBB ^~                 ← One block selected
  - CCC

WHEN: Press Shift+Down

THEN:
:V
  - AAA
    -*BBB ^                  ← Still anchor
  - *CCC ~                   ← Focus moved, selection expanded
```

### Scenario 3: Outdent with Cursor

```
GIVEN:
:E
  - AAA
    - BBB
      - He|llo               ← Editing deeply nested block
    - CCC

WHEN: Press Shift+Tab (outdent)

THEN:
:E
  - AAA
    - BBB
    - CCC
  - He|llo                   ← Moved out, still editing, cursor preserved
```

---

## What This Notation Helps With

### 1. Bug Reports

**Clear communication:**
```
BUG: Cursor jumps to start after outdent

EXPECTED:
:E
  - AAA
    - He|llo → Shift+Tab → :E
                              - AAA
                              - He|llo    ← Cursor stays at pos 2

ACTUAL:
:E
  - AAA
    - He|llo → Shift+Tab → :E
                              - AAA
                              - |Hello    ← Cursor jumped to start!
```

### 2. Spec Discussions

**Unambiguous requirements:**
```
REQUIREMENT: Logical outdenting preserves siblings

:V
  - AAA
    - BBB
    - *CCC ^~                ← Select and outdent this
    - DDD

AFTER:
:V
  - AAA
    - BBB
    - DDD                    ← Siblings stay under AAA
  - CCC                      ← CCC moves to top level (last position)
```

### 3. Test Case Design

**Visual test expectations:**
```clojure
(deftest outdent-preserves-cursor
  ;; GIVEN:
  ;;   :E
  ;;     - AAA
  ;;       - He|llo
  ;;
  ;; WHEN: outdent
  ;;
  ;; THEN:
  ;;   :E
  ;;     - AAA
  ;;     - He|llo     ← cursor stays at pos 2

  (let [db (setup-editing "He|llo" :under "AAA")]
    (is (= 2 (cursor-pos db)))
    (let [db' (outdent db)]
      (is (= 2 (cursor-pos db'))))))
```

---

## Why This Was Missed in Specs

### The Specs Said:

- "Escape (non-editing) + background click dispatch `:selection :clear`"
- "Extend `handle-global-keydown` to dispatch when **not editing**"

### What They Should Have Said:

> **EDIT MODE AND BLOCK SELECTION ARE MUTUALLY EXCLUSIVE GLOBAL STATES**
>
> - Edit mode: One block's contenteditable focused, cursor visible, NO block selection possible
> - View mode: No contenteditable focused, NO cursor, block selection possible
>
> These states cannot coexist. Entering edit mode always clears block selection. Entering block selection mode (Shift+Click) is only possible when not editing.

The specs used phrases like "when not editing" but **never explicitly stated this as a fundamental constraint of the system architecture**.

This led to:
- Tests that assumed both could exist simultaneously
- Visual DSL notation showing `:E` and `:V` blocks in same tree
- Misunderstanding of how cursor and selection interact
- Missed edge cases in state transitions

---

## Correct Mental Model

```
           ┌──────────────────┐
           │   APPLICATION    │
           └────────┬─────────┘
                    │
           ┌────────▼─────────┐
           │  GLOBAL STATE    │
           │  (Pick One)      │
           └────────┬─────────┘
                    │
        ┌───────────┴────────────┐
        │                        │
┌───────▼────────┐      ┌───────▼────────┐
│   EDIT MODE    │      │   VIEW MODE    │
│                │      │                │
│ • One block    │      │ • No editing   │
│ • Cursor shown │      │ • No cursor    │
│ • NO selection │      │ • Can select   │
└────────────────┘      └────────┬───────┘
                                 │
                        ┌────────┴────────┐
                        │                 │
                ┌───────▼──────┐  ┌──────▼──────┐
                │  No blocks   │  │   Blocks    │
                │  selected    │  │  selected   │
                └──────────────┘  └─────────────┘
```

**Key insight**: The tree has a single global "mode" property. Not per-block.
