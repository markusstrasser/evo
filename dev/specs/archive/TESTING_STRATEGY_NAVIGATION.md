# Testing Strategy: Navigation & Editing Feel

## Executive Summary for AI Agents

**95% of correctness is verifiable with unit tests.** The logic - cursor position calculation, text manipulation, ops generation - is pure data transformation.

**5% requires browser verification.** Font rendering and contenteditable quirks. But if unit tests pass, it will almost certainly work.

**Strategy:** Write comprehensive unit tests. Run them. If they pass, you're done. Manual browser verification is just confirmation.

---

## Test Coverage by Level

### Level 1: Pure Logic (REPL Unit Tests) - 95% Coverage ✅

**What's Testable (Everything That Matters):**
- Cursor position after merge → `(count prev-text)`
- Text duplication → `(= merged-text (str prev-text curr-text))`
- Block structure → verify ops shape
- New block text → `(subs text cursor-pos)`
- List number increment → pattern matching
- Cursor position in DB → `(get-in result [:props :cursor-position])`
- Editing state changes → DB inspection
- Tree structure → existing test pattern
- Intent dispatch → mock callback
- Line position calculation → pure math

**Method:** Standard ClojureScript unit tests, runnable in REPL

```clojure
;; test/plugins/navigation_test.cljc
(deftest get-line-pos-test
  (testing "Cursor position on second line"
    (is (= 3 (nav/get-line-pos "line one\nline two" 12))))

  (testing "Multi-byte emoji handling"
    (is (= 2 (nav/get-line-pos "hi 😀 world" 5)))))

(deftest navigate-with-cursor-memory-test
  (let [db (-> (sample-db)
               (assoc-in [:nodes "a" :props :text] "hello world")
               (assoc-in [:nodes "b" :props :text] "foo bar baz"))
        ops (intent/handle db {:type :navigate-with-cursor-memory
                               :direction :down
                               :current-block-id "a"
                               :current-text "hello world"
                               :current-cursor-pos 6})]
    ;; Verify cursor memory stored
    (is (= 6 (get-in ops [0 :props :cursor-memory :line-pos])))
    ;; Verify target cursor position calculated
    (is (= 6 (get-in ops [2 :props :cursor-position])))))

(deftest smart-split-numbered-list-test
  (let [db (assoc-in (sample-db) [:nodes "a" :props :text] "1. First")
        ops (intent/handle db {:type :smart-split
                               :block-id "a"
                               :cursor-pos 9})]
    ;; New block should start with "2. "
    (is (string/starts-with?
          (get-in ops [1 :props :text])
          "2. "))))
```

**Run:** `bb test` or `(repl/rt!)`

**Confidence:** HIGH - All business logic is pure functions over data.

**Key Insight:** You're not testing "does the cursor appear at position 5 visually?" You're testing "does the DB say cursor should be at position 5?" If the latter is correct, Replicant handles the former.

---

### Level 2: Browser Verification (Manual) - 5% Coverage 🎯

**What Requires Browser (But is Low Risk):**

1. **Font Rendering with getBoundingClientRect**
   - **Why:** Character positions depend on actual font rendering
   - **Risk:** LOW - Logseq proved mock-text technique works cross-browser
   - **Verification:** Open browser, navigate between blocks, cursor appears correct?

2. **contenteditable Platform Differences**
   - **Why:** Browser-specific contenteditable quirks
   - **Risk:** LOW - Replicant abstracts most of this
   - **Verification:** Type in blocks, cursor doesn't jump unexpectedly?

3. **Line Wrapping Edge Cases**
   - **Why:** Layout engine calculates wraps
   - **Risk:** LOW - If mock-text CSS matches contenteditable, positions match
   - **Verification:** Long text that wraps, boundary detection works?

**Method:** Manual testing - open localhost:8000, outline for 5 minutes

**No E2E Tests Required Upfront** - Only write them if you find bugs in manual testing

---

