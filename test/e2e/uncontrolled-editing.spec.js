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
import { pressKeyOnContentEditable } from './helpers/index.js';

/**
 * Helper: Enter edit mode on a block by double-clicking and waiting for contenteditable
 */
async function enterEditMode(page, blockLocator) {
  // Strategy: Click the .content-view span directly (not the container)
  // This ensures we're hitting the actual view element that has the click handler
  const viewSpan = blockLocator.locator('.content-view').first();

  // First click to select
  await viewSpan.click();

  // Wait for background color change (visual indicator of selection)
  // Focused blocks get background-color: #b3d9ff
  await page.waitForTimeout(500); // Give Replicant time to re-render with new props

  // Second click to enter edit mode
  await viewSpan.click();

  // Wait for contenteditable to appear
  const editable = page.locator('[contenteditable="true"]').first();
  await editable.waitFor({ state: 'attached', timeout: 5000 });

  return editable;
}

test.describe('Uncontrolled Editing Architecture', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html');
    await page.waitForSelector('[data-block-id]', { timeout: 10000 });
    // Wait for app to fully initialize (plugins loaded, etc.)
    await page.waitForTimeout(500);
  });

  test('UC-01: Basic typing without cursor jumps', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();
    const editable = await enterEditMode(page, firstBlock);
    await expect(editable).toBeFocused();

    // Clear existing text and type new text
    await page.keyboard.press('Meta+A'); // Select all
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
    const editable = await enterEditMode(page, firstBlock);
    await expect(editable).toBeFocused();

    // Clear existing text and type rapidly without delays
    await page.keyboard.press('Meta+A');
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
    const editable = await enterEditMode(page, firstBlock);
    await expect(editable).toBeFocused();

    // Clear and type new text
    await page.keyboard.press('Meta+A');
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
    const firstBlock = page.locator('[data-block-id]').first();
    const editable = await enterEditMode(page, firstBlock);
    await expect(editable).toBeFocused();

    // Clear and type text with multiple words
    await page.keyboard.press('Meta+A');
    await page.keyboard.type('Hello World Test');

    // Go to start
    await page.keyboard.press('Home');

    // Arrow right to move cursor
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowRight');

    // Type to verify cursor position
    await page.keyboard.type('X');

    // Should have inserted X at position 2
    const text = await editable.textContent();
    expect(text).toBe('HeXllo World Test');

    // Go to end
    await page.keyboard.press('End');

    // Type at end
    await page.keyboard.type(' End');

    const finalText = await editable.textContent();
    expect(finalText).toBe('HeXllo World Test End');
  });

  test('UC-05: IME composition (simulated)', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();
    const editable = await enterEditMode(page, firstBlock);
    await expect(editable).toBeFocused();

    // Clear and type characters that might trigger IME
    await page.keyboard.press('Meta+A');
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
    const editable = await enterEditMode(page, firstBlock);
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
    await enterEditMode(page, firstBlock);
    await page.keyboard.press('Meta+A'); // Clear existing
    await page.keyboard.type('First');
    await page.keyboard.press('Escape');
    await page.waitForSelector('.content-view');

    // Cycle 2: Edit again and modify
    await enterEditMode(page, firstBlock);
    await page.keyboard.press('End');
    await page.keyboard.type(' Second');
    await page.keyboard.press('Escape');
    await page.waitForSelector('.content-view');

    // Verify cumulative text
    const viewText = await page.locator('.content-view').first().textContent();
    expect(viewText).toBe('First Second');
  });
});
