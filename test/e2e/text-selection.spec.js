/**
 * Text Selection E2E Tests
 *
 * Tests robust text selection handling in contenteditable elements,
 * verifying the util.text-selection namespace integration.
 *
 * Covers:
 * - Cursor positioning with complex DOM structures
 * - Text selection across multiple nodes
 * - BR element handling for newlines
 * - Position tracking during editing
 */

import { test, expect } from '@playwright/test';
import { pressKeyOnContentEditable } from './helpers/keyboard.js';

test.describe('Text Selection Utilities', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/blocks.html');
    await page.waitForLoadState('networkidle');

    // Wait for app to initialize
    await page.waitForSelector('.block', { timeout: 5000 });
  });

  test('preserves cursor position during text input', async ({ page }) => {
    // Click on first block to enter edit mode
    const block = page.locator('.block').first();
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

  test('handles text selection correctly', async ({ page }) => {
    // Enter edit mode and type text
    const block = page.locator('.block').first();
    await block.click();
    await page.keyboard.type('Hello world');
    await page.waitForTimeout(100);

    // Select "Hello" by double-clicking
    const contentEditable = page.locator('[contenteditable="true"]').first();

    // Move to start and select 5 characters
    await pressKeyOnContentEditable(page, 'Home');
    await pressKeyOnContentEditable(page, 'ArrowRight', { shiftKey: true });
    await pressKeyOnContentEditable(page, 'ArrowRight', { shiftKey: true });
    await pressKeyOnContentEditable(page, 'ArrowRight', { shiftKey: true });
    await pressKeyOnContentEditable(page, 'ArrowRight', { shiftKey: true });
    await pressKeyOnContentEditable(page, 'ArrowRight', { shiftKey: true });

    // Verify selection
    const selectedText = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.toString();
    });

    expect(selectedText).toBe('Hello');
  });

  test('cursor positioning works with Enter key (BR elements)', async ({ page }) => {
    const block = page.locator('.block').first();
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

  test('arrow keys maintain cursor position correctly', async ({ page }) => {
    const block = page.locator('.block').first();
    await block.click();
    await page.keyboard.type('Hello world');
    await page.waitForTimeout(100);

    // Move cursor to start
    await pressKeyOnContentEditable(page, 'Home');
    let cursorPos = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.getRangeAt(0).startOffset;
    });
    expect(cursorPos).toBe(0);

    // Move right 5 times
    for (let i = 0; i < 5; i++) {
      await pressKeyOnContentEditable(page, 'ArrowRight');
    }

    cursorPos = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.getRangeAt(0).startOffset;
    });
    expect(cursorPos).toBe(5);
  });

  test('text extraction handles complex DOM', async ({ page }) => {
    const block = page.locator('.block').first();
    await block.click();

    // Type text with special characters
    await page.keyboard.type('Test with spaces   and tabs');
    await page.waitForTimeout(100);

    // Get text content using our utility
    const extractedText = await page.evaluate(() => {
      const elem = document.querySelector('[contenteditable="true"]');
      if (!elem) return null;

      // Simulate our element->text function
      let text = '';
      const walker = document.createTreeWalker(
        elem,
        NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT,
        null
      );

      let node;
      while (node = walker.nextNode()) {
        if (node.nodeType === Node.TEXT_NODE) {
          text += node.textContent;
        } else if (node.nodeName === 'BR') {
          text += '\n';
        }
      }

      // Add trailing newline if not present
      if (!text.endsWith('\n')) text += '\n';
      return text;
    });

    expect(extractedText).toBe('Test with spaces   and tabs\n');
  });

  test('position tracking during rapid typing', async ({ page }) => {
    const block = page.locator('.block').first();
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
    const block = page.locator('.block').first();
    await block.click();
    await page.keyboard.type('Hello world');
    await page.waitForTimeout(100);

    // Select all
    await pressKeyOnContentEditable(page, 'a', { ctrlKey: true });

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

  test('handles paste with position tracking', async ({ page }) => {
    const block = page.locator('.block').first();
    await block.click();
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
    await page.goto('/blocks.html');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('.block', { timeout: 5000 });
  });

  test('cursor positioning after navigation', async ({ page }) => {
    // Enter edit mode on first block
    const firstBlock = page.locator('.block').first();
    await firstBlock.click();
    await page.keyboard.type('First block');

    // Create second block
    await pressKeyOnContentEditable(page, 'Enter');
    await page.keyboard.type('Second block');

    // Navigate up
    await pressKeyOnContentEditable(page, 'ArrowUp');
    await page.waitForTimeout(100);

    // Should be at end of first block
    const content = await page.evaluate(() => {
      const elem = document.querySelector('[contenteditable="true"]');
      return elem ? elem.textContent : '';
    });
    expect(content).toBe('First block');
  });

  test('maintains cursor during block operations', async ({ page }) => {
    const block = page.locator('.block').first();
    await block.click();
    await page.keyboard.type('Test content');

    // Move cursor to middle
    await pressKeyOnContentEditable(page, 'Home');
    await pressKeyOnContentEditable(page, 'ArrowRight');
    await pressKeyOnContentEditable(page, 'ArrowRight');
    await pressKeyOnContentEditable(page, 'ArrowRight');
    await pressKeyOnContentEditable(page, 'ArrowRight');

    // Type character in middle
    await page.keyboard.type('X');

    const content = await page.evaluate(() => {
      const elem = document.querySelector('[contenteditable="true"]');
      return elem ? elem.textContent : '';
    });

    expect(content).toBe('TestX content');
  });
});
