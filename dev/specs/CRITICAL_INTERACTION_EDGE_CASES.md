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

**Evo status**: 🔍 CHECK

---

### ✅ Enter in Middle → Left-Trimmed Second Block
**Behavior**: When splitting a block, the second block has left whitespace trimmed.

```
Before: Hello |  World    (cursor after "Hello ")
After:  Hello
        World|            (trimmed "  World" → "World", cursor at 0)
```

**Implementation**: `string/triml` on second block text

**Evo status**: 🔍 CHECK

---

### ✅ Backspace at Start → Cursor at Join Point (UTF-8 Aware)
**Behavior**: When merging blocks, cursor positions at the join point using **UTF-8 byte length**.

```
Before: Hello
        |World   (cursor at 0 in "World")
After:  Hello|World   (cursor at byte position after "Hello")
```

**Critical detail**: Uses `(gobj/get (utf8/encode original-content) "length")` for emoji/multi-byte chars

**Evo status**: 🔍 CHECK - are we UTF-8 aware?

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

**Evo status**: 🔍 CHECK

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

**Evo status**: 🔍 CHECK - not implemented

---

## Testing Priority Matrix

| Feature | User Impact | Implementation Complexity | Priority |
|---------|-------------|--------------------------|----------|
| Enter at 0 → block above | High | Low | **P0** |
| Backspace merge cursor position | High | Medium (UTF-8) | **P0** |
| Column position memory | High | Low (already done!) | ✅ |
| Left-trim on split | Medium | Low | **P1** |
| Paired deletion | Medium | Medium | **P1** |
| Shift+Enter newline | Medium | Low | **P1** |
| Async char buffering | Low | High | **P2** |
| Selection direction tracking | Low | Medium | **P2** |

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
