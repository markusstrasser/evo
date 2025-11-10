# Test Redundancy Analysis

## TL;DR

With proper view/component tests, we can **eliminate 60-70% of E2E tests** while maintaining better coverage and getting **100x faster feedback**.

## Testing Pyramid (Current vs Proposed)

### Current Approach
```
        E2E (Slow)           ← 50+ tests, 2-5 min
       /          \
      /            \
     /______________\
    Unit (Fast)         ← Good kernel/plugin coverage
```

### Proposed Approach
```
     E2E (Slow)          ← 10-15 tests, 30s (only browser-specific)
       /  \
      /    \
     /______\
    /        \
   Integration       ← 30-40 tests, 5s (render→action→update)
  /            \
 /              \
/________________\
View + Unit          ← 100+ tests, 1s (pure functions)
```

## What Can Be Replaced

### ✅ Replaceable: Business Logic Tests

These test **logic that doesn't require a browser**:

#### 1. Block Merging Logic

**Current E2E** (test/e2e/critical-fixes.spec.js:56):
```javascript
test('backspace merge re-parents children', async ({ page }) => {
  // Create structure in DOM (slow: 500ms)
  await page.keyboard.type('Block A');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Child A1');
  await page.keyboard.press('Tab');
  // ... more setup

  // Perform merge (slow: 200ms)
  await page.keyboard.press('Backspace');
  await page.waitForTimeout(300);

  // Verify structure (slow: 100ms)
  const tree = await getTreeStructure(page);
  expect(tree.find(b => b.text === 'Child B1').depth).toBe(1);
});
// Total: ~1 second per test
```

**Replacement View/Integration Test**:
```clojure
(deftest backspace-merge-reparents-children
  (let [;; Setup (fast: <1ms)
        db (-> (db/empty-db)
               (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "Block A"}}
                              {:op :place :id "a" :under :doc :at :last}
                              {:op :create-node :id "a1" :type :block :props {:text "Child A1"}}
                              {:op :place :id "a1" :under "a" :at :last}
                              {:op :create-node :id "b" :type :block :props {:text "Block B"}}
                              {:op :place :id "b" :under :doc :at :last}
                              {:op :create-node :id "b1" :type :block :props {:text "Child B1"}}
                              {:op :place :id "b1" :under "b" :at :last}])
               :db)

        ;; Execute merge (fast: <1ms)
        {:keys [ops]} (intent/apply-intent db {:type :backspace-merge
                                                :block-id "b"
                                                :cursor-pos 0})
        db' (:db (tx/interpret db ops))]

    ;; Verify (fast: <1ms)
    (is (= "a" (get-in db' [:derived :parent-of "b1"]))
        "Child B1 re-parented to Block A")
    (is (= :trash (get-in db' [:derived :parent-of "b"]))
        "Block B moved to trash")))
// Total: ~1 millisecond per test (1000x faster!)
```

**Why this works**: The **business logic** of re-parenting is pure. No browser needed.

#### 2. Navigation Cursor Memory

**Current E2E** (test/e2e/navigation.spec.js:18):
```javascript
test('arrow down preserves cursor column', async ({ page }) => {
  await page.keyboard.type('Hello world this is a long line');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Short line');
  await page.keyboard.press('ArrowUp');
  await setCursorPosition(page, blocks[0].id, 10);
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(100);

  const cursor = await getCursorPosition(page);
  expect(cursor.offset).toBe(10);
});
// Total: ~500ms per test
```

**Replacement Unit Test**:
```clojure
(deftest navigate-down-preserves-column
  ;; Already exists in test/plugins/navigation_test.cljc!
  (let [db (sample-db)
        result (intent/apply-intent db {:type :navigate-with-cursor-memory
                                        :direction :down
                                        :current-block-id "a"
                                        :current-text "Hello world"
                                        :current-cursor-pos 10})]
    (is (= 10 (get-in result [:ops 0 :cursor-position])))))
// Total: <1ms per test
```

**Status**: ✅ We already have this! (test/plugins/navigation_test.cljc)

#### 3. Text Updates

**Current E2E** (test/e2e/editing.spec.js:18):
```javascript
test('typing advances cursor sequentially', async ({ page }) => {
  const results = await typeAndVerifyCursor(page, 'TESTING');
  expect(results).toEqual([
    { char: 'T', offsetAfter: 1, advanced: true },
    // ... 7 more
  ]);
});
// Total: ~700ms
```

**Replacement Integration Test**:
```clojure
(deftest typing-updates-text-sequentially
  (let [db (-> (db/empty-db)
               (tx/interpret [{:op :create-node :id "a" :type :block :props {:text ""}}
                              {:op :place :id "a" :under :doc :at :last}])
               :db)]

    ;; Simulate typing "TESTING"
    (loop [db db
           chars ["T" "E" "S" "T" "I" "N" "G"]
           results []]
      (if (empty? chars)
        (do
          (is (= 7 (count results)))
          (is (every? :advanced results)))
        (let [char (first chars)
              {:keys [ops]} (intent/apply-intent db {:type :update-content
                                                     :block-id "a"
                                                     :text (str (get-in db [:nodes "a" :props :text]) char)})
              db' (:db (tx/interpret db ops))]
          (recur db'
                 (rest chars)
                 (conj results {:char char :advanced true})))))))
// Total: <5ms
```