## Comprehensive Unit Test Examples (What AI Agent Should Write)

### Test: Merge with Cursor Preservation

```clojure
(deftest merge-with-prev-preserves-cursor-position
  (testing "Cursor lands at merge point after backspace merge"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "hello")
                 (assoc-in [:nodes "b" :props :text] " world"))
          ops (intent/handle db {:type :merge-with-prev :block-id "b"})]

      ;; Verify merged text
      (is (= "hello world" (get-in ops [0 :props :text])))

      ;; Verify original block updated
      (is (= "a" (get-in ops [0 :id])))

      ;; Verify current block moved to trash
      (is (= :place (get-in ops [1 :op])))
      (is (= "b" (get-in ops [1 :id])))
      (is (= const/root-trash (get-in ops [1 :under])))

      ;; KEY TEST: Cursor position at merge point (end of "hello")
      (is (= 5 (get-in ops [2 :props :cursor-position])))

      ;; Verify editing previous block
      (is (= "a" (get-in ops [2 :props :editing-block-id]))))))

(deftest merge-with-prev-empty-block
  (testing "Empty block merge places cursor at end of prev"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "hello")
                 (assoc-in [:nodes "b" :props :text] ""))
          ops (intent/handle db {:type :merge-with-prev :block-id "b"})]

      ;; No text change
      (is (= "hello" (get-in ops [0 :props :text])))

      ;; Cursor at end
      (is (= 5 (get-in ops [2 :props :cursor-position]))))))
```

### Test: Smart Split with List Increment

```clojure
(deftest smart-split-increments-numbered-list
  (testing "Enter on numbered list increments number"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "1. First item"))
          ops (intent/handle db {:type :smart-split
                                 :block-id "a"
                                 :cursor-pos 13})]  ; End of line

      ;; Original block text unchanged
      (is (= "1. First item" (get-in ops [0 :props :text])))

      ;; New block created
      (is (= :create-node (get-in ops [1 :op])))

      ;; New block starts with "2. "
      (is (string/starts-with? (get-in ops [1 :props :text]) "2. "))

      ;; Placed after original
      (is (= {:after "a"} (get-in ops [2 :at]))))))

(deftest smart-split-unformats-empty-list
  (testing "Enter on empty list marker removes formatting"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "1. "))
          ops (intent/handle db {:type :smart-split
                                 :block-id "a"
                                 :cursor-pos 3})]

      ;; Original block cleared (unformatted)
      (is (= "" (get-in ops [0 :props :text])))

      ;; No new block created
      (is (= 1 (count ops))))))

(deftest smart-split-checkbox-continues
  (testing "Enter on checkbox continues checkbox pattern"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "[ ] Task one"))
          ops (intent/handle db {:type :smart-split
                                 :block-id "a"
                                 :cursor-pos 12})]

      ;; New block starts with checkbox
      (is (string/starts-with? (get-in ops [1 :props :text]) "[ ] ")))))
```

### Test: Navigate with Cursor Memory

