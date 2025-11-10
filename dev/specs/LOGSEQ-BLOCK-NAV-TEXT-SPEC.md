# Logseq Block · Navigation · Text · Selection Feature Spec

**Scope**: Comprehensive behavioral specification for block-level navigation, selection, and text editing features required for Evo to feel indistinguishable from Logseq on macOS. This document is prescriptive—every scenario below is mandatory unless explicitly marked optional.

---

## 1. Notation & DSL

All scenarios use a lightweight ASCII DSL to describe document state.

```
Doc                     ; implicit root (not focusable)
├─ -A|Hello world|       ; normal block (no selection)
├─ -*B|Task|             ; block selected (blue background)
├─ ->C|Text^ here|       ; block in edit mode, caret shown by ^
├─ ->D|Start «text» end| ; text selection wrapped in « »
├─ -F[folded]|...|       ; folded block (children hidden)
└─ -#G|Title|            ; block whose subtree is zoom root (only shown tree)
```

Legend:

| Prefix | Meaning |
|--------|---------|
| `-`    | Default block (not selected, not editing) |
| `-*`   | Block included in selection set |
| `->`   | Block currently being edited |
| `-F`   | Folded block (children hidden in UI) |
| `-#`   | Block is current zoom root |

Inline markers:

- `^` marks caret position within text.
- `«…»` highlights an active text selection range.
- `⏎` denotes a newline within block text.
- `[…]` inside a block name indicates annotations (e.g., `[folded]`).
- Children appear underneath parents; ordering matters.

Each scenario is presented as **Before → Action → After**, with explicit context notes and expected DOM/intent side effects.

---

## 2. Global Rules

1. **Focus vs Selection vs Editing**
   - Exactly one block at a time has *focus* when not editing (latest selected block).
   - Selection (`-*`) can include multiple blocks; editing (`->`) clears selection except for the edited block.
   - Entering edit mode stores cursor column in `cursor-memory`. Exiting edit mode leaves selection unchanged.

2. **Cursor Memory**
   - Vertical navigation stores `{:block-id id :column n :direction dir}`.
   - Returning to a block reuses stored column as long as the user has not manually moved the caret with the mouse.

3. **Fold & Zoom Constraints**
   - Navigation never enters folded descendants or blocks outside the current zoom root (`-#`).
   - Actions requiring a target sibling/parent gracefully no-op when the target is unavailable.

4. **Undo Granularity**
   - Every scenario emits pure ops (`:create-node`, `:place`, `:update-node`) so undo/redo mirrors Logseq behavior.

---

## 3. Block Navigation Scenarios

### 3.1 Arrow Up/Down While Editing

#### 3.1.1 Multi-line Down Navigation (column preserved)
- Context: editing top block A at column 6; next block B has two lines.
- Action: `↓`

```tree
Before:
Doc
├─ ->A|Hello ^world|
└─ -B|First⏎Second line|

After:
Doc
├─ -A|Hello world|
└─ ->B|First⏎Seco^nd line|
```

Expectation:
- `cursor-memory` stores `{line-pos 6}` before switching.
- Caret lands on same visual column (within bottom line of B).
- `:navigate-with-cursor-memory` adds ops: exit edit on A, enter edit on B with `:cursor-position 11` (start of `Second line` + 6).

#### 3.1.2 Up Navigation with Shorter Block (falls back to end)
- Context: editing B, caret after `foo`. Previous block A text is "Hi".
- Action: `↑`

```tree
Before:
Doc
├─ -A|Hi|
└─ ->B|foo^ bar|

After:
Doc
├─ ->A|Hi^|
└─ -B|foo bar|
```

Expectation: target shorter → caret at end (symbolized by `^` after entire text). `cursor-memory` retains requested column for later returns.

#### 3.1.3 Boundary No-op (topmost block)
- Context: editing first block only.
- Action: `↑`

```tree
Before:
Doc
└─ ->A|^Heading|

After:
Doc
└─ ->A|^Heading|
```

Expectation: no exception; caret stays at column 0.

### 3.2 Arrow Up/Down While Not Editing

- Action: `↓`

```tree
Before:
Doc
├─ -*A|Task|
├─ -B|Details|
└─ -C|Done|

After:
Doc
├─ -A|Task|
├─ -*B|Details|
└─ -C|Done|
```

Expectation: focus moves, selection single-block. No cursor memory update.

### 3.3 Arrow Left/Right at Text Boundaries

#### 3.3.1 Left at Start Jumps to Previous Block End

```tree
Before:
Doc
├─ ->A|Alpha|
└─ ->B|^Bravo|

Action: `←`

After:
Doc
├─ ->A|Alpha^|
└─ -B|Bravo|
```

Expectation: `:navigate-to-adjacent` enters previous block with cursor at `:max`.

#### 3.3.2 Right at End Jumps to Next Block Start

