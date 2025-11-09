# Browser Testing Tooling for AI-Driven Development

**Status:** Proposed  
**Target:** Catch browser-specific bugs before manual testing  
**Priority:** Critical (prevents 80% of AI-agent iteration loops)

**Related Specs:**
- [DEV_TOOLING_UPGRADE.md](./DEV_TOOLING_UPGRADE.md) - Backend/logic testing improvements

---

## Executive Summary

**Problem:** Unit tests pass but browser has critical bugs.

**AI Agent Blind Spots:**
- ❌ Cannot see colors, layout, spacing
- ❌ Cannot detect accessibility violations  
- ❌ Cannot test cross-browser differences
- ❌ Cannot "feel" cursor jumps or timing issues

**Root Cause:** AI agents cannot see the browser. Manual testing required to catch:
- DOM lifecycle bugs (atoms resetting, render timing)
- Browser API quirks (cursor positioning, TreeWalker)
- Visual issues (text duplication, CSS mismatches)
- User interaction flows (typing, navigation)
- A11y violations (color contrast, ARIA, keyboard nav)

**Solution:** Automated browser E2E testing with text-based output that AI can parse.

**Expected Impact:**
- Catch 95% of browser bugs before manual testing
- Reduce "why does this fail in browser?" iterations by 80%
- All failures include DOM state, screenshots, cursor positions
- Tests run automatically on code changes
- Zero a11y violations shipped to production

---

## Lessons from Real Bugs

### Session: Logseq-Feel Navigation (2025-11-09)

**What unit tests caught:**
- ✅ Navigation logic (cursor column calculation)
- ✅ Intent validation
- ✅ Operation generation

**What unit tests MISSED:**
1. **Cursor jumping while typing** (7 iterations to fix)
   - Root cause: Component-local atom recreated on re-render
   - Solution: DOM node property tracking
   
2. **Text duplication**
   - Root cause: Extra `[:span]` wrapper in render
   - Invisible to unit tests
   
3. **Duplicate navigation handlers**
   - Both block component AND global handler firing
   - No test for "only one navigation event"
   
4. **Mock-text positioning**
   - Element at `top: 0px` instead of matching editing block
   - No test for element positioning
   
5. **CSS wrapping mismatch**
   - contentEditable vs mock-text had different `word-wrap`
   - Visual bug, not testable without browser

**Cost:** 20+ manual test cycles, 3+ hours debugging

**What would have prevented it:** Automated browser tests with visual verification

---

## Testing Architecture

### Four-Layer Testing Strategy

```
┌─────────────────────────────────────────┐
│ Layer 4: Cross-Browser Testing         │ ← Safari/Firefox/Mobile
├─────────────────────────────────────────┤
│ Layer 3: Visual & Accessibility        │ ← Percy, axe-core, computed styles
├─────────────────────────────────────────┤
│ Layer 2: Interaction Flows             │ ← Type, click, navigate, drag
├─────────────────────────────────────────┤
│ Layer 1: DOM State Verification        │ ← Cursor, selection, focus
└─────────────────────────────────────────┘
```

### Tool Selection

**Primary:** Playwright (best ClojureScript integration via Node)

**Why Playwright:**
- Native Chrome DevTools Protocol access (already using via MCP)
- Built-in screenshot/video capture
- Text-based assertions (AI-friendly)
- Watch mode for TDD
- Works with shadow-cljs hot reload
- Visual regression (Percy integration)
- A11y testing (axe-core integration)

**Alternative:** Etaoin (pure Clojure, WebDriver-based)
- Considered but: slower, more verbose, less AI-friendly output

---

## Phase 1: Core Setup (20 min)

### A. Install Playwright

```bash
cd /Users/alien/Projects/evo

# Add to package.json
npm install --save-dev @playwright/test

# Visual regression
npm install --save-dev @percy/playwright

# Accessibility testing
npm install --save-dev @axe-core/playwright

# Install browsers
npx playwright install chromium firefox webkit
```

### B. Configure Playwright

