# Text Editing Testing Strategy

**Goal:** Comprehensive testing strategy for text editing behaviors that balances data-first testing (95% coverage) with browser-based verification (5% coverage).

**Context:** This strategy complements `TEXT_EDITING_BEHAVIORS_SPEC.md` by defining how to test each behavior at the appropriate level.

---

## Testing Philosophy

### The 95/5 Rule

**95% of text editing correctness is verifiable through pure data tests:**
- Intent handlers produce correct ops
- Ops update DB correctly
- Cursor positions are calculated correctly
- Text manipulation is correct (split, merge, etc.)
- Context detection logic works

**5% requires browser verification:**
- Cursor appears at calculated position
- Selection collapse works
- contenteditable quirks (paste, composition)
- Font rendering with getBoundingClientRect

**Strategy:** Write comprehensive unit tests first. Only add browser tests for bugs found in manual testing.

---

## Test Pyramid

```
         ╱ ╲          Browser E2E (5%)
        ╱   ╲         - Playwright tests
       ╱     ╲        - 10-20 critical path tests
      ╱───────╲       - Run on CI + before release
     ╱         ╲
    ╱  Component ╲    Component Tests (10%)
   ╱   (Repl+DOM) ╲   - Replicant component mounting
  ╱───────────────╲  - Keyboard event simulation
 ╱                 ╲  - 50-100 tests
╱   Unit/Intent     ╲ Unit Tests (85%)
╱    (Pure Data)     ╲ - Pure function tests
─────────────────────  - Intent → ops verification
                       - 200-500 tests
                       - Fast, deterministic
```

---

## Level 1: Unit Tests (Pure Data) - 85% Coverage

### What We Test

**All intent handlers return correct ops:**
- `:context-aware-enter` produces correct split/continuation
- `:delete-with-pair-check` removes both characters
- `:delete-forward` merges with correct priority
- `:navigate-to-adjacent` moves to correct block
- `:insert-paired-char` auto-closes correctly

**All context detection returns correct results:**
- `context-at-cursor` identifies markup, refs, code blocks
- `detect-markup-at-cursor` finds enclosing pairs
- `detect-list-item-at-cursor` extracts marker and content

**All text utilities work correctly:**
- `grapheme-length-at` handles emoji/CJK
- `count-graphemes` counts correctly
- `cursor-pos-to-grapheme-index` converts correctly

### Test Structure

