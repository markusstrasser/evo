# Fixing the Testing Disconnect

## The Brutal Truth

**You're right. 259 tests pass, bugs exist. Tests aren't catching real problems.**

### Root Causes

1. **Unit tests verify operations, not outcomes**
   - Test: "Operation updates cursor position to 5" ✅
   - Reality: Cursor jumps to start in browser ❌

2. **No visual state verification**
   - Tests check `db[:cursor-position]`
   - Don't check what user actually sees

3. **Missing integration coverage**
   - Test individual intent handlers ✅
   - Don't test intent → ops → re-render → DOM → Selection API chain ❌

4. **Operation log is useless for debugging**
   - Shows raw EDN maps
   - Can't visualize tree/cursor/selection changes

## Concrete Fixes

### Fix 1: Add Snapshot Tests with Visual DSL

**Problem**: Tests verify DB but not visual outcome.

**Solution**: Add "visual assertions" using DSL.

```clojure
(deftest cursor-stays-on-indent
  (testing "Cursor position preserved when indenting"
    (let [db (given-tree "
           -V AAA
           -E He|llo
           -V CCC
           ")]

      ;; Perform indent
      (let [db' (apply-intent db {:type :indent :block-id "BBB"})]

        ;; Assert tree structure AND cursor position
        (assert-tree-matches db' "
          -V AAA
            -E He|llo    ← cursor stayed at pos 2
          -V CCC
        ")))))
```

**Why this helps**:
- Visually see expected cursor position
- Tree structure and cursor in one assertion
- Clear what "correct" means

### Fix 2: Add E2E Tests for Cursor/Selection

**Problem**: Unit tests can't verify browser Selection API behavior.

**Solution**: Add targeted E2E tests that:
1. Set up specific tree state
2. Perform action (indent, outdent, navigate)
3. Verify cursor position in DOM
4. Verify selection state in DOM

```javascript
// test/e2e/cursor_bugs.spec.js

test('cursor stays in place after indent', async ({ page }) => {
  // Set up: Block with cursor in middle
  await setupTree(page, `
    - AAA
    - BBB  ← cursor here at pos 2
    - CCC
  `);
  await setCursor(page, 'BBB', 2);

  // Action
  await page.keyboard.press('Tab'); // indent

  // Verify DOM
  const cursorPos = await getCursorPosition(page);
  expect(cursorPos.blockId).toBe('BBB');
  expect(cursorPos.offset).toBe(2);  // Should stay at 2

  // Verify tree structure
  const tree = await getTreeStructure(page);
  expect(tree).toMatchSnapshot(); // Visual tree representation
});
```