```javascript
// playwright.config.js (new file)
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './test/e2e',
  
  // Run tests in parallel
  fullyParallel: true,
  
  // Fail fast on CI, retry locally
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  
  // Structured output for AI parsing
  reporter: [
    ['list'],  // Console output
    ['json', { outputFile: 'test-results/e2e-results.json' }],
    ['html', { open: 'never' }]  // HTML report for manual review
  ],
  
  use: {
    // Base URL for tests
    baseURL: 'http://localhost:8080',
    
    // Capture on failure
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
    
    // Browser viewport
    viewport: { width: 1280, height: 720 }
  },
  
  // Cross-browser testing
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] }
    },
    {
      name: 'webkit',  // Safari engine
      use: { ...devices['Desktop Safari'] }
    },
    {
      name: 'Mobile Safari',
      use: { ...devices['iPhone 13'] }
    }
  ],
  
  // Dev server
  webServer: {
    command: 'bb dev',
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
    timeout: 120000
  }
});
```

### C. Add to bb.edn

```clojure
;; bb.edn - add E2E tasks
e2e {:doc "Run Playwright E2E tests"
     :task (shell "npx playwright test")}

e2e-watch {:doc "Run E2E tests in watch mode (UI)"
           :task (shell "npx playwright test --ui")}

e2e-debug {:doc "Run E2E tests with debugger"
           :task (shell "npx playwright test --debug")}

e2e-headed {:doc "Run E2E tests with visible browser"
            :task (shell "npx playwright test --headed")}

e2e-report {:doc "Open last E2E test report"
            :task (shell "npx playwright show-report")}

e2e-visual {:doc "Run visual regression tests with Percy"
            :task (shell "npx percy exec -- npx playwright test visual")}

e2e-a11y {:doc "Run accessibility tests only"
          :task (shell "npx playwright test accessibility")}
```

---

## Phase 2: Layer 1 - DOM State Testing (30 min)

### A. Cursor Position Utilities

```javascript
// test/e2e/helpers/cursor.js (new file)
/**
 * Get current cursor position in contenteditable element.
 * Returns {offset, text, elementId} for AI-parseable assertions.
 */
export async function getCursorPosition(page) {
  return await page.evaluate(() => {
    const sel = window.getSelection();
    const elem = document.activeElement;
    
    if (!elem || !sel || sel.rangeCount === 0) {
      return { offset: null, text: null, elementId: null };
    }
    
    return {
      offset: sel.focusOffset,
      text: elem.textContent,
      elementId: elem.id || elem.getAttribute('data-block-id'),
      nodeType: sel.focusNode?.nodeType,
      isCollapsed: sel.isCollapsed
    };
  });
}

/**
 * Set cursor position in specific block.
 */
export async function setCursorPosition(page, blockId, offset) {
  await page.evaluate(({ blockId, offset }) => {
    const elem = document.querySelector(`[data-block-id="${blockId}"]`);
    if (!elem || !elem.firstChild) return false;
    
    const range = document.createRange();
    const sel = window.getSelection();
    const textNode = elem.firstChild;
    
    range.setStart(textNode, Math.min(offset, textNode.length));
    range.setEnd(textNode, Math.min(offset, textNode.length));
    sel.removeAllRanges();
    sel.addRange(range);
    
    return true;
  }, { blockId, offset });
}

/**
 * Assert cursor at expected position.
 * Prints structured diff on failure (AI-readable).
 */
export async function expectCursorAt(page, expectedOffset, expectedText) {
  const cursor = await getCursorPosition(page);
  
  if (cursor.offset !== expectedOffset) {
    console.log('\n=== CURSOR POSITION MISMATCH ===');
    console.log('Expected offset:', expectedOffset);
    console.log('Actual offset:', cursor.offset);
    console.log('Text:', cursor.text);
    console.log('Element:', cursor.elementId);
  }
  
  expect(cursor.offset).toBe(expectedOffset);
  
  if (expectedText !== undefined) {
    expect(cursor.text).toContain(expectedText);
  }
}
```

### B. Block Manipulation Helpers

