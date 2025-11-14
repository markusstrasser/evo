# Test Equivalence Gaps: What Unit Tests Cannot Verify

## TL;DR

**Unit/view tests verify LOGIC correctness. E2E tests verify USER-VISIBLE behavior.**

These are **not equivalent**. There are real gaps where correct logic still produces broken UX.

## The Fundamental Problem

### Correct Logic ≠ Correct Behavior

```clojure
;; Unit test passes ✅
(deftest update-text-test
  (let [{:keys [ops]} (intent/apply-intent db {:type :update-content
                                                :block-id "a"
                                                :text "Hello"})]
    (is (= "Hello" (:text (:props (first ops)))))))
```

But in the browser:
- ❌ Cursor jumps to start after typing
- ❌ Text appears twice in DOM
- ❌ Input lags by 200ms
- ❌ Focus lost after update
- ❌ Screen reader doesn't announce change

**The unit test verified the operation is correct, but not that the UX works.**

## Categories of Gaps

### 1. Replicant DOM Diffing Bugs

**What unit tests verify:**
- Hiccup structure is correct
- State updates correctly

**What they miss:**
- Replicant's VDOM diff algorithm bugs
- DOM patching errors
- Attribute update order issues

**Real example from our E2E tests** (test/e2e/editing.spec.js:82):
```javascript
test('text is not duplicated in DOM', async ({ page }) => {
  await page.keyboard.type('Single line of text');
  const blockHTML = await page.evaluate(() =>
    document.querySelector('[contenteditable="true"]').innerHTML
  );
  const textCount = (blockHTML.match(/Single line of text/g) || []).length;
  expect(textCount).toBe(1); // This FAILED before the fix!
});
```

**Why unit test wouldn't catch this:**
```clojure
;; This would pass even with the bug
(deftest text-update-test
  (let [hiccup (Block {:db db :block-id "a" :on-intent identity})]
    (is (= "Single line of text" (extract-text hiccup))))) ; ✅ Passes
```

The bug was in **Replicant's DOM reconciliation**, not in our hiccup generation.

### 2. Cursor Position & Selection API

**What unit tests verify:**
- Cursor position stored in DB
- Cursor position passed to component

**What they miss:**
- Actual `window.getSelection()` behavior
- Browser's contenteditable quirks
- Cursor jumping during re-renders
- Selection getting cleared

**Real bug that unit tests can't catch:**

```clojure
;; Unit test: Cursor position logic is correct ✅
(deftest set-cursor-position-test
  (let [ops (intent/apply-intent db {:type :set-cursor :pos 5})]
    (is (= 5 (get-in ops [:cursor-position]))))) ; ✅ Passes

;; But in the browser:
;; - Replicant re-renders
;; - DOM gets replaced
;; - Selection API returns offset 0
;; - User sees cursor jump to start
```

**E2E test catches this** (test/e2e/editing.spec.js:36):
```javascript
test('cursor never jumps to start while typing', async ({ page }) => {
  for (let i = 0; i < text.length; i++) {
    const before = await getCursorPosition(page);
    await page.keyboard.type(text[i]);
    const after = await getCursorPosition(page);

    // CRITICAL: Real Selection API behavior
    if (i > 0 && after.offset === 0) {
      throw new Error('REGRESSION: Cursor jumped to start');
    }
  }
});
```

This tests the **interaction** between:
- Our code setting cursor position
- Replicant's re-rendering
- Browser's Selection API
- contentEditable behavior

**No unit test can verify this.**

### 3. Lifecycle Hook Execution

**What unit tests verify:**
- `:replicant/on-render` function exists in hiccup
- Function contains correct logic

**What they miss:**
- Hook actually gets called
- Hook called at right time
- Hook called with correct arguments
- Multiple hooks don't conflict

