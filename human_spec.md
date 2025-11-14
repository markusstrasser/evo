# Logseq Parity — Human Feature Spec (Blocks · Navigation · Text · Selection)

This document spells out the exact end-user experience Evo must provide to match Logseq on macOS. Every action below includes:

- **Mode line** showing whether we’re editing (`Mode: Edit`) or browsing (`Mode: View`).
- **Ordered block list** with state markers:
  - `[->]` editing block (caret active)
  - `[*]` selected block (blue background)
  - `[ ]` neutral block
  - `[F]` folded block (children hidden)
  - `[Z]` zoom root (only this subtree is rendered)
- Inline markers:
- `|` caret position
- `«…»` highlighted text selection
- `⏎` newline within a block’s text
- The `|` caret only appears in `Mode: Edit`. In view/select mode we show focus with `[*]` but never a caret.
- **Action** line with hotkey (mac-style). Alt = Option, Ctrl = Control.
- **After** state reflecting exactly what the user sees.
- Optional **Notes** for state changes (cursor memory, session flags, etc.).

If a scenario involves multiple blocks, indentation shows hierarchy; numbering always reflects visual order.

---

## 1. Navigation (Arrow Keys & Cursor Memory)

### 1.1 Arrow Down While Editing (multi-line target, column preserved)
```
Before
Mode: Edit
1. [->] A  "Hello |world"
2. [ ]  B  "First line⏎Second line"

Action: ↓

After
Mode: Edit
1. [ ]  A  "Hello world"
2. [->] B  "First line⏎Seco|nd line"
Notes: Column 6 retained via cursor memory.
```

### 1.2 Arrow Up With Shorter Block (falls back to end)
```
Before
Mode: Edit
1. [ ]  A  "Hi"
2. [->] B  "foo| bar"

Action: ↑

After
Mode: Edit
1. [->] A  "Hi|"
2. [ ]  B  "foo bar"
Notes: Target shorter → caret snaps to end (same as Logseq).
```

### 1.3 Arrow Up at Document Start (no exception)
```
Before
Mode: Edit
1. [->] A  "|Heading"

Action: ↑

After
Mode: Edit
1. [->] A  "|Heading"
Notes: No-op, no exceptions.
```

### 1.4 Arrow Up/Down While Browsing (focus shifts)
```
Before
Mode: View
1. [*] A  "Task"
2. [ ]  B  "Details"
3. [ ]  C  "Done"

Action: ↓

After
Mode: View
1. [ ]  A  "Task"
2. [*] B  "Details"
3. [ ]  C  "Done"
```

### 1.5 Arrow Left at Text Start (jump to previous block end)
```
Before
Mode: Edit
1. [->] A  "Alpha"
2. [->] B  "|Bravo"

Action: ←

After
Mode: Edit
1. [->] A  "Alpha|"
2. [ ]  B  "Bravo"
```

### 1.6 Arrow Right at Text End (jump to next block start)
```
Before
Mode: Edit
1. [->] A  "Fo|o"
2. [ ]  B  "Bar"

Action: →

After
Mode: Edit
1. [ ]  A  "Foo"
2. [->] B  "|Bar"
```

### 1.7 Arrow Down Skips Folded Descendants
```
Before
Mode: Edit
1. [F] Parent  "Node"
    ├─ Child1  "Alpha"
    └─ Child2  "Beta"
2. [->] Sibling "|Gamma"

Action: ↓

After
Mode: Edit
1. [F] Parent  "Node"
    ├─ Child1  "Alpha"
    └─ Child2  "Beta"
2. [->] Sibling "|Gamma"
Notes: Folded subtree ignored.
```

### 1.8 Arrow Up/Down Respect Zoom Root
```
Before
Mode: Edit (Zoom root #Topic)
1. [Z] Topic
    ├─ [->] Item1  "|Text"
    └─ [ ]  Item2  "Other"

Action: ↑

After
Mode: Edit (Zoom root #Topic)
1. [Z] Topic
    ├─ [->] Item1  "|Text"
    └─ [ ]  Item2  "Other"
Notes: Cannot navigate outside zoom root.
```

---

## 2. Selection (Mouse & Keyboard)