```javascript
// test/e2e/helpers/blocks.js (new file)
/**
 * Get all blocks on page with their text content.
 */
export async function getAllBlocks(page) {
  return await page.evaluate(() => {
    const blocks = document.querySelectorAll('[contenteditable="true"]');
    return Array.from(blocks).map((block, idx) => ({
      index: idx,
      id: block.id || block.getAttribute('data-block-id'),
      text: block.textContent,
      isFocused: block === document.activeElement
    }));
  });
}

/**
 * Type text character-by-character and verify cursor advances.
 */
export async function typeAndVerifyCursor(page, text) {
  const results = [];
  
  for (const char of text) {
    const before = await getCursorPosition(page);
    await page.keyboard.type(char);
    await page.waitForTimeout(50);  // Wait for state update
    const after = await getCursorPosition(page);
    
    results.push({
      char,
      offsetBefore: before.offset,
      offsetAfter: after.offset,
      advanced: after.offset === before.offset + 1
    });
    
    // Assert cursor advanced
    if (after.offset !== before.offset + 1) {
      console.log('\n=== CURSOR JUMP DETECTED ===');
      console.log('Character:', char);
      console.log('Before:', before);
      console.log('After:', after);
      throw new Error(`Cursor jumped: expected ${before.offset + 1}, got ${after.offset}`);
    }
  }
  
  return results;
}
```

---

## Phase 3: Layer 2 - Interaction Testing (40 min)

### A. Critical Typing Tests

```javascript
// test/e2e/editing.spec.js (new file)
import { test, expect } from '@playwright/test';
import { getCursorPosition, typeAndVerifyCursor } from './helpers/cursor.js';

test.describe('Text Editing', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[contenteditable="true"]');
  });
  
  test('typing advances cursor sequentially', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    
    // Type text and verify cursor advances
    const results = await typeAndVerifyCursor(page, 'TESTING');
    
    // Assert all characters advanced cursor correctly
    expect(results).toEqual([
      { char: 'T', offsetBefore: 0, offsetAfter: 1, advanced: true },
      { char: 'E', offsetBefore: 1, offsetAfter: 2, advanced: true },
      { char: 'S', offsetBefore: 2, offsetAfter: 3, advanced: true },
      { char: 'T', offsetBefore: 3, offsetAfter: 4, advanced: true },
      { char: 'I', offsetBefore: 4, offsetAfter: 5, advanced: true },
      { char: 'N', offsetBefore: 5, offsetAfter: 6, advanced: true },
      { char: 'G', offsetBefore: 6, offsetAfter: 7, advanced: true }
    ]);
  });
  
  test('REGRESSION: cursor never jumps to start while typing', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    
    // Type longer text
    const text = 'The quick brown fox jumps over the lazy dog';
    
    for (let i = 0; i < text.length; i++) {
      const before = await getCursorPosition(page);
      await page.keyboard.type(text[i]);
      await page.waitForTimeout(50);
      const after = await getCursorPosition(page);
      
      // Critical assertion: cursor should NEVER be at 0 after first character
      if (i > 0 && after.offset === 0) {
        throw new Error(`REGRESSION: Cursor jumped to start at character ${i} ('${text[i]}')`);
      }
      
      // Cursor should advance by 1
      expect(after.offset).toBe(before.offset + 1);
    }
  });
  
  test('typing after navigation preserves position', async ({ page }) => {
    // Create two blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('First block');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Second block');
    
    // Navigate up
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);
    
    // Verify we're in first block
    const cursor = await getCursorPosition(page);
    expect(cursor.text).toContain('First');
    
    // Type and verify cursor doesn't jump
    const initialOffset = cursor.offset;
    await page.keyboard.type('X');
    await page.waitForTimeout(50);
    
    const afterTyping = await getCursorPosition(page);
    expect(afterTyping.offset).toBe(initialOffset + 1);
  });
});
```

### B. Navigation Tests

```javascript
// test/e2e/navigation.spec.js (new file)
import { test, expect } from '@playwright/test';
import { getCursorPosition, setCursorPosition } from './helpers/cursor.js';
import { getAllBlocks } from './helpers/blocks.js';

test.describe('Block Navigation', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[contenteditable="true"]');
  });
  
  test('arrow down preserves cursor column', async ({ page }) => {
    // Create two blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Hello world this is a long line');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Short line');
    
    // Navigate up to first block
    await page.keyboard.press('ArrowUp');
    
    // Set cursor at position 10
    const blocks = await getAllBlocks(page);
    await setCursorPosition(page, blocks[0].id, 10);
    
    // Navigate down
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);
    
    const cursor = await getCursorPosition(page);
    
    // Cursor should be at same column (or end if shorter)
    expect(cursor.offset).toBe(10);
    expect(cursor.elementId).not.toBe(blocks[0].id);
  });
  
  test('arrow up to shorter block goes to end', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Short');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Very long line with lots of text');
    
    // Set cursor at position 20 in second block
    const blocks = await getAllBlocks(page);
    await setCursorPosition(page, blocks[1].id, 20);
    
    // Navigate up (first block only has 5 chars)
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);
    
    const cursor = await getCursorPosition(page);
    // Should be at end of shorter block
    expect(cursor.offset).toBe(5);
    expect(cursor.text).toBe('Short');
  });
});
```

