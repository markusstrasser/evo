# Critical Spec Gap: Edit/View Mode Mutual Exclusivity

## TL;DR

**The specs never explicitly stated:**

> Edit mode (cursor in block) and block selection (blue backgrounds) are **mutually exclusive global states**.

This led to fundamental misunderstandings in implementation and testing.

---

## What the Specs Said

### LOGSEQ-PARITY-BLOCK-TEXT.md Line 58

```
Deselection | Escape (non-editing) + background click dispatch `:selection :clear`
```

### Section 4.6

```
Extend `handle-global-keydown` to dispatch `{:type :selection :mode :clear}`
on Escape **when not editing**
```

### Section 4.7 (Backspace Merge)

```
Keep cursor positioning at the end of the merged text.
```

## What They Implied (But Never Stated)

The specs used phrases like:
- "when not editing"
- "outside edit mode"
- "while editing"

But **never said**:

```
THERE ARE TWO MUTUALLY EXCLUSIVE GLOBAL STATES:

1. EDIT MODE
   - One block has contenteditable focused
   - Cursor visible inside that block
   - NO blocks can be selected
   - Block selection = empty set

2. VIEW MODE
   - NO block has contenteditable focused
   - NO cursor anywhere
   - Blocks CAN be selected (blue background)
   - May have selection, may not

You CANNOT be in both states simultaneously.
```

---

## Why This Matters

### Consequence 1: Confused Mental Model

I (and likely previous AI agents) assumed you could have:

```
- Edit mode block (cursor visible)
- Selected blocks (blue background)
- Both at the same time
```

**This is impossible in Logseq.**

### Consequence 2: Wrong Visual Notation

Initial DSL had:

```
-E AAA|              ← Editing with cursor
  -V BBB             ← View mode block
  -*CCC              ← Selected block
```

This notation shows edit and selection **in the same tree**, which cannot happen.

### Consequence 3: Test Assumptions

Tests might verify:
```clojure
(is (= "a" (get-editing-block db)))      ; ✓ In edit mode
(is (= #{"b" "c"} (get-selection db)))   ; ✗ IMPOSSIBLE - can't have selection while editing
```

### Consequence 4: Missing State Transition Logic

The code needs:

```clojure
;; When entering edit mode
(defn enter-edit-mode [db block-id]
  (-> db
      (clear-selection!)           ; ← CRITICAL: Clear selection first
      (set-editing-block block-id)))

;; When entering selection mode
(defn select-block [db block-id]
  (if (editing? db)
    (-> db
        (exit-edit-mode!)          ; ← CRITICAL: Exit edit first
        (add-to-selection block-id))
    (add-to-selection db block-id)))
```

If these transitions are wrong, you get:
- Selection "sticks" after clicking a block
- Cursor disappears when it shouldn't
- Blue background on edited block (visual bug)

---

## How This Got Missed

### 1. Specs Focused on Features, Not State Model

Specs described behaviors:
- "Navigate with arrow keys"
- "Outdent blocks"
- "Clear selection on Escape"

But never described the **state machine**:

```
┌─────────────┐   Click Block    ┌─────────────┐
│  VIEW MODE  ├──────────────────>│  EDIT MODE  │
│             │<──────────────────┤             │
└──────┬──────┘   Escape          └─────────────┘
       │
       │ Shift+Click
       ▼
┌─────────────┐
│  VIEW MODE  │
│ + Selection │
└─────────────┘
```

### 2. "When Not Editing" Is Subtle

Saying "clear selection when not editing" implies:
- Selection exists in some states
- Editing exists in some states
- But **doesn't say they're mutually exclusive**

More explicit phrasing:

```
Selection can ONLY exist in VIEW mode.
Editing can ONLY exist in EDIT mode.
These modes are mutually exclusive.
Transitioning between modes requires clearing the other state.
```

### 3. Logseq Source Wasn't Explicit Either

Looking at Logseq source (from archived specs), even their code doesn't have a comment saying:

```clojure
;; INVARIANT: editing-block-id and selection-blocks are mutually exclusive
;; If editing-block-id is set, selection-blocks must be empty
;; If selection-blocks is non-empty, editing-block-id must be nil
```

It's just **how the code works** but not **documented as a constraint**.

### 4. AI Agents Don't Experience the UI

When AI reads:
```
"Navigate with arrow keys"
"Select blocks with Shift+Click"
```

We don't inherently know:
- Arrow keys work in EDIT mode (move cursor)
- Shift+Click works in VIEW mode (extend selection)
- These are **different interaction modes**

A human using Logseq immediately feels:
- "I'm typing → I'm IN a block"
- "I'm selecting → I'm LOOKING AT blocks"

But reading specs, it's not obvious these are mutually exclusive.

---

## How to Fix the Specs

### Add "State Model" Section

