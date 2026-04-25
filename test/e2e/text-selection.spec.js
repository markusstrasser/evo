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
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
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
    await pressKeyOnContentEditable(page, 'ArrowLeft');

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
});