---

## Phase 4: Layer 3 - Visual & Accessibility Testing (60 min)

### A. Visual Regression with Percy

```javascript
// test/e2e/visual.spec.js (new file)
import { test, expect } from '@playwright/test';
import percySnapshot from '@percy/playwright';

test.describe('Visual Regression', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[contenteditable="true"]');
  });
  
  test('default block state', async ({ page }) => {
    await percySnapshot(page, 'Blocks - Default State');
  });
  
  test('focus state shows blue background', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    
    // Percy captures the #b3d9ff blue background
    await percySnapshot(page, 'Block - Focused (#b3d9ff)');
  });
  
  test('blocks do not have duplicate text', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Single line of text');
    
    // Take screenshot
    await percySnapshot(page, 'Single Block - No Duplication');
    
    // Assert text appears exactly once in DOM
    const blockText = await page.evaluate(() => {
      const block = document.querySelector('[contenteditable="true"]');
      return block.innerHTML;
    });
    
    // Should NOT have nested spans with duplicate text
    const textCount = (blockText.match(/Single line of text/g) || []).length;
    expect(textCount).toBe(1);
  });
  
  test('mock-text element positioned correctly', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Testing mock-text position');
    
    // Get positions
    const positions = await page.evaluate(() => {
      const block = document.querySelector('[contenteditable="true"]');
      const mock = document.getElementById('mock-text');
      
      if (!block || !mock) return null;
      
      const blockRect = block.getBoundingClientRect();
      const mockRect = mock.getBoundingClientRect();
      
      return {
        block: { top: blockRect.top, left: blockRect.left, width: blockRect.width },
        mock: { top: mockRect.top, left: mockRect.left, width: mockRect.width }
      };
    });
    
    // Mock should match block position
    expect(positions.mock.top).toBe(positions.block.top);
    expect(positions.mock.left).toBe(positions.block.left);
    expect(positions.mock.width).toBe(positions.block.width);
  });
});
```

### B. Accessibility Testing

```javascript
// test/e2e/accessibility.spec.js (new file)
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test.describe('Accessibility Compliance', () => {
  test('page meets WCAG AA standards', async ({ page }) => {
    await page.goto('/');
    
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze();
    
    // AI agent can read this failure message:
    // "5 violations found:
    //  - color-contrast: Element has insufficient contrast (3.2:1)
    //  - label: Form element missing accessible name
    //  - focus-visible: Element has outline:none without alternative"
    expect(results.violations).toEqual([]);
  });
  
  test('contenteditable has proper ARIA', async ({ page }) => {
    await page.goto('/');
    await page.click('[contenteditable="true"]');
    
    // Check for required ARIA attributes
    const editable = page.locator('[contenteditable="true"]');
    await expect(editable).toHaveAttribute('role', 'textbox');
    await expect(editable).toHaveAttribute('aria-label');
    await expect(editable).toHaveAttribute('aria-multiline', 'false');
  });
  
  test('keyboard navigation works', async ({ page }) => {
    await page.goto('/');
    
    // Navigate with Tab only (no mouse)
    await page.keyboard.press('Tab');
    await expect(page.locator('[data-block-id]').first()).toBeFocused();
    
    await page.keyboard.press('Tab');
    await expect(page.locator('[data-block-id]').nth(1)).toBeFocused();
  });
  
  test('focus indicator visible', async ({ page }) => {
    await page.goto('/');
    await page.locator('[data-block-id]').first().focus();
    
    // Check focus indicator is NOT outline:none
    const outline = await page.locator('[data-block-id]').first().evaluate(el =>
      window.getComputedStyle(el).outline);
    
    // Should have visible outline or custom focus indicator
    expect(outline).not.toBe('none');
  });
  
  test('color contrast meets WCAG AA', async ({ page }) => {
    await page.goto('/');
    
    const results = await new AxeBuilder({ page })
      .withRules(['color-contrast'])
      .analyze();
    
    expect(results.violations).toEqual([]);
  });
});
```