```clojure
;; test/plugins/context_test.cljc
(ns plugins.context-test
  (:require [clojure.test :refer [deftest testing is]]
            [plugins.context :as ctx]))

(deftest detect-markup-test
  (testing "Cursor inside bold markup"
    (is (= {:type :markup
            :marker "**"
            :markup-type :bold
            :start 6
            :end 15
            :inner-start 8
            :inner-end 13
            :complete? true}
           (ctx/detect-markup-at-cursor "Hello **world** test" 10))))

  (testing "Cursor outside markup"
    (is (nil? (ctx/detect-markup-at-cursor "Hello **world** test" 2))))

  (testing "Incomplete markup (only opening)"
    (is (= {:type :markup
            :marker "**"
            :complete? false}
           (ctx/detect-markup-at-cursor "Hello **world" 10))))

  (testing "Nested markup (bold inside italic)"
    (is (= {:type :markup :marker "__"}
           (ctx/detect-markup-at-cursor "__hello **world** test__" 12))))

  (testing "Multiple markup on same line"
    (is (= {:type :markup :marker "**" :start 6}
           (ctx/detect-markup-at-cursor "**A** **B**" 8)))))

;; test/plugins/smart_editing_test.cljc
(ns plugins.smart-editing-test
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.db :as db]
            [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [fixtures :as f]))

(deftest paired-char-insertion-test
  (testing "Opening bracket auto-closes"
    (let [db (f/make-db {"a" {:type :block :props {:text "hello"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :insert-paired-char
                                                  :block-id "a"
                                                  :cursor-pos 5
                                                  :char "["})]
      (is (= "hello[]" (get-in ops [0 :props :text])))
      (is (= 6 (get-in ops [1 :props :cursor-position])))))

  (testing "Closing bracket skips over when next char matches"
    (let [db (f/make-db {"a" {:type :block :props {:text "hello[]"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :insert-paired-char
                                                  :block-id "a"
                                                  :cursor-pos 6
                                                  :char "]"})]
      ;; Should move cursor, not insert
      (is (= "hello[]" (get-in ops [0 :props :text])))
      (is (= 7 (get-in ops [0 :props :cursor-position])))))

  (testing "Non-paired char inserts normally"
    (let [db (f/make-db {"a" {:type :block :props {:text "hello"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :insert-paired-char
                                                  :block-id "a"
                                                  :cursor-pos 5
                                                  :char "x"})]
      (is (= "hellox" (get-in ops [0 :props :text])))
      (is (= 6 (get-in ops [1 :props :cursor-position]))))))

(deftest paired-deletion-test
  (testing "Backspace after [ deletes both [ and ]"
    (let [db (f/make-db {"a" {:type :block :props {:text "[]"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :delete-with-pair-check
                                                  :block-id "a"
                                                  :cursor-pos 1})]
      (is (= "" (get-in ops [0 :props :text])))
      (is (= 0 (get-in ops [1 :props :cursor-position])))))

  (testing "Backspace with no pair deletes one char"
    (let [db (f/make-db {"a" {:type :block :props {:text "hello"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :delete-with-pair-check
                                                  :block-id "a"
                                                  :cursor-pos 5})]
      (is (= "hell" (get-in ops [0 :props :text])))
      (is (= 4 (get-in ops [1 :props :cursor-position])))))

  (testing "Backspace with markup pairs"
    (let [db (f/make-db {"a" {:type :block :props {:text "****"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :delete-with-pair-check
                                                  :block-id "a"
                                                  :cursor-pos 2})]
      (is (= "" (get-in ops [0 :props :text]))))))

(deftest context-aware-enter-test
  (testing "Enter inside bold exits markup first"
    (let [db (f/make-db {"a" {:type :block :props {:text "**hello world**"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :context-aware-enter
                                                  :block-id "a"
                                                  :cursor-pos 5})]
      ;; Should move cursor to after **, not split
      (is (= 15 (get-in ops [0 :props :cursor-position])))))

  (testing "Enter in code block inserts newline"
    (let [db (f/make-db {"a" {:type :block :props {:text "```\ncode\n```"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :context-aware-enter
                                                  :block-id "a"
                                                  :cursor-pos 8})]
      ;; Should insert \n, not create new block
      (is (= "```\ncode\n\n```" (get-in ops [0 :props :text])))
      (is (= 9 (get-in ops [1 :props :cursor-position])))))

  (testing "Enter on empty list unformats"
    (let [db (f/make-db {"a" {:type :block :props {:text "1. "}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :context-aware-enter
                                                  :block-id "a"
                                                  :cursor-pos 3})]
      (is (= "" (get-in ops [0 :props :text])))
      (is (= 1 (count ops)))))  ; No new block created

  (testing "Enter on numbered list increments"
    (let [db (f/make-db {"a" {:type :block :props {:text "1. First"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :context-aware-enter
                                                  :block-id "a"
                                                  :cursor-pos 9})]
      (is (= "1. First" (get-in ops [0 :props :text])))
      (is (string/starts-with? (get-in ops [1 :props :text]) "2. "))))

  (testing "Enter on checkbox continues pattern"
    (let [db (f/make-db {"a" {:type :block :props {:text "- [ ] Task"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :context-aware-enter
                                                  :block-id "a"
                                                  :cursor-pos 11})]
      (is (string/starts-with? (get-in ops [1 :props :text]) "- [ ] ")))))

(deftest delete-forward-test
  (testing "Delete at end merges with first child"
    (let [db (-> (f/make-db {"a" {:type :block :props {:text "Parent"}}
                             "child" {:type :block :props {:text "Child"}}}
                            {"a" ["child"]})
                 (tx/interpret [])
                 :db)
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "a"
                                                  :cursor-pos 6
                                                  :has-selection? false})]
      (is (= "ParentChild" (get-in ops [0 :props :text])))
      ;; Cursor stays at original position
      (is (= 6 (get-in ops [2 :props :cursor-position])))))

  (testing "Delete at end merges with sibling if no children"
    (let [db (-> (f/make-db {"a" {:type :block :props {:text "First"}}
                             "b" {:type :block :props {:text "Second"}}}
                            {})
                 (assoc-in [:derived :next-id-of "a"] "b")
                 (tx/interpret [])
                 :db)
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "a"
                                                  :cursor-pos 5
                                                  :has-selection? false})]
      (is (= "FirstSecond" (get-in ops [0 :props :text])))
      (is (= 5 (get-in ops [2 :props :cursor-position])))))

  (testing "Delete in middle deletes next char"
    (let [db (f/make-db {"a" {:type :block :props {:text "hello"}}} {})
          {:keys [ops]} (intent/apply-intent db {:type :delete-forward
                                                  :block-id "a"
                                                  :cursor-pos 2
                                                  :has-selection? false})]
      (is (= "helo" (get-in ops [0 :props :text])))
      (is (= 2 (get-in ops [1 :props :cursor-position]))))))

(deftest multi-byte-char-test
  (testing "Grapheme length for emoji"
    (is (= 2 (text/grapheme-length-at "Hi😀" 2))))

  (testing "Count graphemes correctly"
    (is (= 3 (text/count-graphemes "Hi😀")))
    (is (= 8 (text/count-graphemes "Hello😀世界"))))

  (testing "Cursor position to grapheme index"
    (is (= 2 (text/cursor-pos-to-grapheme-index "Hi😀there" 4)))))
```