```markdown
## Core State Model

The application has **two mutually exclusive global states**:

### Edit Mode
- ONE block's contenteditable is focused
- Cursor is visible inside that block
- User can type, move cursor with arrows, etc.
- Block selection is IMPOSSIBLE (selection = ∅)
- Indicated by: `editing-block-id` is set in DB

### View Mode
- NO block has contenteditable focused
- NO cursor visible
- User can select blocks (Shift+Click, Shift+Arrow)
- May have 0, 1, or many blocks selected
- Indicated by: `editing-block-id` is nil in DB

### State Transitions

**VIEW → EDIT:**
- Trigger: Click a block, press Enter on selected block
- Action: Clear selection, set editing-block-id, focus contenteditable

**EDIT → VIEW:**
- Trigger: Press Escape, click away, blur contenteditable
- Action: Clear editing-block-id, unfocus contenteditable

**VIEW → VIEW (with selection):**
- Trigger: Shift+Click, Shift+Arrow while in view mode
- Action: Add/remove blocks from selection set

**EDIT → VIEW (with selection):**
- IMPOSSIBLE: Must exit edit mode first
```

### Add Invariant Checks

```clojure
(defn verify-state-invariants [db]
  (let [editing? (some? (get-editing-block db))
        selected? (seq (get-selection db))]
    (when (and editing? selected?)
      (throw (ex-info "INVARIANT VIOLATION: Cannot have editing-block-id and selection simultaneously"
                      {:editing-block-id (get-editing-block db)
                       :selection (get-selection db)})))))
```

### Update Test Naming

```clojure
;; BEFORE (ambiguous)
(deftest navigate-with-arrows ...)

;; AFTER (explicit about mode)
(deftest edit-mode-navigate-with-arrows-moves-cursor ...)
(deftest view-mode-shift-arrow-extends-selection ...)
```

---

## What Needs Auditing Now

### 1. DB Schema

Does the DB enforce this?

```clojure
;; Check these are mutually exclusive
(get-in db [:session/ui :editing-block-id])    ; Should be XOR
(get-in db [:session/selection :nodes])         ; with this
```

### 2. State Transition Code

Search for:
- `(exit-edit-mode` → Does it clear selection? (shouldn't need to)
- `(enter-edit-mode` → Does it clear selection? (MUST)
- `(select-block` → Does it check if editing? (SHOULD)

### 3. Tests

Search for tests that assume both:
```clojure
;; INVALID test assumption
(let [db {:editing-block-id "a"
          :selection {:nodes #{"b" "c"}}}]  ; ← IMPOSSIBLE STATE
  ...)
```

### 4. UI Components

Does `Block` component render correctly based on:
- Edit mode: Show contenteditable
- View mode + selected: Show blue background
- View mode + not selected: Show plain span

Can it accidentally show **both** contenteditable AND blue background?

---

## Lessons for Future Specs

### Do This:
1. **Start with state model** before describing behaviors
2. **Draw state transition diagram** showing mutually exclusive states
3. **List invariants explicitly** as assertions
4. **Use "CANNOT" language** for impossible states

### Don't Do This:
1. ~~Assume context from "when not editing" is obvious~~
2. ~~Focus only on features without state constraints~~
3. ~~Rely on "well everyone knows" assumptions~~

### Example: Good Spec Template

```markdown
## State Model

[Diagram showing states and transitions]

### Invariants
1. editing-block-id XOR selection-blocks (mutually exclusive)
2. editing-block-id must exist in nodes map if non-nil
3. selection-blocks must be subset of nodes map

### State: Edit Mode
- Definition: editing-block-id is non-nil
- Constraints: selection-blocks = ∅
- Transitions:
  - → View: [triggers] clear editing-block-id
  - → View+Selection: IMPOSSIBLE (must go through View first)

### State: View Mode
- Definition: editing-block-id is nil
- Constraints: none (selection-blocks can be empty or non-empty)
- Transitions:
  - → Edit: [triggers] set editing-block-id, clear selection
  - → View+Selection: [triggers] add to selection-blocks

[Then describe features and behaviors]
```

---

## Summary

**The fundamental issue:**

Specs described **what Logseq does** but not **how Logseq's state machine works**.

By not explicitly stating "edit mode and block selection are mutually exclusive", it left room for:
- Misunderstandings
- Wrong mental models
- Test assumptions about impossible states
- Missing state transition logic

**The fix:**

Document the state model FIRST, then describe behaviors within that model.

Make impossible states explicit: "You CANNOT have X and Y at the same time."

Use visual diagrams showing state transitions.

**The impact:**

Without this, AI agents (and potentially human developers) will continue to:
- Write tests for impossible states
- Miss edge cases in state transitions
- Create UI bugs where both states appear to exist
- Not understand why certain bugs happen

This is why 259 tests pass but cursor/selection bugs exist.

The tests verify operations in isolation but don't verify the **state machine invariants**.