### C. Computed Styles & Layout

```javascript
// test/e2e/computed-styles.spec.js (new file)
import { test, expect } from '@playwright/test';

test.describe('CSS Computed Values', () => {
  test('block indentation is 20px per depth level', async ({ page }) => {
    await page.goto('/');
    
    // Depth 0
    const depth0 = await page.locator('[data-depth="0"]').first().evaluate(el =>
      window.getComputedStyle(el).marginLeft);
    expect(depth0).toBe('0px');
    
    // Depth 1 (child)
    const depth1 = await page.locator('[data-depth="1"]').first().evaluate(el =>
      window.getComputedStyle(el).marginLeft);
    expect(depth1).toBe('20px');
    
    // Depth 2 (grandchild)
    const depth2 = await page.locator('[data-depth="2"]').first().evaluate(el =>
      window.getComputedStyle(el).marginLeft);
    expect(depth2).toBe('40px');
  });
  
  test('focus background is correct blue (#b3d9ff)', async ({ page }) => {
    await page.goto('/');
    await page.click('[data-block-id]');
    
    const bgColor = await page.locator('[data-block-id]').first().evaluate(el =>
      window.getComputedStyle(el).backgroundColor);
    
    // Assert exact RGB value (hex #b3d9ff = rgb(179, 217, 255))
    expect(bgColor).toBe('rgb(179, 217, 255)');
  });
});

test.describe('Layout Positioning', () => {
  test('blocks do not overlap vertically', async ({ page }) => {
    await page.goto('/');
    
    const block1 = await page.locator('[data-block-id]').first().boundingBox();
    const block2 = await page.locator('[data-block-id]').nth(1).boundingBox();
    
    // Block 2 should be completely below block 1
    expect(block2.y).toBeGreaterThan(block1.y + block1.height);
  });
  
  test('nested blocks aligned correctly', async ({ page }) => {
    await page.goto('/');
    
    const parent = await page.locator('[data-depth="0"]').first().boundingBox();
    const child = await page.locator('[data-depth="1"]').first().boundingBox();
    
    // Child should be indented 20px from parent
    expect(child.x).toBe(parent.x + 20);
  });
});
```

---

## Phase 5: Layer 4 - Cross-Browser Testing (15 min)

Already configured in `playwright.config.js` - just run:

```bash
# Run on all browsers
npx playwright test

# Or specific browser
npx playwright test --project=webkit  # Safari
npx playwright test --project=firefox
npx playwright test --project="Mobile Safari"
```

### What This Catches

✅ **Safari contenteditable quirks**  
✅ **Firefox cursor positioning**  
✅ **Mobile touch event differences**  
✅ **Vendor prefix needs**

---

## CI Integration

### GitHub Actions Workflow

```yaml
# .github/workflows/e2e.yml
name: E2E & Visual Tests

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

jobs:
  e2e-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
      
      - name: Install dependencies
        run: npm ci
      
      - name: Install Playwright browsers
        run: npx playwright install --with-deps chromium firefox webkit
      
      - name: Run E2E tests
        run: bb e2e
      
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: playwright-report
          path: playwright-report/
          
      - name: Upload screenshots
        if: failure()
        uses: actions/upload-artifact@v3
        with:
          name: screenshots
          path: test-results/
  
  visual-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '20'
      
      - name: Install dependencies
        run: npm ci
      
      - name: Run visual regression tests
        run: bb e2e-visual
        env:
          PERCY_TOKEN: ${{ secrets.PERCY_TOKEN }}
  
  a11y-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '20'
      
      - name: Install dependencies
        run: npm ci
      
      - name: Run accessibility tests
        run: bb e2e-a11y
```

---

## Development Workflow

### TDD with E2E Tests

```bash
# Terminal 1: Dev server (hot reload)
bb dev

# Terminal 2: E2E watch mode (UI)
bb e2e-watch

# OR: Run specific test file
npx playwright test editing.spec.js --headed

# Debug failing test
bb e2e-debug
```

### When to Run E2E Tests

**Always:**
- Before committing changes to editing/navigation code
- After modifying contenteditable logic
- When adding new keyboard shortcuts
- When changing cursor positioning
- When updating styles/layout

**Optional:**
- After pure data/logic changes (covered by unit tests)
- After docs-only changes

---

## Reading Test Output

### Success