### Running Unit Tests

```bash
# Run all unit tests
bb test

# Run specific test namespace
bb test plugins.context-test

# Watch mode
bb test:watch

# In REPL
(require '[clojure.test :refer [run-tests]])
(run-tests 'plugins.context-test)
```

---

## Level 2: Component Tests (Replicant + DOM) - 10% Coverage

### What We Test

**Component behavior with simulated DOM:**
- Key events trigger correct intents
- Cursor position is read/set correctly
- Selection state is checked correctly
- contenteditable updates trigger handlers

### Test Structure

```clojure
;; test/components/block_editing_test.cljs
(ns components.block-editing-test
  "Component-level tests with Replicant rendering and DOM simulation.

   These tests mount actual components, simulate user input,
   and verify DOM state changes."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [components.block :as block]
            [replicant.dom :as r]
            [kernel.db :as db]
            [kernel.intent :as intent]))

;; Test fixture - mount/unmount component
(def ^:dynamic *container* nil)

(use-fixtures :each
  (fn [test-fn]
    (let [container (js/document.createElement "div")]
      (js/document.body.appendChild container)
      (binding [*container* container]
        (test-fn))
      (js/document.body.removeChild container))))

(defn mount-block-editor
  "Mount block editor component for testing.

   Returns: {:component component-fn
             :container DOM element
             :get-text (fn [] current text)
             :get-cursor (fn [] cursor position)
             :set-cursor (fn [pos] set cursor)
             :type-char (fn [char] simulate typing)
             :press-key (fn [key-name] simulate key press)}"
  [db block-id]
  (let [intent-log (atom [])
        on-intent (fn [intent]
                    (swap! intent-log conj intent)
                    ;; Apply intent to get new DB
                    (let [{:keys [ops]} (intent/apply-intent @db-atom intent)]
                      (when (seq ops)
                        (swap! db-atom #(kernel.transaction/interpret % ops)))))
        db-atom (atom db)
        component (block/Block {:db @db-atom
                               :block-id block-id
                               :depth 0
                               :on-intent on-intent})
        _ (r/render component *container*)]
    {:component component
     :container *container*
     :db-atom db-atom
     :intent-log intent-log
     :get-text (fn []
                 (.-textContent (.querySelector *container* ".content-edit")))
     :get-cursor (fn []
                   (let [sel (.getSelection js/window)]
                     (when (> (.-rangeCount sel) 0)
                       (.-anchorOffset sel))))
     :set-cursor (fn [pos]
                   (let [editable (.querySelector *container* ".content-edit")
                         text-node (.-firstChild editable)
                         range (.createRange js/document)
                         sel (.getSelection js/window)]
                     (.setStart range text-node pos)
                     (.setEnd range text-node pos)
                     (.removeAllRanges sel)
                     (.addRange sel range)))
     :type-char (fn [char]
                  (let [editable (.querySelector *container* ".content-edit")]
                    (.dispatchEvent editable
                      (js/KeyboardEvent. "keydown"
                        #js {:key char :bubbles true}))
                    ;; Simulate input event
                    (let [text (str (.-textContent editable) char)]
                      (set! (.-textContent editable) text))
                    (.dispatchEvent editable
                      (js/Event. "input" #js {:bubbles true}))))
     :press-key (fn [key-name & {:keys [shift ctrl meta alt]}]
                  (let [editable (.querySelector *container* ".content-edit")]
                    (.dispatchEvent editable
                      (js/KeyboardEvent. "keydown"
                        #js {:key key-name
                             :shiftKey (boolean shift)
                             :ctrlKey (boolean ctrl)
                             :metaKey (boolean meta)
                             :altKey (boolean alt)
                             :bubbles true}))))}))

(deftest paired-char-auto-close-test
  (testing "Typing [ auto-closes to []"
    (let [db (fixtures/make-db {"a" {:type :block :props {:text ""}}} {})
          editor (mount-block-editor db "a")]
      ;; Type "["
      ((:type-char editor) "[")
      ;; Check text is "[]"
      (is (= "[]" ((:get-text editor))))
      ;; Check cursor is between brackets
      (is (= 1 ((:get-cursor editor))))))

  (testing "Typing ] when next is ] skips over"
    (let [db (fixtures/make-db {"a" {:type :block :props {:text "[]"}}} {})
          editor (mount-block-editor db "a")]
      ;; Position cursor between brackets
      ((:set-cursor editor) 1)
      ;; Type "]"
      ((:type-char editor) "]")
      ;; Text should be unchanged
      (is (= "[]" ((:get-text editor))))
      ;; Cursor should be after ]
      (is (= 2 ((:get-cursor editor)))))))

(deftest backspace-paired-deletion-test
  (testing "Backspace after [ deletes both brackets"
    (let [db (fixtures/make-db {"a" {:type :block :props {:text "[]"}}} {})
          editor (mount-block-editor db "a")]
      ((:set-cursor editor) 1)
      ((:press-key editor) "Backspace")
      (is (= "" ((:get-text editor))))
      (is (= 0 ((:get-cursor editor)))))))

(deftest enter-in-code-block-test
  (testing "Enter inside code block inserts newline (doesn't create block)"
    (let [db (fixtures/make-db {"a" {:type :block :props {:text "```\ncode\n```"}}} {})
          editor (mount-block-editor db "a")]
      ((:set-cursor editor) 8)  ; After "code"
      ((:press-key editor) "Enter")
      ;; Should insert newline
      (is (= "```\ncode\n\n```" ((:get-text editor))))
      ;; No new block created
      (is (= 1 (count @(:intent-log editor)))))))

(deftest arrow-with-selection-test
  (testing "Arrow up with text selection collapses to start"
    (let [db (fixtures/make-db {"a" {:type :block :props {:text "hello world"}}} {})
          editor (mount-block-editor db "a")]
      ;; Create text selection (select "world")
      (let [editable (.querySelector *container* ".content-edit")
            text-node (.-firstChild editable)
            range (.createRange js/document)
            sel (.getSelection js/window)]
        (.setStart range text-node 6)
        (.setEnd range text-node 11)
        (.removeAllRanges sel)
        (.addRange sel range))
      ;; Press up arrow
      ((:press-key editor) "ArrowUp")
      ;; Selection should collapse to start
      (is (= 6 ((:get-cursor editor))))
      ;; Should NOT navigate to previous block
      (is (empty? (filter #(= (:type %) :navigate-with-cursor-memory)
                         @(:intent-log editor)))))))
```