### 2.1 Mouse Click
```
Before
Mode: View
1. [*] A  "Today"
2. [ ]  B  "Tomorrow"

Action: Click block B

After
Mode: View
1. [ ]  A  "Today"
2. [*] B  "Tomorrow"
```

### 2.2 Shift+Click Extends Range
```
Before
Mode: View
1. [*] A  "Alpha"
2. [ ]  B  "Beta"
3. [ ]  C  "Gamma"

Action: Shift+Click on C

After
Mode: View
1. [*] A  "Alpha"
2. [*] B  "Beta"
3. [*] C  "Gamma"
```

### 2.3 Shift+Arrow Up at Row Boundary (block selection)
```
Before
Mode: Edit
1. [ ]  Intro  "Overview"
2. [->] Body   "Top row⏎Second| row"
3. [ ]  Outro  "Summary"

Action: Shift+↑

After
Mode: View
1. [*] Intro  "Overview"
2. [*] Body   "Top row⏎Second row"
3. [ ]  Outro  "Summary"
```

### 2.4 Shift+Arrow Down Mid-Line (text selection only)
```
Before
Mode: Edit
1. [->] A  "Hel«lo» world"
2. [ ]  B  "Next"

Action: Shift+↓

After
Mode: Edit
1. [->] A  "Hel«lo world»"
2. [ ]  B  "Next"
Notes: Browser extends text selection; no block selection.
```

Implementation note: Ensure global shortcut handlers do not capture `Shift+Arrow` while editing unless the caret is on the first/last row. Logseq keeps the event inside the editor so the browser can extend the in-block selection; only boundary cases should dispatch block-range selection intents.

### 2.5 Cmd+A While Editing (select parent block)
```
Before
Mode: Edit
1. [*] Parent  "List"
    ├─ [->] Child  "|Item"
    └─ [ ]  Child2 "Item 2"
2. [ ]  Sibling "Else"

Action: Cmd+A

After
Mode: View
1. [*] Parent  "List"
    ├─ [ ]  Child  "Item"
    └─ [ ]  Child2 "Item 2"
2. [ ]  Sibling "Else"
```

### 2.6 Cmd+Shift+A (Select All in View / Zoom)
```
Before
Mode: View (Zoom root #Project)
1. [Z] Project
    ├─ [*] Task 1
    ├─ [ ]  Task 2
    └─ [ ]  Task 3

Action: Cmd+Shift+A

After
Mode: View (Zoom root #Project)
1. [Z] Project
    ├─ [*] Task 1
    ├─ [*] Task 2
    └─ [*] Task 3
```

### 2.7 Escape (while editing) — leave edit, keep selection
```
Before
Mode: Edit
1. [->] A  "Hello|"

Action: Esc

After
Mode: View
1. [*] A  "Hello"
```

### 2.8 Escape (not editing) — clear selection
```
Before
Mode: View
1. [*] A  "Task 1"
2. [*] B  "Task 2"

Action: Esc

After
Mode: View
1. [ ]  A  "Task 1"
2. [ ]  B  "Task 2"
```

### 2.9 Background Click — clear selection
```
Before
Mode: View
1. [*] A  "Task 1"
2. [*] B  "Task 2"

Action: Click empty canvas

After
Mode: View
1. [ ]  A  "Task 1"
2. [ ]  B  "Task 2"
```

---

## 3. Structural Editing (Indent, Outdent, Move, Delete)

### 3.1 Indent Selected Blocks (Tab)
```
Before
Mode: View
1. [*] A  "Parent"
2. [*] B  "Child"
3. [ ]  C  "Later"

Action: Tab

After
Mode: View
1. [*] A  "Parent"
    └─ [*] B  "Child"
2. [ ]  C  "Later"
```

### 3.2 Outdent (Shift+Tab, logical mode)
```
Before
Mode: View
1. [ ]  Parent
    ├─ [*] A  "First"
    ├─ [*] B  "Second"
    └─ [*] C  "Third"
2. [ ]  Neighbor "Outside"

Action: Shift+Tab

After
Mode: View
1. [ ]  Parent
    └─ [*] A  "First"
2. [*] B  "Second"
    └─ [*] C  "Third"
3. [ ]  Neighbor "Outside"
Notes: Logical outdent → selection moves to bottom of grandparent; right siblings remain.
```

