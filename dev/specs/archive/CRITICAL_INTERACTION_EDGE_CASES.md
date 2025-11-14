# Critical Interaction Edge Cases - Must Implement

**Date**: 2025-11-13
**Source**: Full analysis in `dev/logseq-interaction-edge-cases.md`

These are the **most critical** interaction behaviors that affect daily usage.

---

## Priority 1: Cursor Position After Operations

### ✅ Enter at Position 0 → Creates Block ABOVE
**Behavior**: When cursor is at position 0 and you press Enter, Logseq creates an empty block **above** the current block, not below.

```
Before: |Hello     (cursor at 0)
After:
        Hello|     (cursor at 0 in original block)
```

**Why critical**: Users expect this for inserting thoughts above current line.

**Evo status**: ✅ IMPLEMENTED (smart_editing.cljc:443-450)
- Checks `(zero? cursor-pos)` before split
- Creates empty block with `:at {:before block-id}`
- Cursor stays in original block at position 0

---

### ✅ Enter in Middle → Left-Trimmed Second Block
**Behavior**: When splitting a block, the second block has left whitespace trimmed.

```
Before: Hello |  World    (cursor after "Hello ")
After:  Hello
        World|            (trimmed "  World" → "World", cursor at 0)
```

**Implementation**: `string/triml` on second block text

**Evo status**: ✅ IMPLEMENTED (smart_editing.cljc:454)
- Applies `str/triml` to second block text
- Prevents unwanted leading spaces in new block

---

### ✅ Backspace at Start → Cursor at Join Point (UTF-8 Aware)
**Behavior**: When merging blocks, cursor positions at the join point using **UTF-16 code units** (browser standard).

```
Before: Hello
        |World   (cursor at 0 in "World")
After:  Hello|World   (cursor at UTF-16 position after "Hello")
```

**Critical detail**: Uses `(.-length prev-text)` for JavaScript UTF-16 code units (browser cursor positioning standard)

**Evo status**: ✅ IMPLEMENTED (editing.cljc:77-78)
- Uses `(.-length prev-text)` in ClojureScript
- Falls back to `(count prev-text)` in Clojure
- Correctly handles emoji/multi-byte characters via UTF-16 encoding

---

## Priority 2: Selection State Behaviors

### ✅ Tab/Shift+Tab with Multiple Blocks Selected
**Behavior**: Indents/outdents ALL selected blocks together, preserving relative structure.

**Evo status**: 🔍 CHECK

---

### ✅ Backspace with Text Selection → Cursor at Start
**Behavior**: When text is selected in edit mode and you press Backspace, cursor goes to `selected-start` position.

```
Before: He<<llo Wo>>rld   (selected "llo Wo")
After:  He|rld             (cursor at position 2)
```

**Evo status**: 🔍 CHECK

---

### ✅ Cmd+C with Block Selection → Clears Selection
**Behavior**: After copying blocks, selection is automatically cleared.

**Evo status**: 🔍 CHECK

---

## Priority 3: Arrow Key Navigation

### ✅ Column Position Maintained Across Lines
**Behavior**: Up/Down arrows maintain **horizontal column position** (grapheme-aware) even when target line is shorter.

**Implementation**: Uses `get-line-pos` which counts graphemes from last `\n` to cursor.

```
Before: This is a long line with many characters|
        Short|

After Up: This is a long line with many characters|
          Short

(cursor tries to land at same column even if line is shorter)
```

**Evo status**: 📝 IMPLEMENTED (`navigation.cljc:get-line-pos`)

---

### ✅ Shift+Arrow Extends Selection in Direction
**Behavior**: Tracks selection direction (`:up` or `:down`) to properly extend/contract selection with Shift+Arrow.

**Evo status**: 🔍 CHECK - do we track direction?

---

## Priority 4: Special Key Combinations

### ✅ Shift+Enter → Inserts Newline Character
**Behavior**: Does NOT create new block. Inserts literal `\n` within the block text.

```
Before: Hello|World
Action: Shift+Enter
After:  Hello
        |World    (same block, contains literal newline)
```

**Evo status**: ✅ IMPLEMENTED (editing.cljc:64-77, bindings_data.cljc:18)
- New `:insert-newline` intent
- Inserts `\n` character at cursor position
- Cursor moves to position after newline
- Bound to Shift+Enter in editing mode

---

### ✅ Enter at Position 0 with Async Unsaved Chars
**Behavior**: If user types fast, unsaved characters are buffered. On Enter at position 0, these chars are **prepended** to the new block above.

**Implementation**:
```clojure
unsaved-chars @(:editor/async-unsaved-chars @state/state)
;; Prepend to new block's content
```

**Evo status**: 🔍 CHECK - do we have async char buffering?

---

### ✅ Escape Behavior: Save + Select (Optional)
**Behavior**: Escape saves current block and optionally selects it (based on `select?` param).

- Default: Just exit editing
- With `select?`: Exit and select current block

**Evo status**: 📝 SPEC'D (§3 Rule 6)

---

## Priority 5: Paired Character Deletion

### ✅ Backspace After Opening Bracket
**Behavior**: When cursor is right after `[`, `(`, `{`, or `"`, Backspace deletes **both** the opening and closing characters.

```
Before: [|]     (cursor after [)
Action: Backspace
After:  |       (both [ and ] deleted)
```

**Implementation**: Checks for paired delimiters

**Evo status**: ✅ ALREADY IMPLEMENTED (smart_editing.cljc:130-186)
- Intent `:delete-with-pair-check` handles paired deletion
- Supports single-char pairs: `[]`, `()`, `{}`, `""`
- Supports multi-char pairs: `**`, `__`, `^^`, `~~`, ``` `` ```
- Sorts pairs by length (multi-char first) to avoid conflicts

---

## Testing Priority Matrix

| Feature | User Impact | Implementation Complexity | Status |
|---------|-------------|--------------------------|--------|
| Enter at 0 → block above | High | Low | ✅ DONE |
| Backspace merge cursor position | High | Medium (UTF-16) | ✅ DONE |
| Column position memory | High | Low | ✅ DONE |
| Left-trim on split | Medium | Low | ✅ DONE |
| Paired deletion | Medium | Medium | ✅ DONE |
| Shift+Enter newline | Medium | Low | ✅ DONE |
| Async char buffering | Low | High | 🔍 CHECK |
| Selection direction tracking | Low | Medium | 🔍 CHECK |

---

## Verification Checklist

For each edge case, verify in Evo:

1. ✅ **Already works correctly**
2. 🔍 **Needs testing** - might work, not verified
3. ❌ **Missing** - needs implementation
4. 🐛 **Broken** - exists but buggy

Run through this checklist systematically to find gaps.

---

## Next Steps

1. **Test each P0/P1 case in Evo manually**
2. **Compare with Logseq side-by-side**
3. **Document which are missing/broken**
4. **Implement missing behaviors**
5. **Add E2E tests for each**

See full 656-line analysis: `dev/logseq-interaction-edge-cases.md`