### Running Component Tests

```bash
# Run in browser environment (needs DOM)
npx shadow-cljs compile test-browser
open test.html

# Or use Karma/similar test runner
npm run test:component
```

---

## Level 3: Browser E2E Tests (Playwright) - 5% Coverage

### What We Test

**Critical user paths that require real browser:**
- Cursor memory navigation (font rendering matters)
- Multi-line block boundary detection
- Paste behavior (browser-dependent)
- IME composition (CJK input)
- Cross-browser compatibility

### Setup

```bash
# Install Playwright (already in package.json)
npm install

# Initialize Playwright config
npx playwright install
```

**File:** `playwright.config.js`

```javascript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30000,
  expect: {
    timeout: 5000
  },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:8080',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
  ],

  webServer: {
    command: 'bb dev',
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
  },
});
```

### Test Structure

**File:** `e2e/text-editing.spec.js`

```javascript
import { test, expect } from '@playwright/test';

// Helper functions
async function getEditableText(page) {
  return await page.locator('.content-edit').textContent();
}

async function getCursorPosition(page) {
  return await page.evaluate(() => {
    const sel = window.getSelection();
    return sel.anchorOffset;
  });
}

async function setCursorPosition(page, pos) {
  await page.evaluate((pos) => {
    const editable = document.querySelector('.content-edit');
    const textNode = editable.firstChild;
    const range = document.createRange();
    const sel = window.getSelection();
    range.setStart(textNode, pos);
    range.setEnd(textNode, pos);
    sel.removeAllRanges();
    sel.addRange(range);
  }, pos);
}

async function createBlocks(page, texts) {
  await page.evaluate((texts) => {
    // Dispatch intent to create blocks via global API
    window.evoAPI.dispatch({
      type: 'batch-create-blocks',
      texts: texts
    });
  }, texts);
}

// Tests
test.describe('Paired Character Insertion', () => {
  test('typing [ auto-closes to []', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await page.keyboard.type('[');

    expect(await getEditableText(page)).toBe('[]');
    expect(await getCursorPosition(page)).toBe(1);
  });

  test('typing ] when next char is ] skips over', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['[]']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await setCursorPosition(page, 1);
    await page.keyboard.type(']');

    expect(await getEditableText(page)).toBe('[]');
    expect(await getCursorPosition(page)).toBe(2);
  });

  test('backspace after [ deletes both brackets', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['[]']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await setCursorPosition(page, 1);
    await page.keyboard.press('Backspace');

    expect(await getEditableText(page)).toBe('');
    expect(await getCursorPosition(page)).toBe(0);
  });
});

test.describe('Cursor Memory Navigation', () => {
  test('arrow down preserves cursor column', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['hello world', 'foo bar baz']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await setCursorPosition(page, 6); // After "hello "

    await page.keyboard.press('ArrowDown');

    // Should be editing second block
    const activeBlock = await page.evaluate(() => {
      return document.querySelector('.content-edit').closest('[data-block-id]')
        .getAttribute('data-block-id');
    });
    expect(activeBlock).toBe('block-2');

    // Cursor should be at column 6 (after "foo ba")
    expect(await getCursorPosition(page)).toBe(6);
  });

  test('arrow down to shorter block goes to end', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['hello world', 'hi']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await setCursorPosition(page, 8);

    await page.keyboard.press('ArrowDown');

    // Cursor should be at end of "hi"
    expect(await getCursorPosition(page)).toBe(2);
  });
});

test.describe('Context-Aware Enter', () => {
  test('enter on numbered list increments', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['1. First item']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await page.keyboard.press('End'); // Move to end
    await page.keyboard.press('Enter');

    // Should create new block starting with "2. "
    const blocks = await page.locator('.content-edit').all();
    expect(blocks.length).toBe(2);
    expect(await blocks[1].textContent()).toMatch(/^2\. /);
  });

  test('enter on empty list unformats', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['1. ']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await page.keyboard.press('End');
    await page.keyboard.press('Enter');

    // Should clear the marker, not create new block
    const blocks = await page.locator('.content-edit').all();
    expect(blocks.length).toBe(1);
    expect(await blocks[0].textContent()).toBe('');
  });

  test('enter inside code block inserts newline', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['```\\ncode\\n```']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await setCursorPosition(page, 8); // After "code"
    await page.keyboard.press('Enter');

    // Should insert newline, not create new block
    const blocks = await page.locator('.content-edit').all();
    expect(blocks.length).toBe(1);
    expect(await blocks[0].textContent()).toContain('\\n\\n');
  });
});

test.describe('Delete Forward Merge', () => {
  test('delete at end merges with first child', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, [
      { id: 'a', text: 'Parent', children: [
        { id: 'child', text: 'Child' }
      ]},
      { id: 'b', text: 'Sibling' }
    ]);

    // Edit parent block
    const parent = page.locator('[data-block-id="a"] .content-edit');
    await parent.click();
    await page.keyboard.press('End');
    await page.keyboard.press('Delete');

    // Should merge with child
    expect(await parent.textContent()).toBe('ParentChild');

    // Child should be gone
    const child = page.locator('[data-block-id="child"]');
    await expect(child).toHaveCount(0);
  });

  test('delete at end merges with sibling if no children', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['First', 'Second']);

    const first = page.locator('.content-edit').first();
    await first.click();
    await page.keyboard.press('End');
    await page.keyboard.press('Delete');

    expect(await first.textContent()).toBe('FirstSecond');
  });
});

test.describe('Boundary Detection', () => {
  test('first row detection with wrapped text', async ({ page }) => {
    await page.goto('/');
    // Long text that wraps
    const longText = 'This is a very long line that will definitely wrap to multiple lines when rendered';
    await createBlocks(page, [longText]);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await setCursorPosition(page, 0); // Start

    // Press Down - should move within block (to second line)
    await page.keyboard.press('ArrowDown');

    // Should still be in same block
    const blocks = await page.locator('.content-edit').all();
    expect(blocks.length).toBe(1);

    // Cursor should have moved down (not to start)
    const cursorPos = await getCursorPosition(page);
    expect(cursorPos).toBeGreaterThan(0);
  });
});

test.describe('Selection Collapse', () => {
  test('arrow up with selection collapses to start', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['hello world']);

    const editable = page.locator('.content-edit').first();
    await editable.click();

    // Select "world" (chars 6-11)
    await page.evaluate(() => {
      const editable = document.querySelector('.content-edit');
      const textNode = editable.firstChild;
      const range = document.createRange();
      const sel = window.getSelection();
      range.setStart(textNode, 6);
      range.setEnd(textNode, 11);
      sel.removeAllRanges();
      sel.addRange(range);
    });

    await page.keyboard.press('ArrowUp');

    // Selection should collapse
    const isCollapsed = await page.evaluate(() => {
      const sel = window.getSelection();
      return sel.isCollapsed;
    });
    expect(isCollapsed).toBe(true);

    // Cursor should be at start of selection
    expect(await getCursorPosition(page)).toBe(6);
  });
});

test.describe('Multi-byte Characters', () => {
  test('delete emoji deletes entire emoji', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['Hi😀there']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await setCursorPosition(page, 2); // After "Hi"
    await page.keyboard.press('Delete');

    expect(await getEditableText(page)).toBe('Hithere');
  });

  test('backspace before emoji deletes entire emoji', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['Hi😀there']);

    const editable = page.locator('.content-edit').first();
    await editable.click();
    await setCursorPosition(page, 4); // After emoji (2 UTF-16 units)
    await page.keyboard.press('Backspace');

    expect(await getEditableText(page)).toBe('Hithere');
  });
});
```