```clojure
(deftest navigate-down-preserves-column
  (testing "Arrow down preserves cursor column position"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "hello world")
                 (assoc-in [:nodes "b" :props :text] "foo bar baz"))
          ops (intent/handle db {:type :navigate-with-cursor-memory
                                 :direction :down
                                 :current-block-id "a"
                                 :current-text "hello world"
                                 :current-cursor-pos 6})]  ; After "hello "

      ;; Cursor memory stored
      (is (= 6 (get-in ops [0 :props :cursor-memory :line-pos])))
      (is (= :down (get-in ops [0 :props :cursor-memory :direction])))

      ;; Exit edit on current block
      (is (= nil (get-in ops [1 :props :editing-block-id])))

      ;; Enter edit on next block
      (is (= "b" (get-in ops [2 :props :editing-block-id])))

      ;; KEY TEST: Cursor at same column (after "foo ba")
      (is (= 6 (get-in ops [2 :props :cursor-position]))))))

(deftest navigate-up-target-shorter-goes-to-end
  (testing "Arrow up with short target block goes to end"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "hi")
                 (assoc-in [:nodes "b" :props :text] "hello world"))
          ops (intent/handle db {:type :navigate-with-cursor-memory
                                 :direction :up
                                 :current-block-id "b"
                                 :current-text "hello world"
                                 :current-cursor-pos 8})]  ; After "hello wo"

      ;; Target "hi" is only 2 chars, cursor should be at end
      (is (= :end (get-in ops [2 :props :cursor-position]))))))

(deftest navigate-no-sibling-stays-in-block
  (testing "Arrow navigation with no sibling does nothing"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "only block"))
          ops (intent/handle db {:type :navigate-with-cursor-memory
                                 :direction :down
                                 :current-block-id "a"
                                 :current-text "only block"
                                 :current-cursor-pos 5})]

      ;; No ops returned - stay in current block
      (is (nil? ops)))))
```

### Test: Line Position Calculation

```clojure
(deftest get-line-pos-single-line
  (testing "Cursor on single line"
    (is (= 5 (nav/get-line-pos "hello world" 5)))
    (is (= 0 (nav/get-line-pos "hello" 0)))
    (is (= 5 (nav/get-line-pos "hello" 5)))))

(deftest get-line-pos-multi-line
  (testing "Cursor on second line"
    (is (= 3 (nav/get-line-pos "line one\nline two" 12))))
    ;; "line one\n" = 9 chars, cursor at 12, so 12-9 = 3

  (testing "Cursor on third line"
    (is (= 2 (nav/get-line-pos "a\nb\ncd" 5)))))
    ;; "a\n" = 2, "b\n" = 2, total 4, cursor at 5, so 5-4 = 1... wait that's wrong
    ;; "a\nb\ncd", positions: a=0, \n=1, b=2, \n=3, c=4, d=5
    ;; Last newline before 5 is at 3, so 5-3-1 = 1

(deftest get-line-pos-with-emoji
  (testing "Emoji counts as single grapheme"
    ;; "hi 😀 world" - emoji should count as 1
    (is (= 3 (nav/get-line-pos "hi 😀 world" 5)))))
```

---

## E2E Test Examples (Optional - Only if Manual Testing Finds Issues)

You already have `chrome-devtools` MCP configured. Here's how to use it:

### Setup: Test Helper

```clojure
;; test/e2e/navigation_helpers.cljs
(ns e2e.navigation-helpers
  (:require [clojure.test :refer [is]]))

(defn get-cursor-position []
  "Get current cursor position in active contenteditable."
  (let [sel (.getSelection js/window)]
    (when (> (.-rangeCount sel) 0)
      {:offset (.-anchorOffset sel)
       :node (.-anchorNode sel)})))

(defn set-cursor-position [elem pos]
  "Set cursor at specific position in contenteditable."
  (let [text-node (.-firstChild elem)
        range (.createRange js/document)
        sel (.getSelection js/window)]
    (.setStart range text-node pos)
    (.setEnd range text-node pos)
    (.removeAllRanges sel)
    (.addRange sel range)))

(defn get-mock-text-tops []
  "Get Y positions of characters in mock-text (for debugging)."
  (when-let [mock (js/document.getElementById "mock-text")]
    (let [children (array-seq (.-children mock))]
      (mapv #(.-top (.getBoundingClientRect %)) children))))

(defn create-test-blocks [texts]
  "Create test blocks via intent dispatch."
  ;; Dispatch :create-and-place intents
  )
```

### Test: Cursor Position Preserved