```tree
Before:
Doc
├─ ->A|Fo^o|
└─ -B|Bar|

Action: `→`

After:
Doc
├─ -A|Foo|
└─ ->B|^Bar|
```

### 3.4 Fold & Zoom Respect

```tree
Before:
Doc
├─ -FParent|Node|
│   ├─ -Child1|X|
│   └─ -Child2|Y|
└─ ->Sibling|^Z|

Action: `↓`

After:
Doc
├─ -FParent|Node|
│   ├─ -Child1|X|
│   └─ -Child2|Y|
└─ ->Sibling|^Z|
```

Expectation: folded children skipped; caret remains on Sibling.

```tree
Zoom Root #Parent
├─ ->Child1|^A|
└─ -Child2|B|

Action: `↑`

After:
Zoom Root #Parent
├─ ->Child1|^A|
└─ -Child2|B|
```

Expectation: cannot leave zoom root; navigation no-ops when target outside subtree.

---

## 4. Selection Scenarios

### 4.1 Mouse Interaction

#### 4.1.1 Single Click

```tree
Before:
Doc
├─ -*A|Today|
└─ -B|Tomorrow|

Action: Click block B body

After:
Doc
├─ -A|Today|
└─ -*B|Tomorrow|
```

#### 4.1.2 Shift+Click Extends Range

```tree
Before:
Doc
├─ -*A|Alpha|
├─ -B|Beta|
└─ -C|Gamma|

Action: Shift+Click block C

After:
Doc
├─ -*A|Alpha|
├─ -*B|Beta|
└─ -*C|Gamma|
```

### 4.2 Shift+Arrow While Editing

#### 4.2.1 Extend Up at Row Boundary

```tree
Before:
Doc
├─ -A|Intro|
├─ ->B|First line⏎Second^ line|
└─ -C|Outro|

Action: `Shift+↑`

After:
Doc
├─ -*A|Intro|
├─ -*B|First line⏎Second line|
└─ -C|Outro|
```

Expectation: Because caret on first row, block selection extends upward and editing stops.

#### 4.2.2 Extend Down mid-text (stay in block)

```tree
Before:
Doc
├─ ->A|Hel«lo wo»rld|
└─ -B|Next|

Action: `Shift+↓`

After:
Doc
├─ ->A|Hel«lo wo»rld|
└─ -B|Next|
```

Expectation: browser handles text selection; no block selection occurs.

### 4.3 Keyboard Shortcuts

| Shortcut | Context | Result |
|----------|---------|--------|
| `Cmd+A` (editing) | Caret inside block | Exit edit, select parent block (if not root). |
| `Cmd+A` (selection mode) | No anchor | Select all blocks in zoomed subtree using `:selection :mode :all-in-view`. |
| `Cmd+Shift+A` | Any selection state | Select all blocks sibling range plus children (`:all-in-view`). |
| `Esc` (not editing) | Any selection | Clear selection (`selection :clear`). |

Example for `Cmd+A` while editing child:

```tree
Before:
Doc
├─ -*Parent|List|
│   ├─ ->Child|^Item|
│   └─ -Child2|Item 2|
└─ -Sibling|Else|

Action: `Cmd+A`

After:
Doc
├─ -*Parent|List|
│   ├─ -Child|Item|
│   └─ -Child2|Item 2|
└─ -Sibling|Else|
```

---

## 5. Text Editing Scenarios

### 5.1 Enter Behavior

#### 5.1.1 Plain Text Split

```tree
Before:
Doc
└─ ->A|Hello ^world|

Action: `Enter`

After:
Doc
├─ ->A|Hello|
└─ -B|^world|
```

Ops: `:update-node` (A text before cursor), `:create-node` (new B with after text), `:place` after A, cursor enters new block.

#### 5.1.2 Checkbox Continuation

```tree
Before:
Doc
└─ ->A| - [ ] Todo ^item|

Action: `Enter`

After:
Doc
├─ -A| - [ ] Todo item|
└─ ->B| - [ ] ^|
```

#### 5.1.3 Empty Checkbox Unformat

```tree
Before:
Doc
└─ ->A| - [ ] ^|

Action: `Enter`

After:
Doc
└─ ->A|^|
```

#### 5.1.4 Numbered List Increment

```tree
Before:
Doc
└─ ->A|1. Item^|

Action: `Enter`

After:
Doc
├─ -A|1. Item|
└─ ->B|2. ^|
```

#### 5.1.5 Code Fence Newline

```tree
Before:
Doc
└─ ->A|```clojure⏎(inc ^x)⏎```|

Action: `Enter`

After:
Doc
└─ ->A|```clojure⏎(inc ^⏎x)⏎```|
```

Expectation: newline inserted, caret stays inside fence; no new block.

### 5.2 Backspace & Delete

#### 5.2.1 Backspace at Start (merge, migrate children)