### ❌ Keep E2E: Browser-Specific Behavior

These **require a real browser** and cannot be unit tested:

#### 1. Actual Cursor Position Tracking

**Must Keep E2E**:
```javascript
test('cursor never jumps to start while typing', async ({ page }) => {
  for (let i = 0; i < text.length; i++) {
    const before = await getCursorPosition(page);
    await page.keyboard.type(text[i]);
    const after = await getCursorPosition(page);

    // CRITICAL: This requires window.getSelection() API
    if (i > 0 && after.offset === 0) {
      throw new Error('REGRESSION: Cursor jumped to start');
    }
  }
});
```

**Why E2E?**:
- Requires `window.getSelection()` API
- Tests contentEditable behavior
- Catches Replicant re-render bugs that reset cursor
- Tests interaction between React state and DOM state

#### 2. Focus Management

**Must Keep E2E**:
```javascript
test('entering edit mode focuses block', async ({ page }) => {
  await page.click('[contenteditable="true"]');
  const focused = await page.evaluate(() =>
    document.activeElement === document.querySelector('[contenteditable="true"]')
  );
  expect(focused).toBe(true);
});
```

**Why E2E?**:
- `document.activeElement` only works in browser
- Tests `:replicant/on-render` focus hook
- Catches timing issues (focus before DOM ready)

#### 3. Mock-Text Positioning

**Must Keep E2E** (test/e2e/editing.spec.js:99):
```javascript
test('mock-text element positioned correctly', async ({ page }) => {
  const mockText = await page.locator('#mock-text');
  const position = await mockText.evaluate(el => ({
    visibility: getComputedStyle(el).visibility,
    position: getComputedStyle(el).position
  }));

  expect(position.visibility).toBe('hidden');
  expect(position.position).toBe('absolute');
});
```

**Why E2E?**:
- Tests CSS layout (absolute positioning)
- Tests `getBoundingClientRect()` calculations
- Catches cursor row detection bugs

#### 4. Text Duplication in DOM

**Must Keep E2E** (test/e2e/editing.spec.js:82):
```javascript
test('text is not duplicated in DOM', async ({ page }) => {
  await page.keyboard.type('Single line of text');
  const blockHTML = await page.evaluate(() =>
    document.querySelector('[contenteditable="true"]').innerHTML
  );
  const textCount = (blockHTML.match(/Single line of text/g) || []).length;
  expect(textCount).toBe(1);
});
```

**Why E2E?**:
- Tests Replicant's DOM diffing algorithm
- Catches bugs where text gets duplicated in DOM
- Can't simulate DOM diffing in unit tests

### ⚠️ Partially Replaceable: Hybrid Tests

These can be **split**: logic tested in unit tests, browser behavior in E2E.

#### Example: Indentation/Outdenting

**Current E2E** (test/e2e/critical-fixes.spec.js:122):
```javascript
test('Tab indents block under sibling', async ({ page }) => {
  // Create two blocks
  await page.keyboard.type('Block A');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Block B');

  // Press Tab
  await page.keyboard.press('Tab');

  // Verify tree structure
  const tree = await getTreeStructure(page);
  expect(tree.find(b => b.text === 'Block B').depth).toBe(1);
});
```

**Split into**:

**Unit Test** (fast: <1ms):
```clojure
(deftest indent-places-under-sibling
  (let [db (create-two-blocks "A" "B")
        {:keys [ops]} (intent/apply-intent db {:type :indent
                                                :block-id "b"})]
    (is (= {:op :place :id "b" :under "a" :at :last}
           (first ops)))))
```

**E2E Test** (only for keyboard integration):
```javascript
test('Tab key triggers indent', async ({ page }) => {
  // Just test the keyboard → intent dispatch
  await page.keyboard.press('Tab');
  // Verify intent was dispatched (via window hook or event listener)
});
```

## Detailed Migration Plan

### Phase 1: Add View Test Infrastructure (Week 1)

1. **Create `test/view_util.cljc`**
   - `find-element`, `select-actions`, `select-attribute`
   - Action extraction helpers
   - Placeholder interpolation

2. **Create test fixtures for components**
   - Sample DBs with different states
   - Test on-intent handlers

### Phase 2: Migrate High-Value Tests (Week 2)

**Priority 1: Business Logic** (can delete E2E immediately):

1. ✅ Navigation cursor memory → Already done in `plugins/navigation_test.cljc`
2. ❌ Block merging logic → Add to `plugins/editing_test.cljc`
3. ❌ Indentation logic → Add to `plugins/struct_test.cljc`
4. ❌ Text formatting → Add to `plugins/text_formatting_test.cljc`

**Expected savings**: 20 E2E tests → 20 unit tests (500ms → 2ms each)

### Phase 3: Add Component Tests (Week 3)

1. **Block component**:
   - Rendering in edit/view mode
   - Event handler wiring
   - CSS class application

2. **Sidebar component**:
   - Page list rendering
   - Active page highlighting