### Running E2E Tests

```bash
# Run all E2E tests
npx playwright test

# Run specific test file
npx playwright test e2e/text-editing.spec.js

# Run in headed mode (see browser)
npx playwright test --headed

# Run specific browser
npx playwright test --project=chromium

# Debug mode (step through)
npx playwright test --debug

# Generate test report
npx playwright show-report
```

---

## Test Organization

```
test/
├── fixtures.cljc                    # Generic test fixtures
├── test_util.cljc                   # Test utilities
│
├── plugins/                         # Unit tests for plugins
│   ├── context_test.cljc           # Context detection
│   ├── smart_editing_test.cljc     # Paired chars, enter
│   ├── editing_test.cljc           # Delete forward, merge
│   └── navigation_test.cljc        # Cursor memory
│
├── components/                      # Component tests (Replicant)
│   ├── block_editing_test.cljs     # Block component behaviors
│   └── keyboard_test.cljs          # Keyboard event handling
│
├── integration/                     # Integration tests
│   └── editing_flow_test.cljc      # Multi-step editing flows
│
└── utils/
    └── text_test.cljc              # Multi-byte utilities

e2e/                                 # Playwright E2E tests
├── text-editing.spec.js            # Text editing behaviors
├── navigation.spec.js              # Cursor memory navigation
└── helpers.js                       # Shared test helpers
```

