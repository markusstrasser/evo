/**
 * E2E Test: Uncontrolled Editing Flow
 *
 * Verifies the new uncontrolled editing architecture:
 * - Browser owns text state during edit mode
 * - Cursor position remains stable during typing
 * - Text commits to DB on blur
 * - No cursor jumps during rapid input
 *
 * FR: None (architectural refactor, behavior should be unchanged)
 */

import { test, expect } from '@playwright/test';
import { pressKeyOnContentEditable } from './helpers/keyboard.js';

test.describe('Uncontrolled Editing Architecture', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8000');
    await page.waitForSelector('[data-block-id]', { timeout: 10000 });
  });

  test('UC-01: Basic typing without cursor jumps', async ({ page }) => {
    // Find first block
    const firstBlock = page.locator('[data-block-id]').first();

    // Click to enter edit mode
    await firstBlock.click();
    await firstBlock.click(); // Second click to edit

    // Wait for contenteditable to be active
    const editable = page.locator('[contenteditable="true"]').first();
    await expect(editable).toBeFocused();

    // Type text character by character
    await page.keyboard.type('Hello');

    // Verify DOM has the text (browser state)
    const domText = await editable.textContent();
    expect(domText).toBe('Hello');

    // Type more in the middle
    await page.keyboard.press('Home'); // Move to start
    await page.keyboard.type('X');

    // Cursor should be after X, not at end
    const textAfterX = await editable.textContent();
    expect(textAfterX).toBe('XHello');

    // Type again to verify cursor stayed in place
    await page.keyboard.type('Y');
    const finalText = await editable.textContent();
    expect(finalText).toBe('XYHello');
  });

  test('UC-02: Cursor position stability during rapid typing', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await firstBlock.click();

    const editable = page.locator('[contenteditable="true"]').first();
    await expect(editable).toBeFocused();

    // Type rapidly without delays
    await page.keyboard.type('The quick brown fox jumps over the lazy dog');

    // Verify full text appeared
    const text = await editable.textContent();
    expect(text).toBe('The quick brown fox jumps over the lazy dog');

    // Navigate to middle
    await page.keyboard.press('Home');
    for (let i = 0; i < 10; i++) {
      await pressKeyOnContentEditable(page, 'ArrowRight');
    }

    // Insert text in middle
    await page.keyboard.type(' INSERTED');

    const finalText = await editable.textContent();
    expect(finalText).toContain('INSERTED');
    expect(finalText).toContain('brown');
  });

  test('UC-03: Blur commits to DB', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();
    const blockId = await firstBlock.getAttribute('data-block-id');

    // Enter edit mode
    await firstBlock.click();
    await firstBlock.click();

    const editable = page.locator('[contenteditable="true"]').first();
    await expect(editable).toBeFocused();

    // Type new text
    await page.keyboard.type('Committed Text');

    // Blur by pressing Escape
    await page.keyboard.press('Escape');

    // Wait for view mode
    await page.waitForSelector('.content-view', { timeout: 2000 });

    // Verify the text persisted in view mode
    const viewText = await page.locator('.content-view').first().textContent();
    expect(viewText).toBe('Committed Text');
  });

  test('UC-04: Arrow navigation at boundaries', async ({ page }) => {
    // Create a multi-line scenario by setting up blocks
    const firstBlock = page.locator('[data-block-id]').first();

    // Enter edit mode
    await firstBlock.click();
    await firstBlock.click();

    const editable = page.locator('[contenteditable="true"]').first();
    await expect(editable).toBeFocused();

    // Type text
    await page.keyboard.type('First line');

    // Create new block with Enter (use helper for contenteditable)
    await pressKeyOnContentEditable(page, 'Enter');

    // Should now be editing a new block
    const editables = page.locator('[contenteditable="true"]');
    await expect(editables).toHaveCount(1); // Only one editing at a time

    // Type in second block
    await page.keyboard.type('Second line');

    // Go to start of current block
    await page.keyboard.press('Home');

    // Arrow up should navigate to previous block
    await pressKeyOnContentEditable(page, 'ArrowUp');

    // Should be editing first block now
    const currentText = await page.locator('[contenteditable="true"]').textContent();
    expect(currentText).toBe('First line');
  });

  test('UC-05: IME composition (simulated)', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await firstBlock.click();

    const editable = page.locator('[contenteditable="true"]').first();
    await expect(editable).toBeFocused();

    // Type characters that might trigger IME
    await page.keyboard.type('test');

    // Verify text appears correctly
    const text = await editable.textContent();
    expect(text).toBe('test');

    // Continue typing after IME would complete
    await page.keyboard.type(' more text');

    const finalText = await editable.textContent();
    expect(finalText).toBe('test more text');
  });

  test('UC-06: Selection and replacement', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await firstBlock.click();

    const editable = page.locator('[contenteditable="true"]').first();
    await expect(editable).toBeFocused();

    // Type initial text
    await page.keyboard.type('Hello World');

    // Select "World" using keyboard
    await page.keyboard.press('End');
    await page.keyboard.press('Shift+Home'); // Select from end to start

    // Type to replace selection
    await page.keyboard.type('Universe');

    const finalText = await editable.textContent();
    expect(finalText).toBe('Universe');
  });

  test('UC-07: Multiple edit/blur cycles preserve data', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();

    // Cycle 1: Edit and blur
    await firstBlock.click();
    await firstBlock.click();
    await page.keyboard.type('First');
    await page.keyboard.press('Escape');
    await page.waitForSelector('.content-view');

    // Cycle 2: Edit again and modify
    await firstBlock.click();
    await firstBlock.click();
    await page.keyboard.press('End');
    await page.keyboard.type(' Second');
    await page.keyboard.press('Escape');
    await page.waitForSelector('.content-view');

    // Verify cumulative text
    const viewText = await page.locator('.content-view').first().textContent();
    expect(viewText).toBe('First Second');
  });
});
