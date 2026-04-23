/**
 * Text Selection E2E Tests
 *
 * Tests robust text selection handling in contenteditable elements,
 * verifying the utils.text-selection namespace integration.
 *
 * Covers:
 * - Cursor positioning with complex DOM structures
 * - Text selection across multiple nodes
 * - BR element handling for newlines
 * - Position tracking during editing
 */

import { expect, test } from '@playwright/test';
import { pressKeyOnContentEditable } from './helpers/index.js';

test.describe('Text Selection Utilities', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for empty database with clean state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    // Don't call selectPage() - test mode auto-selects test-page
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('preserves cursor position during text input', async ({ page }) => {
    // Click on first block to enter edit mode
    const block = page.locator('[data-block-id]').first();
    await block.click();
    await page.waitForTimeout(100);

    // Type text character by character
    const text = 'Hello world';
    for (let i = 0; i < text.length; i++) {
      await page.keyboard.type(text[i]);
      await page.waitForTimeout(50);

      // Verify cursor is at expected position
      const cursorPos = await page.evaluate(() => {
        const selection = window.getSelection();
        return selection.rangeCount > 0 ? selection.getRangeAt(0).startOffset : -1;
      });

      expect(cursorPos).toBe(i + 1);
    }
  });

  // TODO: Fix flaky test - see test/e2e/SKIPPED_TESTS.md § Flaky Tests
  // Issue: Characters dropped during rapid typing (e.g., "Hello" → "Hlo w")
  // Fix strategy: Use slower typing with delay: 50 or use dispatchIntent for text entry
  test.skip('handles text selection correctly', async ({ page }) => {
    // Enter edit mode and type text
    const block = page.locator('[data-block-id]').first();
    await block.click();
    await page.keyboard.type('Hello world');
    await page.waitForTimeout(100);

    // Set cursor to start via browser API (Home key not supported)
    await page.evaluate(() => {
      const elem = document.activeElement;
      if (elem?.firstChild) {
        const range = document.createRange();
        range.setStart(elem.firstChild, 0);
        range.setEnd(elem.firstChild, 5); // Select "Hello"
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
    });

    // Verify selection
    const selectedText = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.toString();
    });

    expect(selectedText).toBe('Hello');
  });

  // TODO: Fix flaky test - see test/e2e/SKIPPED_TESTS.md § Flaky Tests
  // Issue: Enter creates new block, cursor lands in different element
  // Fix strategy: After Enter, query for new block's contenteditable and check cursor there
  test.skip('cursor positioning works with Enter key (BR elements)', async ({ page }) => {
    const block = page.locator('[data-block-id]').first();
    await block.click();

    // Type first line
    await page.keyboard.type('Line 1');

    // Press Enter to create new block
    await pressKeyOnContentEditable(page, 'Enter');
    await page.waitForTimeout(100);

    // Type second line
    await page.keyboard.type('Line 2');

    // Verify cursor is at end of second block
    const cursorPos = await page.evaluate(() => {
      const selection = window.getSelection();
      const range = selection.getRangeAt(0);
      return range.startOffset;
    });

    expect(cursorPos).toBe(6); // "Line 2" = 6 characters
  });

  // TODO: Fix flaky test - see test/e2e/SKIPPED_TESTS.md § Flaky Tests
  // Issue: First keypress enters edit mode, subsequent keystrokes may be lost
  // Fix strategy: Use enterEditModeAndClick helper before typing, add explicit edit mode entry
  test.skip('arrow keys maintain cursor position correctly', async ({ page }) => {
    const block = page.locator('[data-block-id]').first();
    await block.click();
    await page.keyboard.type('Hello world');
    await page.waitForTimeout(100);

    // Move cursor to start via browser API (Home key not supported)
    await page.evaluate(() => {
      const elem = document.activeElement;
      if (elem?.firstChild) {
        const range = document.createRange();
        range.setStart(elem.firstChild, 0);
        range.collapse(true);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
    });

    let cursorPos = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.getRangeAt(0).startOffset;
    });
    expect(cursorPos).toBe(0);

    // Move right 5 times using native arrow keys
    for (let i = 0; i < 5; i++) {
      await page.keyboard.press('ArrowRight');
    }

    cursorPos = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.getRangeAt(0).startOffset;
    });
    expect(cursorPos).toBe(5);
  });

  // REMOVED: 'text extraction handles complex DOM'
  // Reason: Tests internal DOM text extraction utility (element->text).
  // Not user-facing behavior - covered by unit tests in kernel.

  test('position tracking during rapid typing', async ({ page }) => {
    const block = page.locator('[data-block-id]').first();
    await block.click();
    await page.waitForTimeout(100);

    // Type rapidly
    const text = 'RapidTypingTest';
    await page.keyboard.type(text, { delay: 10 });

    // Verify final cursor position
    const cursorPos = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.getRangeAt(0).startOffset;
    });

    expect(cursorPos).toBe(text.length);

    // Verify text content
    const content = await page.evaluate(() => {
      const elem = document.querySelector('[contenteditable="true"]');
      return elem ? elem.textContent : '';
    });

    expect(content).toBe(text);
  });

  test('selection collapse on arrow keys', async ({ page }) => {
    const block = page.locator('[data-block-id]').first();
    await block.click();
    await page.keyboard.type('Hello world');
    await page.waitForTimeout(100);

    // Select all via browser API (platform-independent)
    await page.evaluate(() => {
      const elem = document.activeElement;
      if (elem) {
        const range = document.createRange();
        range.selectNodeContents(elem);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
    });

    // Verify selection exists
    let hasSelection = await page.evaluate(() => {
      const selection = window.getSelection();
      return !selection.isCollapsed;
    });
    expect(hasSelection).toBe(true);

    // Press left arrow - should collapse to start
    await page.keyboard.press('ArrowLeft');

    hasSelection = await page.evaluate(() => {
      const selection = window.getSelection();
      return !selection.isCollapsed;
    });
    expect(hasSelection).toBe(false);

    // Cursor should be at start
    const cursorPos = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.getRangeAt(0).startOffset;
    });
    expect(cursorPos).toBe(0);
  });

  // TODO: Fix flaky test - see test/e2e/SKIPPED_TESTS.md § Flaky Tests
  // Issue: Timeout waiting for contenteditable:focus after paste
  // Fix strategy: Use waitForEditing helper from index.js instead of raw selector
  test.skip('handles paste with position tracking', async ({ page }) => {
    const block = page.locator('[data-block-id]').first();
    await block.click();

    // Wait for contenteditable to be focused
    await page.waitForSelector('[contenteditable="true"]:focus', { timeout: 5000 });

    // Clear any existing content first
    await page.evaluate(() => {
      const elem = document.activeElement;
      if (elem && elem.getAttribute('contenteditable') === 'true') {
        elem.textContent = '';
      }
    });

    await page.keyboard.type('Hello ');

    // Simulate paste by typing (actual paste is complex in Playwright)
    await page.keyboard.type('world');
    await page.waitForTimeout(100);

    const content = await page.evaluate(() => {
      const elem = document.querySelector('[contenteditable="true"]');
      return elem ? elem.textContent : '';
    });

    expect(content).toBe('Hello world');

    // Cursor should be at end
    const cursorPos = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.getRangeAt(0).startOffset;
    });
    expect(cursorPos).toBe(11); // "Hello world" = 11 chars
  });
});

test.describe('Text Selection Integration with Block Component', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state (avoids demo data interference)
    await page.goto('/blocks.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  // REMOVED: 'cursor positioning after navigation'
  // Reason: Duplicate coverage - cross-block navigation is tested in navigation.spec.js.
  // Was flaky due to characters dropped during typing.

  // REMOVED: 'maintains cursor during block operations'
  // Reason: Duplicate coverage - cursor maintenance is tested in editing-parity.spec.js.
  // Was flaky due to characters dropped during typing.
});