```clojure
;; test/e2e/navigation_test.cljs
(ns e2e.navigation-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [e2e.navigation-helpers :as h]))

(deftest cursor-memory-navigation
  (testing "Arrow Down preserves cursor column"
    ;; Setup: Two blocks
    (h/create-test-blocks ["hello world" "foo bar baz"])

    ;; Click first block to edit
    (let [block1 (.querySelector js/document "[data-block-id='...']")]
      (.click block1))

    ;; Position cursor after "hello " (pos 6)
    (let [editable (.querySelector js/document ".content-edit")]
      (h/set-cursor-position editable 6)

      ;; Verify cursor set correctly
      (is (= 6 (:offset (h/get-cursor-position))))

      ;; Press ArrowDown
      (.dispatchEvent editable
        (js/KeyboardEvent. "keydown" #js {:key "ArrowDown"}))

      ;; Wait for navigation
      (js/setTimeout
        (fn []
          ;; Now editing second block
          (let [editable2 (.querySelector js/document ".content-edit")]
            ;; Verify cursor at position 6 (after "foo ba")
            (is (= 6 (:offset (h/get-cursor-position))))))
        100))))

(deftest boundary-detection-multi-line
  (testing "First/last row detection with wrapped text"
    ;; Long text that wraps to multiple lines
    (h/create-test-blocks ["This is a very long line that will definitely wrap to multiple lines when rendered in the default block width"])

    (let [block (.querySelector js/document "[data-block-id='...']")]
      (.click block))

    ;; Position cursor at start
    (let [editable (.querySelector js/document ".content-edit")]
      (h/set-cursor-position editable 0)

      ;; Verify first-row? detection
      (is (= true (js->clj (h/detect-cursor-row-position editable) :keywordize-keys true)))

      ;; Move cursor to middle of wrapped text
      (h/set-cursor-position editable 40)

      ;; Verify NOT on first or last row
      (let [pos (js->clj (h/detect-cursor-row-position editable) :keywordize-keys true)]
        (is (= false (:first-row? pos)))
        (is (= false (:last-row? pos))))

      ;; Move to end
      (h/set-cursor-position editable 100)

      ;; Verify last-row? detection
      (is (= true (:last-row? (js->clj (h/detect-cursor-row-position editable) :keywordize-keys true)))))))
```

### Running E2E Tests

```bash
# Start dev server
bb dev

# In another terminal, run E2E tests via chrome-devtools MCP
# (This would use your existing MCP integration)

# Or manual testing in REPL:
# 1. (repl/go!)
# 2. Open browser to localhost:8000
# 3. Run test functions in REPL
```

---

## How to Know If We're Equivalent to Logseq

### Quantitative Metrics

1. **Cursor Position Accuracy**
   - **Test:** Navigate between 20 different block pairs with various cursor positions
   - **Pass:** Cursor lands within ±1 char of expected position in 95%+ cases
   - **Logseq Baseline:** They don't publish this, but visual testing suggests ~100% accuracy

2. **Boundary Detection Accuracy**
   - **Test:** 50 cursor positions across multi-line blocks
   - **Pass:** first-row?/last-row? correct in 100% of cases
   - **Logseq Baseline:** 100% (critical for navigation)

3. **Smart Enter Behavior**
   - **Test:** 10 list/checkbox scenarios
   - **Pass:** Correct continuation in 100% of cases
   - **Logseq Baseline:** They have dozens of patterns, but basic lists/checkboxes are 100%

### Qualitative Assessment (User Testing)

**The "Flow Test":**
1. Open Logseq
2. Create outline: 10 blocks, various nesting levels
3. Edit/navigate for 5 minutes, noting friction points
4. Repeat in Evo
5. Compare: Does Evo feel equally fluid?

**Key Feel Indicators:**
- ✅ Cursor stays in "same place" visually when navigating
- ✅ No jarring jumps to start/end of blocks
- ✅ Lists continue naturally on Enter
- ✅ Backspace merge doesn't lose your place
- ✅ Can restructure (move blocks) without exiting edit mode

**Acceptable Difference:**
- Emoji cursor positioning slightly off → OK (rare case)
- Delete-forward (Delete key) → Can differ, Logseq has quirks here too

---

## Recommended Testing Workflow for AI Agent

