# Shift+Arrow Text Selection in Editing Mode - Spec

**Goal:** Match Logseq's behavior where `Shift+↑/↓` does text selection when cursor is NOT at block boundaries, but block selection when AT boundaries.

**Status:** ❌ Not implemented - Evo currently always does block selection on Shift+Arrow

---

## Behavior Definition

### Current Evo Behavior (WRONG)

```
User editing block: "hello world"
Cursor at position 6: "hello |world"
Press Shift+↑

Result: Exits edit mode, extends block selection to previous block
```

### Expected Logseq Behavior (CORRECT)

```
User editing block: "hello world"
Cursor at position 6: "hello |world"
Press Shift+↑

If cursor is NOT on first row → Select text within block (browser default)
If cursor IS on first row → Select whole block (extend block selection)
```

---

## Detailed Specification

### Shift+↑ (Up with Shift)

**When NOT at first row:**
- Action: Text selection within block (browser default)
- Stay in edit mode
- Do NOT extend block selection

**When at first row:**
- Action: Extend block selection upward
- Stay in edit mode
- Current block + previous block selected

### Shift+↓ (Down with Shift)

**When NOT at last row:**
- Action: Text selection within block (browser default)
- Stay in edit mode
- Do NOT extend block selection

**When at last row:**
- Action: Extend block selection downward
- Stay in edit mode
- Current block + next block selected

---

## Implementation Strategy

### Step 1: Row Detection (Already Exists!)

Evo already has `detect-cursor-row-position` in `shell/blocks_ui.cljs`:

```clojure
;; shell/blocks_ui.cljs:113-141
(defn detect-cursor-row-position [editable-el]
  "Detect if cursor is at first row, last row, or middle.

   Returns: {:first-row? bool :last-row? bool}

   Uses mock-text technique (same as Logseq)"
  ...)
```

✅ This already works - we use it for plain arrow navigation.

### Step 2: Add Shift+Arrow Handler

**Current code** (`blocks_ui.cljs:56-105`):

```clojure
(cond
  ;; Plain ArrowUp - navigate to prev block
  (and editing? (= key "ArrowUp") at-start? ...)
  (do ...)

  ;; Plain ArrowDown - navigate to next block
  (and editing? (= key "ArrowDown") at-end? ...)
  (do ...))
```

**Add NEW cases** (before plain arrow cases):

```clojure
(cond
  ;; NEW: Shift+ArrowUp at first row → Block selection
  (and editing? (= key "ArrowUp") shift? (not mod?))
  (let [row-pos (detect-cursor-row-position editable-el)]
    (when (:first-row? row-pos)
      (.preventDefault e)
      (handle-intent {:type :selection :mode :extend-prev})))

  ;; NEW: Shift+ArrowDown at last row → Block selection
  (and editing? (= key "ArrowDown") shift? (not mod?))
  (let [row-pos (detect-cursor-row-position editable-el)]
    (when (:last-row? row-pos)
      (.preventDefault e)
      (handle-intent {:type :selection :mode :extend-next})))

  ;; Existing: Plain ArrowUp
  (and editing? (= key "ArrowUp") at-start? (not shift?) ...)
  (do ...)

  ;; Existing: Plain ArrowDown
  (and editing? (= key "ArrowDown") at-end? (not shift?) ...)
  (do ...))
```

**Key points:**
- Check `shift?` flag FIRST
- Only call `.preventDefault` if on first/last row
- If NOT on boundary → let browser handle (text selection)
- Use existing `:selection :extend-prev/extend-next` intents

---

## Testing Strategy

### Unit Tests (Data Layer) - 95%

**Test: Selection intent extends correctly**

```clojure
;; test/plugins/selection_test.cljc
(deftest extend-selection-while-editing-test
  (testing "Shift+ArrowUp at start extends selection upward"
    (let [db (-> (sample-db)
                 (tx/apply-intent {:type :enter-edit
                                  :block-id "b"
                                  :cursor-at :start})
                 (tx/apply-intent {:type :selection
                                  :mode :extend-prev}))]
      ;; Both blocks selected
      (is (= #{"a" "b"} (q/selection db)))
      ;; Still editing block B
      (is (= "b" (q/editing-block-id db)))
      ;; Focus moved to A (new selection focus)
      (is (= "a" (q/focus db)))))

  (testing "Shift+ArrowDown at end extends selection downward"
    (let [db (-> (sample-db)
                 (tx/apply-intent {:type :enter-edit
                                  :block-id "a"
                                  :cursor-at :end})
                 (tx/apply-intent {:type :selection
                                  :mode :extend-next}))]
      (is (= #{"a" "b"} (q/selection db)))
      (is (= "a" (q/editing-block-id db)))
      (is (= "b" (q/focus db))))))
```

✅ **Status:** These tests already pass! The `:extend-prev`/`:extend-next` logic exists.

### Browser Tests (Integration) - 5%

**Test: Row detection accuracy**