### 3.3 Move Selected Up (Cmd+Shift+↑)
```
Before
Mode: View
1. [ ]  A
2. [*] B
3. [ ]  C

Action: Cmd+Shift+↑

After
Mode: View
1. [*] B
2. [ ]  A
3. [ ]  C
```

### 3.4 Move Selected Down (Cmd+Shift+↓)
```
Before
Mode: View
1. [ ]  A
2. [*] B
3. [ ]  C

Action: Cmd+Shift+↓

After
Mode: View
1. [ ]  A
2. [ ]  C
3. [*] B
```

### 3.5 Delete Selected (Backspace in View mode)
```
Before
Mode: View
1. [*] A  "Keep"
2. [*] B  "Remove"
3. [ ]  C  "Remain"

Action: Backspace

After
Mode: View
1. [*] A  "Keep"
2. [ ]  C  "Remain"
Notes: Deleted blocks moved to trash; focus stays on previous sibling.
```

### 3.6 Create Block & Enter Edit (Enter in View mode)
```
Before
Mode: View
1. [*] A  "Current"
2. [ ]  B  "Next"

Action: Enter

After
Mode: Edit
1. [ ]  A  "Current"
2. [->] New  "|"
3. [ ]  B  "Next"
```

### 3.7 Delete Block Children (Cmd+Backspace while editing)
```
Before
Mode: Edit
1. [ ]  A  "Parent"
    └─ child "Keep"
2. [->] B  "|Child"
    └─ child "Move"

Action: Cmd+Backspace

After
Mode: Edit
1. [->] A  "ParentChild|"
    ├─ child "Keep"
    └─ child "Move"
```

---

## 4. Text Editing & Smart Enter

### 4.1 Plain Split (Enter)
```
Before
Mode: Edit
1. [->] A  "Hello |world"

Action: Enter

After
Mode: Edit
1. [->] A   "Hello"
2. [ ]  B   "|world"
```

### 4.2 Checkbox Continuation
```
Before
Mode: Edit
1. [->] A  "- [ ] Todo |item"

Action: Enter

After
Mode: Edit
1. [ ]  A  "- [ ] Todo item"
2. [->] B  "- [ ] |"
```

### 4.3 Checkbox Unformat (empty)
```
Before
Mode: Edit
1. [->] A  "- [ ] |"

Action: Enter

After
Mode: Edit
1. [->] A  "|"
```

### 4.4 Numbered List Increment
```
Before
Mode: Edit
1. [->] A  "1. Item|"

Action: Enter

After
Mode: Edit
1. [ ]  A  "1. Item"
2. [->] B  "2. |"
```

### 4.5 Code Fence Newline
```
Before
Mode: Edit
1. [->] A  "```clojure⏎(inc |x)⏎```"

Action: Enter

After
Mode: Edit
1. [->] A  "```clojure⏎(inc |⏎x)⏎```"
```

### 4.6 Plain Text Backspace + Merge (children preserved)
*(see §3.7 for full example)*

### 4.7 Delete at End (merge with next)
```
Before
Mode: Edit
1. [->] A  "Foo|"
    └─ child "Keep"
2. [ ]  B  "Bar"
    └─ child "Move"

Action: Delete

After
Mode: Edit
1. [->] A  "FooBar|"
    ├─ child "Keep"
    └─ child "Move"
```

### 4.8 Formatting Toggles
```
Cmd+B   : bold (**…**)
Cmd+I   : italic (*…*)
Cmd+Shift+H : highlight (^^…^^)
Cmd+Shift+S : strikethrough (~~…~~)
```
Example:
```
Before  ->A "Make «this» bold"
Action  Cmd+B
After   ->A "Make **«this»** bold"
```

### 4.9 Emacs-style Word Navigation (Control+Shift+F / Control+Shift+B)
```
Before
Mode: Edit
1. [->] A  "Jump |over words"
Action: Ctrl+Shift+F
After
Mode: Edit
1. [->] A  "Jump over| words"
```
```
Before
Mode: Edit
1. [->] A  "Jump over |words"
Action: Ctrl+Shift+B
After
Mode: Edit
1. [->] A  "Jump |over words"
```