---

## Coverage Requirements

### Minimum Coverage Before Release

**Unit Tests (Intent Layer):**
- ✅ 100% of intent handlers have tests
- ✅ All edge cases covered (empty text, no siblings, etc.)
- ✅ All context detection functions tested
- ✅ Multi-byte character utilities tested

**Component Tests:**
- ✅ All keyboard handlers have tests
- ✅ Paired character insertion/deletion
- ✅ Selection collapse behavior
- ✅ contenteditable edge cases

**E2E Tests:**
- ✅ 3 critical paths (cursor memory, enter continuation, boundary detection)
- ✅ Cross-browser compatibility (Chromium, Firefox, WebKit)

---

## Continuous Integration

### GitHub Actions Workflow

**File:** `.github/workflows/test.yml`

```yaml
name: Test

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: babashka/setup-babashka@v1
      - name: Run unit tests
        run: bb test
        timeout-minutes: 5

  component-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
        with:
          node-version: 18
      - name: Install dependencies
        run: npm ci
      - name: Run component tests
        run: npm run test:component
        timeout-minutes: 10

  e2e-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
        with:
          node-version: 18
      - name: Install dependencies
        run: npm ci
      - name: Install Playwright browsers
        run: npx playwright install --with-deps
      - name: Run E2E tests
        run: npx playwright test
        timeout-minutes: 15
      - name: Upload test report
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: playwright-report
          path: playwright-report/
```