```javascript
// e2e/shift-arrow-selection.spec.js
test.describe('Shift+Arrow Text Selection', () => {
  test('Shift+Up on first row → block selection', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['Block A', 'Block B']);

    // Edit block B
    const blockB = page.locator('[data-block-id="b"] .content-edit');
    await blockB.click();
    await page.keyboard.press('Home'); // Move to start (first row)

    // Press Shift+Up
    await page.keyboard.press('Shift+ArrowUp');

    // Both blocks should be selected
    const selectedBlocks = await page.locator('.selected').count();
    expect(selectedBlocks).toBe(2);

    // Still editing block B
    const editing = await page.evaluate(() =>
      document.activeElement.closest('[data-block-id]').getAttribute('data-block-id')
    );
    expect(editing).toBe('b');
  });

  test('Shift+Up NOT on first row → text selection', async ({ page }) => {
    await page.goto('/');
    // Long text that wraps to multiple rows
    await createBlocks(page, [
      'This is a very long line that will wrap to multiple rows when rendered in the browser viewport'
    ]);

    const block = page.locator('.content-edit').first();
    await block.click();
    await page.keyboard.press('End'); // Move to end (last row)
    await page.keyboard.press('ArrowUp'); // Move up one row (middle row now)

    // Press Shift+Up
    await page.keyboard.press('Shift+ArrowUp');

    // Should have text selection (not block selection)
    const hasTextSelection = await page.evaluate(() => {
      const sel = window.getSelection();
      return !sel.isCollapsed; // Has selection
    });
    expect(hasTextSelection).toBe(true);

    // Should NOT have block selection
    const selectedBlocks = await page.locator('.selected').count();
    expect(selectedBlocks).toBe(0);
  });
});
```

### Manual Testing Checklist

**Scenario 1: Single-line block**
- [ ] Edit block "hello world"
- [ ] Cursor at start (first row)
- [ ] Press `Shift+↑` → Previous block selected
- [ ] Still editing current block ✅
- [ ] Both blocks highlighted ✅

**Scenario 2: Multi-line block (wrapped text)**
- [ ] Edit long block that wraps to 3 rows
- [ ] Cursor on row 2 (middle)
- [ ] Press `Shift+↑` → Text selected within block ✅
- [ ] NO block selection ✅
- [ ] Still in edit mode ✅

**Scenario 3: Multi-line block at boundary**
- [ ] Same long block, cursor on row 1 (first row)
- [ ] Press `Shift+↑` → Previous block selected ✅
- [ ] Cursor on row 3 (last row)
- [ ] Press `Shift+↓` → Next block selected ✅

---

## Edge Cases

### Edge Case 1: Empty Block

```
Block A: "hello"
Block B: "" (empty)
Block C: "world"

User editing Block B (cursor at position 0)
Press Shift+↑

Expected: Block A and B selected
Reason: Cursor is on first row (even though block is empty)
```

### Edge Case 2: Single Character

```
Block A: "hello"
Block B: "x" (single char)
Block C: "world"

User editing Block B, cursor at position 0
Press Shift+↑

Expected: Block A and B selected
Reason: Single char is both first and last row
```

### Edge Case 3: Selection Already Exists

```
User editing Block B: "hello world"
Has text selection: "world" selected
Press Shift+↑

Expected:
1. If on first row → Collapse selection, then extend block selection
2. If not on first row → Extend text selection upward (browser default)
```

**Implementation:**
```clojure
(when (and shift? at-first-row?)
  ;; First collapse any text selection
  (when (has-text-selection?)
    (collapse-selection-to-start))
  ;; Then extend block selection
  (.preventDefault e)
  (handle-intent {:type :selection :mode :extend-prev}))
```

---

## Files to Modify

### 1. `shell/blocks_ui.cljs`

**Function:** `handle-global-keydown`

**Changes:**
- Add `Shift+ArrowUp` case (before plain `ArrowUp`)
- Add `Shift+ArrowDown` case (before plain `ArrowDown`)
- Use existing `detect-cursor-row-position`
- Call existing `:selection :extend-prev/extend-next` intents

**Estimated:** 10-15 lines of code

### 2. No other files needed!

- Selection logic already exists (`plugins/selection.cljc`)
- Row detection already exists (`blocks_ui.cljs`)
- Just need to wire up the keyboard handler

---

## Implementation Checklist

- [ ] Add `Shift+ArrowUp` handler in `blocks_ui.cljs`
- [ ] Add `Shift+ArrowDown` handler in `blocks_ui.cljs`
- [ ] Write unit tests for selection extension
- [ ] Write browser test for row detection accuracy
- [ ] Manual test: single-line block
- [ ] Manual test: multi-line wrapped text
- [ ] Manual test: empty block
- [ ] Manual test: with existing text selection

---

## Debugging Tips

**Check if row detection works:**

```clojure
;; In browser console (after loading debug.cljs)
DEBUG.dispatch({type: 'enter-edit', blockId: 'some-id', cursorAt: 'start'})

;; Then in elements panel, check mock-text div
document.querySelector('#mock-text')

;; Manually trigger row detection
let el = document.querySelector('.content-edit')
// See shell/blocks_ui.cljs:113 for the function
```

**Check if selection extends:**

```clojure
;; Before Shift+Arrow
DEBUG.selection()  // ["block-b"]

;; After Shift+Arrow
DEBUG.selection()  // ["block-a", "block-b"]

;; Check focus
DEBUG.state().nodes['session/selection'].props.focus  // "block-a"
```

---

## Success Criteria

✅ Shift+↑/↓ does text selection when NOT at row boundaries
✅ Shift+↑/↓ does block selection when AT row boundaries
✅ Edit mode preserved during block selection extension
✅ Works with single-line and multi-line blocks
✅ Works with empty blocks
✅ Existing text selection is collapsed before block selection

---

## References

**Logseq Source:**
- `src/main/frontend/handler/editor.cljs:3397-3415` - `shortcut-select-up-down` function
- `src/main/frontend/util/cursor.cljs:184-206` - Row detection logic

**Evo Source:**
- `shell/blocks_ui.cljs:56-105` - Current arrow key handling
- `shell/blocks_ui.cljs:113-141` - Row detection (already exists!)
- `plugins/selection.cljc:120-169` - Selection intent handlers