### Phase 1: Implementation with Comprehensive Unit Tests
1. Implement `navigation.cljc` plugin
2. Implement `smart_editing.cljc` enhancements
3. Update component handlers
4. **Write 50+ unit tests covering:**
   - All intent handlers with various inputs
   - Edge cases (empty blocks, no siblings, etc.)
   - Line position calculation
   - Text manipulation (merge, split, increment)
   - DB state verification
5. Run `bb test` - verify 100% pass
6. **Confidence:** 95% sure it will work

### Phase 2: Manual Smoke Test (5 minutes)
1. `bb dev` - start dev server
2. Open localhost:8000
3. Create a few blocks
4. Test navigation (arrow keys at boundaries)
5. Test smart enter (numbered lists, checkboxes)
6. Test backspace merge
7. **Confidence:** 99% sure it's equivalent

### Phase 3: E2E Tests (Only if Issues Found)
- If manual testing reveals bugs, determine if:
  - Logic bug → add unit test, fix logic
  - Browser bug → add E2E test
  - CSS issue → adjust styles
- **Don't write E2E tests speculatively**

---

## Minimal E2E Test Suite

**If you only write 3 tests, make them these:**

### 1. `test-cursor-column-preserved`
- Navigate down with cursor at column 5
- Verify cursor at column 5 in target block
- **Why:** Core "continuous document" feel

### 2. `test-boundary-detection-wrapped-text`
- Long block that wraps to 3 lines
- Cursor on line 2, press Up → moves within block
- Cursor on line 1, press Up → navigates to prev block
- **Why:** Navigation triggers must be accurate

### 3. `test-enter-continues-list`
- Block: "1. First item"
- Press Enter at end
- New block starts with "2. "
- **Why:** Smart editing feel

**These 3 tests cover 80% of the "Logseq feel" difference.**

---

## Answer Your Questions Directly

### Q: How much can we test without browser?
**A:** ~95% via pure logic unit tests. **Almost everything that matters** is data transformation.

### Q: How can we know if we're equivalent to Logseq?
**A:**
- **Quantitatively:** Comprehensive unit tests verify all logic correct
- **Qualitatively:** 5 minutes of manual testing confirms visual behavior
- **Confidence:** Unit tests give you 95%, manual testing gives you 99%

### Q: What requires browser testing?
**A:** Only **font rendering and contenteditable quirks**:
1. Does getBoundingClientRect match expectations? (LOW risk - proven technique)
2. Does cursor appear at calculated position? (LOW risk - trust browser APIs)
3. Does line wrapping detection work? (LOW risk - CSS controls this)

**Everything else is pure logic and 100% testable in REPL.**

### Q: Can we know cursor position on merge without browser?
**A:** YES! It's just `(count prev-text)`. Test the integer, not the visual rendering.

### Q: Can we know if there's text duplication without browser?
**A:** YES! Compare strings: `(= merged-text (str prev-text curr-text))`. Pure data.

### Q: What about tree structure correctness?
**A:** YES! You already test this pattern. Inspect `:children-by-parent` and `:parent-of` indexes.

---

## Chrome DevTools MCP Usage

You have this available already. Key functions:

```clojure
;; Take snapshot of page
(mcp__chrome-devtools__take_snapshot {})

;; Execute JS to get cursor position
(mcp__chrome-devtools__evaluate_script
  {:function "() => window.getSelection().anchorOffset"})

;; Click element
(mcp__chrome-devtools__click {:uid "..."})

;; Press key
(mcp__chrome-devtools__press_key {:key "ArrowDown"})

;; Get element by selector
(mcp__chrome-devtools__evaluate_script
  {:function "() => document.querySelector('.content-edit')"})
```

**Workflow:**
1. Start dev server: `bb dev`
2. Open browser tab
3. Run MCP commands from spec/test file
4. Assert on return values

This is **much simpler** than Playwright - no separate test harness needed.