---

## Debugging Strategies

### Unit Test Failures

```clojure
;; Enable verbose output
(require '[clojure.test :as t])
(binding [t/*stack-trace-depth* 100]
  (t/run-tests 'plugins.context-test))

;; Inspect intent ops
(let [db (fixtures/make-db ...)
      {:keys [ops issues]} (intent/apply-intent db {...})]
  (clojure.pprint/pprint ops)
  (clojure.pprint/pprint issues))

;; REPL-driven debugging
(def test-db (fixtures/make-db ...))
(def result (intent/apply-intent test-db {:type ...}))
(:ops result)
(:issues result)
```

### Component Test Failures

```javascript
// Add console logging
console.log('Text:', await getEditableText(page));
console.log('Cursor:', await getCursorPosition(page));

// Take screenshot
await page.screenshot({ path: 'debug.png' });

// Pause execution
await page.pause(); // Opens Playwright Inspector
```

### E2E Test Failures

```bash
# Run with trace
npx playwright test --trace on

# View trace
npx playwright show-trace trace.zip

# Run single test with headed browser
npx playwright test e2e/text-editing.spec.js:10 --headed --debug
```

---

## Test Writing Guidelines

### Unit Tests

**DO:**
- Test pure functions with many input variations
- Use property-based testing for complex logic
- Test edge cases (empty, nil, boundary values)
- Assert on exact ops shape and DB state