**Example from our bug** (`mounting?` doesn't exist):

```clojure
;; View test: Component renders with on-render hook ✅
(deftest block-on-render-test
  (let [view (Block {:db db :block-id "a" :on-intent identity})]
    (is (fn? (get-in view [:replicant/on-render]))))) ; ✅ Passes

;; But in the browser:
;; - Hook fires
;; - Tries to destructure {:replicant/keys [mounting?]}
;; - mounting? is nil (doesn't exist!)
;; - Logic doesn't work as expected
```

Only an E2E test catches this:
```javascript
test('text set only on mount', async ({ page }) => {
  await page.keyboard.type('Hello');
  await page.click('[contenteditable]'); // Re-render
  const text = await page.locator('[contenteditable]').textContent();
  expect(text).toBe('Hello'); // Would fail if text reset on every render
});
```

### 4. Timing & Async Behavior

**What unit tests verify:**
- Synchronous state updates
- Deterministic operation order

**What they miss:**
- Race conditions
- Event handler timing
- Re-render timing
- Focus timing

**Real example:**

```clojure
;; Unit test: Focus intent works ✅
(deftest focus-block-test
  (let [ops (intent/apply-intent db {:type :focus-block :id "a"})]
    (is (= "a" (get-in ops [:editing-block-id]))))) ; ✅ Passes

;; But in the browser:
;; 1. State update happens
;; 2. Replicant schedules re-render (async!)
;; 3. Component renders with :replicant/on-render
;; 4. Hook calls .focus() - but DOM not ready yet!
;; 5. Focus doesn't happen
```

E2E test catches this:
```javascript
test('block gets focused after click', async ({ page }) => {
  await page.click('.block');
  await page.waitForTimeout(50); // Wait for async render
  const focused = await page.evaluate(() =>
    document.activeElement.hasAttribute('contenteditable')
  );
  expect(focused).toBe(true);
});
```

### 5. CSS & Layout Effects

**What unit tests verify:**
- CSS class names in hiccup

**What they miss:**
- CSS rules actually applied
- Layout causing behavior changes
- z-index issues
- Positioning bugs

**Example: Mock-text positioning**

```clojure
;; Unit test: Mock-text element exists ✅
(deftest mock-text-rendered-test
  (let [view (BlockList {:db db})]
    (is (some #(= :div#mock-text %) view)))) ; ✅ Passes

;; But in the browser:
;; - Mock-text rendered but not positioned
;; - CSS wrong (visible instead of hidden)
;; - getBoundingClientRect returns wrong values
;; - Cursor row detection breaks
```

E2E test catches this (test/e2e/editing.spec.js:99):
```javascript
test('mock-text positioned correctly', async ({ page }) => {
  const mockText = await page.locator('#mock-text');
  const position = await mockText.evaluate(el => ({
    visibility: getComputedStyle(el).visibility,
    position: getComputedStyle(el).position
  }));
  expect(position.visibility).toBe('hidden');
  expect(position.position).toBe('absolute');
});
```

### 6. Event Handler Wiring

**What unit tests verify:**
- Event handlers exist in hiccup
- Handler data structure correct

**What they miss:**
- Events actually fire
- Events bubble correctly
- preventDefault works
- Multiple handlers don't conflict

**Example:**

```clojure
;; View test: Input handler exists ✅
(deftest input-handler-test
  (let [view (Block {:db db :block-id "a" :on-intent identity})
        actions (select-actions view :.content-edit [:on :input])]
    (is (= [[:update-content "a" :event/target.value]]
           actions)))) ; ✅ Passes

;; But in the browser:
;; - set-dispatch! not called, handler never fires
;; - Or interpolate-actions not called, placeholder not replaced
;; - Or event bubbling stopped by parent
;; - User types, nothing happens
```

E2E catches this:
```javascript
test('typing updates text', async ({ page }) => {
  await page.keyboard.type('Hello');
  const text = await page.locator('[contenteditable]').textContent();
  expect(text).toBe('Hello'); // Would fail if handlers not wired
});
```

## What Can Be Safely Replaced?

### ✅ Safe to Replace: Pure Business Logic

**These are deterministically equivalent:**

```
E2E: Create blocks via keyboard → verify tree structure
Unit: Create blocks via operations → verify tree structure
```

**Why equivalent:**
- Tree structure is pure data
- No DOM involved
- No browser APIs
- Deterministic

**Example:**
```clojure
;; Unit test IS equivalent to E2E for this
(deftest indent-places-under-sibling
  (let [db (-> (db/empty-db)
               (tx/interpret [{:op :create-node :id "a" :type :block}
                              {:op :place :id "a" :under :doc :at :last}
                              {:op :create-node :id "b" :type :block}
                              {:op :place :id "b" :under :doc :at :last}])
               :db)
        {:keys [ops]} (intent/apply-intent db {:type :indent :block-id "b"})]
    (is (= "a" (get-in (tx/interpret db ops) [:db :derived :parent-of "b"])))))
```

This **completely replaces** the E2E test because:
- Tree structure is the source of truth
- DOM just reflects the tree
- If tree is correct, DOM will be correct (given Replicant works)

### ⚠️ Not Equivalent: UI Behavior

**These are NOT deterministically equivalent:**

```
E2E: Type text → verify cursor position
Unit: Update text → verify cursor value in DB
```

**Why not equivalent:**
- Cursor position in DB ≠ cursor position in DOM
- Setting cursor requires Selection API
- Replicant re-render can reset cursor
- Browser contenteditable is unpredictable

## Decision Framework

Use this to determine if replacement is safe:

### Question 1: Does the test verify browser APIs?

- `window.getSelection()` → ❌ Not replaceable
- `document.activeElement` → ❌ Not replaceable
- `getBoundingClientRect()` → ❌ Not replaceable
- Pure data transformation → ✅ Replaceable

### Question 2: Does it test Replicant's behavior?

- DOM diffing/patching → ❌ Not replaceable
- Lifecycle hook execution → ❌ Not replaceable
- Event handler registration → ❌ Not replaceable
- Hiccup generation → ✅ Replaceable

### Question 3: Does it involve timing?

- Async re-renders → ❌ Not replaceable
- Focus timing → ❌ Not replaceable
- Event sequencing → ❌ Not replaceable
- Synchronous updates → ✅ Replaceable

### Question 4: Is it purely data?

- Tree structure → ✅ Replaceable
- DB state → ✅ Replaceable
- Hiccup structure → ✅ Replaceable
- DOM structure → ❌ Not replaceable

## Revised Recommendation

### Keep More E2E Tests Than Initially Suggested

**Original**: 15 E2E tests
**Revised**: ~25-30 E2E tests

**Add E2E for:**
1. All cursor position scenarios (10 tests)
2. All focus management (5 tests)
3. Event handler wiring verification (5 tests)
4. Replicant lifecycle hooks (5 tests)

**Can still eliminate:**
1. Pure tree operations (20 tests → unit tests)
2. Pure state updates (10 tests → unit tests)
3. Logic without UI side effects (10 tests → unit tests)

### New Speed Estimate

**Before**: 50 E2E tests × 500ms = 25 seconds

**After**:
- 25 E2E tests × 500ms = 12.5 seconds (still keep critical ones)
- 40 unit/view tests × 5ms = 0.2 seconds
- **Total: 13 seconds** (2x faster, not 3x)

**But we gain:**
- More reliable (unit tests don't flake)
- Better coverage (can test edge cases easily)
- Faster iteration (REPL testing)
- Easier debugging (pure functions)

## The Honest Answer

### What I Should Have Said

**Instead of**: "60-70% of E2E tests can be eliminated"

**Should say**: "40-50% of E2E tests can be replaced with equivalent unit tests (pure logic). The rest should stay as E2E because they test browser/Replicant integration."

### The Real Value

The value isn't **eliminating** E2E tests. The value is:

1. **Adding** view/component tests (currently missing)
2. **Moving** pure logic to unit tests (faster iteration)
3. **Keeping** E2E tests for critical browser behavior
4. **Using** the right test for the right thing

## Examples: What to Keep vs Replace

### Keep E2E ❌

```javascript
// Tests browser behavior - MUST keep
test('cursor never jumps during typing', ...)
test('focus applied after edit mode', ...)
test('mock-text positioned absolutely', ...)
test('text not duplicated in DOM', ...)
test('arrow keys navigate blocks', ...) // Keyboard API integration
```

### Replace with Unit ✅

```javascript
// Tests pure logic - CAN replace
test('backspace merge re-parents children', ...) → unit test
test('indent places under sibling', ...) → unit test
test('outdent moves to parent level', ...) → unit test
test('delete moves children up', ...) → unit test
```

### Add View Tests (New) ✨

```clojure
;; Currently missing - SHOULD add
(deftest block-rendering-test ...)
(deftest sidebar-active-page-test ...)
(deftest event-handler-extraction-test ...)
```

## Conclusion

**No, I cannot say they're deterministically equivalent.**

There are **6 categories of gaps** where unit tests cannot verify end-user behavior:
1. Replicant DOM diffing
2. Cursor/Selection API
3. Lifecycle hook execution
4. Timing/async behavior
5. CSS/layout effects
6. Event handler wiring

**The right approach:**
- Add view/component tests (currently missing)
- Keep E2E tests for browser integration (~25 tests)
- Move pure logic to unit tests (~40 tests)
- Result: Better coverage, faster feedback, more reliable

**Not**: "Replace most E2E tests"
**But**: "Use the right test level for each concern"