```tree
Before:
Doc
├─ -A|Parent|
│   └─ -A1|Keep|
└─ ->B|^Child|
    ├─ -B1|Move me|
    └─ -B2|Move me 2|

Action: `Backspace`

After:
Doc
└─ ->A|ParentChild^|
    ├─ -A1|Keep|
    ├─ -B1|Move me|
    └─ -B2|Move me 2|
```

Ops: Update text of A, re-parent B’s children under A in existing order, place B in trash.

#### 5.2.2 Delete at End (merge with next)

```tree
Before:
Doc
├─ ->A|Foo^|
│   └─ -A1|Child|
└─ -B|Bar|
    └─ -B1|Child2|

Action: `Delete`

After:
Doc
└─ ->A|FooBar^|
    ├─ -A1|Child|
    └─ -B1|Child2|
```

### 5.3 Formatting Commands

| Shortcut | Behavior |
|----------|----------|
| `Cmd+B` | Toggle `**` around selection or word under caret. |
| `Cmd+I` | Toggle `*` around selection or word. |
| `Cmd+Shift+H` | Toggle highlight (`^^`). |
| `Cmd+Shift+S` | Toggle strikethrough (`~~`). |

Example highlight toggle maintaining selection:

```tree
Before:
Doc
└─ ->A|Hello «world»|

Action: `Cmd+Shift+H`

After:
Doc
└─ ->A|Hello ^^«world»^^|
```

### 5.4 Paired Characters

- Typing `[`, `(`, `{`, `"`, `**`, `~~`, `^^` auto-inserts closing partner; caret positioned between pair.
- Backspace immediately after opening with matching closing ahead deletes both characters.

---

## 6. Structural Operations

### 6.1 Indent (Tab)

```tree
Before:
Doc
├─ -*A|Parent|
├─ -*B|Child|
└─ -C|Later|

Action: `Tab`

After:
Doc
├─ -*A|Parent|
│   └─ -*B|Child|
└─ -C|Later|
```

Requirements:
- Only possible if there is a previous sibling (here A).
- Selection preserved under new parent; caret stays in block if editing.

### 6.2 Outdent (Shift+Tab) — Logical Mode

```tree
Before:
Doc
├─ -Parent|Top|
│   ├─ -*A|First|
│   ├─ -*B|Second|
│   └─ -*C|Third|
└─ -Sibling|Outside|

Action: `Shift+Tab`

After:
Doc
├─ -Parent|Top|
│   └─ -*A|First|
└─ -*B|Second|
    └─ -*C|Third|
    └─ -Sibling|Outside|
```

Explanation:
- `B` moves to grandparent (here root) at bottom because logical mode.
- Right siblings (`C`) remain under original parent; only blocks in selection move.

### 6.3 Delete Block (Cmd+Backspace)

- Moves block to trash, children preserved under trash entry.
- Selection moves to previous sibling, or parent if no sibling.

---

## 7. Link & Reference Operations

### 7.1 Follow Link Under Cursor (`Cmd+O`)

```tree
Before:
Doc
└─ ->A|See [[My Page]] for details|

Caret: inside `[[My Page]]`.
Action: `Cmd+O`

After:
- Session state sets `{:current-page "My Page"}`
- Editing stops; focus moves to page root.
```

### 7.2 Follow Block Reference

```tree
Before:
Doc
├─ -Source|((id-123))|
└─ -Target|Referenced block|

Caret: inside `((id-123))`.
Action: `Cmd+O`

After:
- `:ui` updates `{:scroll-to-block "id-123" :highlight-block "id-123"}`
- Focus remains on Source block.
```

(When sidebar implemented, `Cmd+Shift+O` opens target in sidebar instead of main pane.)

---

## 8. Deselection & Escape

| Action | Context | Result |
|--------|---------|--------|
| Click empty canvas | Any selection | Dispatch `:selection :clear`; all blocks revert to `-`. |
| `Esc` while editing | Edited block | Exit edit, caret removed, selection becomes single block. |
| `Esc` while not editing | Any selection | Clear selection entirely. |

Example background click:

```tree
Before:
Doc
├─ -*A|Task 1|
└─ -*B|Task 2|

Action: Click empty background area

After:
Doc
├─ -A|Task 1|
└─ -B|Task 2|
```

---

## 9. Summary Checklist

| Category | Requirement |
|----------|-------------|
| Navigation | Column memory, multi-line behavior, fold/zoom respect, no boundary exceptions. |
| Selection | Shift+Arrow logic, Cmd+A variants, Escape/background clearing. |
| Text Editing | Context-aware Enter (plain, list, checkbox, code), backspace/delete child migration, formatting toggles, paired characters. |
| Structural | Logical outdenting, indent constraints, delete semantics. |
| Links | `Cmd+O` follows page/block references, sidebar-ready `Cmd+Shift+O`. |

Every behavior described herein must be covered by automated tests (unit + browser) before parity can be considered complete.