**Coverage needed** (these are the bugs you're seeing):
- ✅ Cursor preserved on indent/outdent
- ✅ Cursor preserved on block merge
- ✅ Selection cleared on Escape
- ✅ Focus moved to correct block on navigation
- ✅ Edit mode persists after outdent
- ✅ Text not duplicated after rapid typing

### Fix 3: Visual Operation Log in UI

**Current** (useless):
```
Intent: {:type :update-content, :block-id "af4b2c", :text "Hello world", :cursor-pos 11}
Operations: [{:op :update-node, :id "af4b2c", :props {:text "Hello world"}}]
```

**Proposed** (useful):

```
✏️  EDITED BLOCK
    "Hello there" → "Hello world"
                ^^^^^

📍 CURSOR: 11
    Hello world|

🌳 TREE (unchanged)
    - AAA
      - BBB ← edited
```

**Implementation**:

```clojure
(ns shell.dev-tools
  "Development tools for visual operation logging")

(defn format-intent-visual
  "Format intent with before/after tree visualization"
  [db-before intent ops db-after]
  (str
    ;; Header
    (intent-icon intent) " " (intent-label intent) "\n\n"

    ;; Tree diff
    (when (tree-changed? db-before db-after)
      (str "🌳 TREE:\n"
           (tree-diff-visual db-before db-after) "\n\n"))

    ;; Text diff
    (when (text-changed? db-before db-after intent)
      (let [block-id (:block-id intent)
            old-text (get-text db-before block-id)
            new-text (get-text db-after block-id)]
        (str "✏️  TEXT:\n"
             (text-diff-inline old-text new-text) "\n\n")))

    ;; Cursor
    (str "📍 CURSOR: "
         (format-cursor db-after) "\n\n")

    ;; Operations (compact)
    (str "⚙️  OPS (" (count ops) "):\n"
         (format-ops-compact ops))))

;; Hook into intent dispatcher
(defn handle-intent-with-logging [db intent]
  (let [{:keys [db ops]} (api/dispatch db intent)]
    (when js/goog.DEBUG
      (js/console.log (format-intent-visual db intent ops db')))
    {:db db :ops ops}))
```

### Fix 4: REPL Visual Inspector

**Add REPL commands for live inspection**:

```clojure
;; In REPL
(show-tree)
; =>
; -V AAA
;   -E Hello|
;     -V World
;   -S* BBB
; -V CCC

(show-cursor)
; => Block: "a", Position: 5, Text: "Hello|"

(show-selection)
; => Anchor: "b", Focus: "c", Blocks: ["b" "c"]

(watch-intents)
; => Starts logging all intents with visual output

(diff-tree @!db-before @!db-after)
; => Shows tree diff visually
```

### Fix 5: Property-Based Tests for Invariants

**Problem**: Edge cases not covered.

**Solution**: Use test.check to generate random states, verify invariants.

```clojure
(deftest cursor-never-out-of-bounds-property
  (checking "Cursor position never exceeds text length" 100
    [db (gen-db-with-cursor)
     intent (gen-editing-intent)]

    (let [{:keys [db']} (api/dispatch db intent)
          cursor-pos (get-cursor-pos db')
          block-id (get-editing-block db')
          text (get-text db' block-id)]

      ;; INVARIANT: Cursor must be within [0, text.length]
      (is (<= 0 cursor-pos (count text))
          (str "Cursor out of bounds!\n"
               "Text: " (pr-str text) "\n"
               "Cursor: " cursor-pos "\n"
               "Intent: " (pr-str intent))))))
```

**Invariants to test**:
- Cursor position ≤ text length
- Editing block exists in tree
- Selected blocks exist in tree
- Parent-child relationships are consistent
- No cycles in tree
- Folded blocks have children
- All nodes have valid types

### Fix 6: Integration Test Suite

**Current gap**: Test handlers in isolation, not full flow.

**Solution**: Add integration tests for full user workflows.

```clojure
(ns integration.user-workflows-test
  "Test complete user interaction flows")

(deftest typing-and-indenting-workflow
  (testing "User types text then indents block"
    (let [db (given-tree "-E |")

          ;; Step 1: Type "Hello"
          db (apply-intent db {:type :update-content
                               :block-id "a"
                               :text "Hello"})
          _ (assert-tree db "-E Hello|")

          ;; Step 2: Press Enter (create new block)
          db (apply-intent db {:type :smart-split
                               :block-id "a"
                               :cursor-pos 5})
          _ (assert-tree db "
            -V Hello
            -E |
          ")

          ;; Step 3: Type "World"
          db (apply-intent db {:type :update-content
                               :block-id "b"
                               :text "World"})
          _ (assert-tree db "
            -V Hello
            -E World|
          ")

          ;; Step 4: Indent
          db (apply-intent db {:type :indent
                               :block-id "b"})]

      ;; Final assertion: Full tree correct
      (assert-tree db "
        -V Hello
          -E World|
      "))))
```

**Workflows to test**:
- Type → Enter → Type → Indent
- Navigate down → Type → Delete (merge)
- Select multiple → Indent all
- Type → Escape → Click another block
- Type fast → Blur (race condition)

## Action Plan

### Phase 1: Stop the Bleeding (2 hours)

1. **Identify top 5 bugs**
   - List the actual bugs you're seeing
   - Cursor position?
   - Selection state?
   - Outdent behavior?

2. **Write failing E2E tests**
   - One E2E test per bug
   - Verify DOM behavior, not just DB

3. **Fix bugs until E2E tests pass**
   - Then add unit tests for regression

### Phase 2: Visual Infrastructure (4 hours)

1. **Implement DSL parser/renderer** (2h)
   - `parse-tree` - DSL string → DB
   - `render-tree` - DB → DSL string
   - Add to `test/dsl.cljc`

2. **Add visual operation logger** (1h)
   - Format intents with tree diff
   - Add to dev tools

3. **Add REPL helpers** (1h)
   - `(show-tree)`, `(show-cursor)`, `(show-selection)`
   - `(watch-intents)` for live logging

### Phase 3: Test Coverage (8 hours)

1. **Add cursor E2E tests** (3h)
   - 10 tests for cursor preservation
   - Cover indent, outdent, navigate, merge, split

2. **Add selection E2E tests** (2h)
   - Selection cleared on Escape
   - Selection persists on indent
   - Multi-select works correctly

3. **Add integration tests** (3h)
   - 5 common workflows
   - Type → Enter → Indent
   - Navigate → Edit → Blur
   - etc.

### Phase 4: Invariant Tests (4 hours)

1. **Add property-based tests** (4h)
   - Cursor bounds invariant
   - Tree structure invariants
   - Generate random operations
   - Verify invariants always hold

## Quick Wins (Do These Today)

### Win 1: Add Visual Assertions to 5 Existing Tests

Pick 5 unit tests, add visual assertions:

```clojure
;; Before
(is (= "a" (parent-of db "b")))

;; After
(assert-tree db "
  -V AAA
    -V BBB ← parent is AAA
")
```

### Win 2: Add `(show-tree)` REPL Helper

```clojure
(ns dev.repl
  (:require [test.dsl :as dsl]))

(defn show-tree
  "Print current tree in visual DSL format"
  ([]
   (show-tree @shell.blocks-ui/!db))
  ([db]
   (println (dsl/render-tree db))))
```

Usage:
```clojure
;; In REPL
(show-tree)
; =>
; -V AAA
;   -E Hello|
```

### Win 3: Add Visual Log to One Intent

Pick one intent (like `:indent`), add visual logging:

```clojure
(intent/register-intent! :indent
  {:handler
   (fn [db {:keys [block-id]}]
     (let [ops (indent-ops db block-id)]

       ;; Log visual diff
       (when js/goog.DEBUG
         (let [db' (:db (tx/interpret db ops))]
           (js/console.log
             (str "🔼 INDENT\n\n"
                  "BEFORE:\n" (render-tree db) "\n"
                  "AFTER:\n" (render-tree db')))))

       ops))})
```

## Measuring Success

### Before
- ❌ 259 tests pass, bugs exist
- ❌ Can't visualize test expectations
- ❌ Operation log is useless
- ❌ No cursor/selection coverage

### After
- ✅ Tests catch cursor bugs (E2E coverage)
- ✅ Visual assertions show expected state
- ✅ Operation log is readable
- ✅ Integration tests cover full flows
- ✅ Property tests verify invariants

## Summary

**The core issue**: Unit tests verify **logic** (operations correct), not **outcomes** (user sees correct thing).

**The fix**:
1. Add visual DSL for readable test assertions
2. Add E2E tests for cursor/selection (browser behavior)
3. Add integration tests for full workflows
4. Add property tests for invariants
5. Make operation log human-readable

**Start with**:
- Visual DSL (VISUAL_TEST_DSL.md)
- 5 cursor E2E tests
- Visual operation logger
- REPL helper `(show-tree)`

This will immediately make bugs visible and tests meaningful.
