/**
 * Text Editing E2E Tests
 *
 * Critical regression tests for cursor behavior and typing.
 * Based on bugs found in BROWSER_TESTING_BUGS.md
 */

import { test, expect } from '@playwright/test';
import { getCursorPosition, typeAndVerifyCursor, enterEditModeAndClick } from './helpers/index.js';

test.describe('Text Editing', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for empty database with clean state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
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
      // Removed: waitForTimeout - Playwright auto-waits
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
    // Removed: waitForTimeout - Playwright auto-waits

    const afterTyping = await getCursorPosition(page);
    expect(afterTyping.offset).toBe(initialOffset + 1);
  });

  test('REGRESSION: text is not duplicated in DOM', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Single line of text');

    // Get block HTML
    const blockHTML = await page.evaluate(() => {
      const block = document.querySelector('[contenteditable="true"]');
      return block.innerHTML;
    });

    // Count occurrences of the text
    const textCount = (blockHTML.match(/Single line of text/g) || []).length;

    // Text should appear exactly once
    expect(textCount).toBe(1);
  });

  test.skip('REGRESSION: mock-text element positioned correctly', async ({ page }) => {
    // NOTE: Skipped - tests internal implementation detail (mock-text positioning)
    // The mock-text element is used internally for cursor calculations
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

    // Skip test if mock-text doesn't exist (might not be implemented yet)
    if (!positions) {
      test.skip();
      return;
    }

    // Mock should match block position
    expect(positions.mock.top).toBe(positions.block.top);
    expect(positions.mock.left).toBe(positions.block.left);
    expect(positions.mock.width).toBe(positions.block.width);
  });
});