### 4.10 Kill Commands
```
Cmd+L : clear block content
Cmd+U : kill to beginning of line
Cmd+K : kill to end of line
Cmd+Delete : kill word forward
Option+Delete : kill word backward
```
Example (Cmd+U):
```
Before  ->A "Hello |world"
Action  Cmd+U
After   ->A "|world"   ("Hello " removed)
```
Example (Option+Delete):
```
Before  ->A "Remove last |word"
Action  Option+Delete
After   ->A "Remove |"
```

### 4.11 Toggle Checkbox (Cmd+Enter)
```
Before
Mode: Edit
1. [->] Task  "- [ ] |Do laundry"

Action: Cmd+Enter

After
Mode: Edit
1. [->] Task  "- [x] |Do laundry"
```

---

## 5. Folding & Zoom

### 5.1 Toggle Fold (Cmd+;)
```
Before
Mode: View
1. [ ]  Parent
    ├─ [ ]  Child1
    └─ [ ]  Child2

Action: Cmd+;

After
Mode: View
1. [F] Parent
    └─ … (children hidden)
```

### 5.2 Collapse Children (Cmd+↑)
```
Before
Mode: View
1. [ ]  Parent
    ├─ [ ]  Child1
    │     └─ Grandchild
    └─ [ ]  Child2

Action: Cmd+↑

After
Mode: View
1. [F] Parent
    ├─ Child1 (hidden)
    └─ Child2 (hidden)
```

### 5.3 Expand All (Cmd+↓)
```
Before
Mode: View
1. [F] Parent
    ├─ Child1 (hidden)
    └─ Child2 (hidden)

Action: Cmd+↓

After
Mode: View
1. [ ] Parent
    ├─ [ ] Child1
    └─ [ ] Child2
```

### 5.4 Zoom In / Zoom Out (Cmd+. / Cmd+,)
```
Before
Mode: View
1. [*] Topic
    ├─ A
    └─ B

Action: Cmd+.

After
Mode: View (Zoom root #Topic)
1. [Z] Topic
    ├─ [ ] A
    └─ [ ] B
```
```
Action: Cmd+,
After: Return to parent context, original tree restored.
```

---

## 6. Link & Reference Actions

### 6.1 Follow Page Link (Cmd+O)
```
Before
Mode: Edit
1. [->] Block  "Visit [[Project Plan]] soon"
           caret inside [[Project Plan]]

Action: Cmd+O

After
Mode: View
1. [ ]  Block  "Visit [[Project Plan]] soon"
Session: {:current-page "Project Plan"}
```

### 6.2 Follow Block Reference (Cmd+O)
```
Before
Mode: Edit
1. [->] Block  "See ((uuid-1234))"
2. [ ]  Target "Referenced content"

Action: Cmd+O

After
Mode: View
1. [ ]  Block  "See ((uuid-1234))"
2. [ ]  Target "Referenced content"
Session: {:scroll-to-block "uuid-1234" :highlight-block "uuid-1234"}
```

(When the sidebar feature is available, `Cmd+Shift+O` opens the reference in the sidebar instead.)

---

## 7. Undo / Redo

Undo and redo are handled globally (`Cmd+Z`, `Cmd+Shift+Z`). They operate on the op stream produced by the scenarios above, preserving the same granularity as Logseq. No special notation is required; every example is undoable as-is.

---

## 8. Compliance Checklist

- [ ] Arrow navigation honors cursor memory, folded blocks, zoom roots.
- [ ] Selection behaves identically for click, Shift+Click, Shift+Arrow, Cmd+A variants, Escape/background clearing.
- [ ] Structural actions (indent/outdent, move up/down, delete, create) match diagrams.
- [ ] Backspace/Delete merge and child migration logic aligns with specs.
- [ ] Text editing (Enter variants, formatting, Emacs shortcuts, kill commands, checkbox toggle) produces stated transformations.
- [ ] Folding and zoom hotkeys have the exact visual outcomes shown.
- [ ] Link following (`Cmd+O`) updates session state and focus exactly as described.

Once every checkbox is satisfied and covered by automated tests, Evo should be indistinguishable from Logseq for block/text/navigation workflows.