**DON'T:**
- Mock internal functions (test behavior, not implementation)
- Test framework code (Replicant, Malli)
- Duplicate tests (one test per behavior)

### Component Tests

**DO:**
- Test user-visible behavior (what happens in DOM)
- Simulate realistic user input (typing, key presses)
- Verify intent dispatch (check intent log)

**DON'T:**
- Test implementation details (component internals)
- Rely on timing (use proper waits)
- Test CSS/styling (visual regression tests elsewhere)

### E2E Tests

**DO:**
- Test critical user journeys
- Verify cross-browser compatibility
- Take screenshots on failure
- Keep tests independent (can run in any order)

**DON'T:**
- Test every edge case (unit tests cover that)
- Make tests brittle (use data attributes, not CSS classes)
- Ignore flaky tests (fix or remove)

---

## Performance Testing

### Benchmarking Intent Performance

```clojure
;; test/bench/intent_bench.cljc
(ns bench.intent-bench
  (:require [criterium.core :as crit]
            [kernel.intent :as intent]
            [fixtures :as f]))

(defn bench-context-detection []
  (let [text "Hello **world** test ((ref)) more [[page]] text"]
    (crit/quick-bench
      (context/context-at-cursor text 10))))

(defn bench-paired-deletion []
  (let [db (f/make-db {"a" {:type :block :props {:text "[]"}}} {})]
    (crit/quick-bench
      (intent/apply-intent db {:type :delete-with-pair-check
                              :block-id "a"
                              :cursor-pos 1}))))

;; Run: bb bench
```

### Expected Performance

- Context detection: < 1ms
- Intent → ops: < 5ms
- Transaction apply: < 10ms (small graphs)
- Component render: < 16ms (60fps)

---

## Summary

### Test Allocation

| Test Level | Count | Coverage | Time | When to Run |
|-----------|-------|----------|------|-------------|
| Unit (Data) | 200-500 | 85% | < 1 min | Every commit |
| Component (DOM) | 50-100 | 10% | 2-5 min | Pre-push |
| E2E (Browser) | 10-20 | 5% | 5-15 min | Pre-release |

### Success Criteria

✅ **All unit tests pass** - Pure logic is correct
✅ **Component tests pass** - DOM integration works
✅ **3 E2E tests pass** - Critical paths verified
✅ **Manual testing passes** - 5-minute smoke test feels right
✅ **Cross-browser compatibility** - Works in Chrome/Firefox/Safari

### When to Write Each Type

**Write unit tests:**
- First, during implementation
- For all intent handlers
- For all utility functions
- For edge cases found in testing

**Write component tests:**
- When unit tests can't verify DOM behavior
- For keyboard event handling
- For selection/cursor manipulation
- For contenteditable quirks

**Write E2E tests:**
- Only after manual testing finds issues
- For critical user paths
- For cross-browser compatibility
- For integration scenarios

**Don't write tests:**
- For trivial getters/setters
- For framework code
- Speculatively (write when needed)

---

## Getting Started

### 1. Run Existing Tests
```bash
bb test
```

### 2. Add Unit Tests for New Feature
```clojure
;; test/plugins/context_test.cljc
(deftest my-new-feature-test
  (testing "behavior description"
    (is (= expected actual))))
```

### 3. Verify in Browser
```bash
bb dev
open http://localhost:8080
# Manual testing: 5 minutes
```

### 4. Add E2E Test if Needed
```javascript
// e2e/text-editing.spec.js
test('my new feature', async ({ page }) => {
  // ...
});
```

### 5. Run Full Suite
```bash
bb test && npm run test:component && npx playwright test
```

---

## Conclusion

This strategy provides **comprehensive coverage** while keeping tests **fast and maintainable**:

- **85% unit tests** - Fast, deterministic, easy to write
- **10% component tests** - Verify DOM integration
- **5% E2E tests** - Critical paths only

**Total test time:** < 20 minutes for full suite
**Confidence level:** 99%+ that editing behaviors match Logseq

**Philosophy:** Test behaviors, not implementation. Keep tests fast. Only add E2E tests for bugs found in manual testing.