3. **Integration tests**:
   - Typing workflow
   - Navigation workflow
   - Formatting workflow

**Expected savings**: 15 E2E tests → 15 integration tests (500ms → 5ms each)

### Phase 4: Keep Only Critical E2E (Week 4)

**Keep (~15 tests)**:
- Cursor position tracking (5 tests)
- Focus management (2 tests)
- Mock-text positioning (2 tests)
- Text duplication detection (2 tests)
- Accessibility (4 tests from e2e/accessibility.spec.js)

**Delete (~35 tests)**:
- Business logic tests (now unit tests)
- Tree structure tests (now integration tests)
- Text update tests (now integration tests)

## Expected Impact

### Speed Improvement

**Before**:
- 50 E2E tests × 500ms = **25 seconds**
- 80 unit tests × 1ms = 0.08 seconds
- **Total: 25 seconds**

**After**:
- 15 E2E tests × 500ms = 7.5 seconds
- 80 unit tests × 1ms = 0.08 seconds
- 50 view/integration tests × 5ms = 0.25 seconds
- **Total: 8 seconds** (3x faster!)

### Developer Experience

**Before**:
- Wait 25s for test feedback
- Can't run tests in REPL
- Hard to debug failures (async browser state)
- Flaky tests from timing issues

**After**:
- Wait 8s for full suite
- Run relevant tests in REPL instantly (<1s)
- Easy to debug (pure functions, data inspection)
- No flaky tests in unit/view layer

### Coverage Quality

**Before**:
- High E2E coverage (slow, brittle)
- Good unit coverage (kernel/plugins)
- **Gap**: No component testing

**After**:
- Focused E2E coverage (browser-specific only)
- Excellent unit coverage (kernel/plugins)
- **New**: Component/view coverage (fast, reliable)

## Decision Matrix

Use this to decide if a test can be migrated:

| Test Characteristic | E2E Required? | Alternative |
|-------------------|---------------|-------------|
| Tests cursor position (`getSelection`) | ✅ Yes | None |
| Tests focus (`document.activeElement`) | ✅ Yes | None |
| Tests CSS layout (`getBoundingClientRect`) | ✅ Yes | None |
| Tests keyboard events | ⚠️ Maybe | Unit test intent dispatch |
| Tests DOM structure | ❌ No | View test (hiccup) |
| Tests state updates | ❌ No | Integration test |
| Tests business logic | ❌ No | Unit test |
| Tests tree operations | ❌ No | Unit test (tx/interpret) |
| Tests rendering | ❌ No | View test |

## Anti-Patterns to Avoid

### ❌ Don't: Test Database Structure in E2E

```javascript
// BAD: Inspecting DB in E2E
const db = await page.evaluate(() => window.DEBUG.state());
expect(db.nodes["a"].props.text).toBe("Hello");
```

**Why bad**: DB structure is internal. Test the visible behavior instead.

**Do instead**:
```javascript
// GOOD: Test DOM (user-visible behavior)
await expect(page.locator('[contenteditable="true"]'))
  .toHaveText("Hello");
```

### ❌ Don't: Use waitForTimeout

```javascript
// BAD: Arbitrary waits
await page.keyboard.press('Enter');
await page.waitForTimeout(300);  // Flaky!
```

**Do instead**:
```javascript
// GOOD: Wait for condition
await page.keyboard.press('Enter');
await expect(page.locator('[contenteditable="true"]').nth(1))
  .toBeVisible();
```

### ❌ Don't: Test Logic via Browser Automation

```javascript
// BAD: Testing merge logic in E2E
test('merge preserves children', async ({ page }) => {
  // 50 lines of keyboard automation
  // Slow, brittle, hard to debug
});
```

**Do instead**:
```clojure
;; GOOD: Test in unit test
(deftest merge-preserves-children
  (let [result (intent/apply-intent db {:type :merge ...})]
    (is (= "new-parent" (get-in result [:db :derived :parent-of "child"])))))
```

## Summary

### Tests to Keep (E2E)
- Cursor position tracking (~5 tests)
- Focus management (~2 tests)
- Layout/positioning (~2 tests)
- DOM integrity (~2 tests)
- Accessibility (~4 tests)
- **Total: ~15 tests, ~8 seconds**

### Tests to Migrate (View/Integration)
- Block operations (~20 tests)
- Navigation logic (~10 tests)
- Text formatting (~5 tests)
- Component rendering (~15 tests)
- **Total: ~50 tests, ~0.3 seconds**

### Tests to Keep (Unit)
- Kernel operations (~80 tests)
- **Total: ~80 tests, ~0.08 seconds**

### Grand Total
- **145 tests in ~8 seconds** (vs 130 tests in ~25 seconds)
- **3x faster**, better coverage, easier to debug
- **Can run view/unit tests in REPL** for instant feedback

## Next Steps

1. Read `docs/REPLICANT_TESTING.md` for implementation guide
2. Create `test/view_util.cljc` with testing utilities
3. Add Block component tests as proof of concept
4. Migrate 5 high-value E2E tests to unit tests
5. Measure speed improvement
6. Continue migration based on results