```
✓ test/e2e/editing.spec.js:10:5 › typing advances cursor sequentially (523ms)
✓ test/e2e/navigation.spec.js:15:5 › arrow down preserves cursor column (412ms)
✓ test/e2e/accessibility.spec.js:8:5 › page meets WCAG AA standards (1.2s)

3 passed (2.1s)
```

### Failure (AI-readable)

```
✗ test/e2e/editing.spec.js:25:5 › cursor never jumps to start while typing

  Error: REGRESSION: Cursor jumped to start at character 15 ('o')
  
  Expected: 15
  Received: 0
  
  DOM state at failure:
    Block ID: block-abc123
    Text: "The quick brown"
    Cursor offset: 0 (expected 15)
    Focus node: #text
  
  Screenshot: test-results/editing-cursor-jump-1.png
  Video: test-results/editing-cursor-jump-1.webm
```

### Accessibility Violations (AI-parseable)

```json
{
  "violations": [{
    "id": "color-contrast",
    "impact": "serious",
    "description": "Elements must have sufficient color contrast",
    "nodes": [{
      "html": "<span style=\"background:#e6f2ff;color:#999\">Selected</span>",
      "target": [".block[data-block-id='proj-2']"],
      "failureSummary": "Fix any of the following:\n  Element has insufficient contrast (2.8:1). Expected 4.5:1"
    }]
  }]
}
```

Agent can:
1. Read the violation
2. Understand the issue (contrast 2.8:1 < 4.5:1)
3. Fix it (change color to meet ratio)
4. Re-run test to verify

**No human visual inspection needed.**

---

## Cost Analysis

### Percy (Visual Regression)
- **Free tier:** 5,000 snapshots/month
- **Your usage estimate:** ~50 snapshots × 10 test runs/day = 500/month
- **Cost:** $0 (well under free limit)

### axe-core (Accessibility)
- **Cost:** Free (open source)

### Playwright (All Browser Testing)
- **Cost:** Free (open source)
- **CI time:** +3 minutes per run

### Total Cost
- **Money:** $0/month
- **CI time:** +3 minutes (acceptable for catching bugs)
- **Developer time saved:** 2-3 hours/day

---

## Success Metrics

### Before (No Browser Testing)
- **Visual bugs caught:** ~30% (manual testing only)
- **Time to catch visual bug:** 2-3 days (after deploy)
- **A11y violations shipped:** Common (not tested)
- **Cross-browser issues:** Found by users
- **Cursor bugs:** 45 minutes to debug manually

### After (Automated Browser Testing)
- **Visual bugs caught:** ~95% (Percy + layout tests)
- **Time to catch visual bug:** <5 minutes (CI feedback)
- **A11y violations shipped:** 0% (axe blocks merge)
- **Cross-browser issues:** Caught in CI
- **Cursor bugs:** 5 minutes (test fails immediately)

### AI Agent Efficiency Gains
- **Before:** "Can you check if the blue looks right?" → Human checks → 15 min
- **After:** Percy diff shows color change → AI reads report → 30 sec

**Total time saved:** 10-15 hours/week

---

## Implementation Checklist

### Week 1: Core Setup
- [ ] Install Playwright (`npm install --save-dev @playwright/test`)
- [ ] Install Percy (`npm install --save-dev @percy/playwright`)
- [ ] Install axe-core (`npm install --save-dev @axe-core/playwright`)
- [ ] Create `playwright.config.js`
- [ ] Add bb.edn tasks (e2e, e2e-watch, e2e-debug)
- [ ] Create `test/e2e/helpers/cursor.js`
- [ ] Create `test/e2e/helpers/blocks.js`

### Week 2: Core Tests
- [ ] Write regression test for cursor jumping bug
- [ ] Write navigation cursor preservation tests
- [ ] Write typing advancement tests
- [ ] Write visual regression tests (Percy)
- [ ] Write accessibility tests (axe-core)

### Week 3: Layout & Styles
- [ ] Write computed styles tests
- [ ] Write layout positioning tests
- [ ] Write cross-browser tests
- [ ] Add to CI workflow

### Week 4: Documentation & Polish
- [ ] Document E2E workflow in TESTING.md
- [ ] Create baseline screenshots for Percy
- [ ] Fix any a11y violations found
- [ ] Train team on running E2E tests

---

**END OF SPEC**
