/**
 * E2E Test: Buffer Auto-Commit
 *
 * Verifies that typed content in the buffer is automatically committed
 * before intents that might lose it (e.g., Shift+Arrow extends selection).
 *
 * Bug scenario (before fix):
 * 1. User types text in a new block
 * 2. User presses Shift+ArrowUp to extend selection
 * 3. The typed text was lost because it was only in the buffer, not DB
 *
 * Fix: Runtime injects pending buffer into intent, kernel prepends update-node op.
 *
 * FR: None (bug fix for uncontrolled editing architecture)
 */

import { expect, test } from '@playwright/test';
import { enterEditModeAndClick, pressKeyCombo } from './helpers/index.js';

test.describe('Buffer Auto-Commit', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('Shift+Arrow preserves typed text when exiting edit mode', async ({ page }) => {
    // Enter edit mode
    await enterEditModeAndClick(page);

    // Type some text (this goes into buffer, not DB yet)
    const typedText = 'This text should be preserved';
    await page.keyboard.type(typedText);

    // Get the block ID we're editing
    const blockId = await page.evaluate(() => {
      const editable = document.querySelector('[contenteditable="true"]');
      return editable?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });
    expect(blockId).toBeTruthy();

    // Press Shift+ArrowUp - this exits edit mode and extends selection
    // BEFORE the fix, this would lose the typed text
    await pressKeyCombo(page, 'ArrowUp', ['Shift']);

    // Wait for state transition
    await page.waitForTimeout(100);

    // Verify we exited edit mode
    const isEditing = await page.evaluate(() => {
      return !!document.querySelector('[contenteditable="true"]');
    });
    expect(isEditing).toBe(false);

    // Verify the text was preserved in the DB (visible in view mode)
    const blockText = await page.evaluate((id) => {
      const block = document.querySelector(`[data-block-id="${id}"]`);
      const content = block?.querySelector('.block-content');
      return content?.textContent || '';
    }, blockId);

    expect(blockText).toContain(typedText);
  });

  test('Escape preserves typed text when exiting edit mode', async ({ page }) => {
    await enterEditModeAndClick(page);

    const typedText = 'Escape should save this';
    await page.keyboard.type(typedText);

    const blockId = await page.evaluate(() => {
      const editable = document.querySelector('[contenteditable="true"]');
      return editable?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });

    // Press Escape to exit edit mode
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify text preserved
    const blockText = await page.evaluate((id) => {
      const block = document.querySelector(`[data-block-id="${id}"]`);
      const content = block?.querySelector('.block-content');
      return content?.textContent || '';
    }, blockId);

    expect(blockText).toContain(typedText);
  });

  test('Tab (indent) preserves typed text', async ({ page }) => {
    // Create a block that can be indented (needs a previous sibling)
    await enterEditModeAndClick(page);
    await page.keyboard.type('First block');
    await page.keyboard.press('Enter');

    // Now in second block, type some text
    const typedText = 'Indented text here';
    await page.keyboard.type(typedText);

    const blockId = await page.evaluate(() => {
      const editable = document.querySelector('[contenteditable="true"]');
      return editable?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });

    // Indent with Tab
    await page.keyboard.press('Tab');
    await page.waitForTimeout(100);

    // Verify still editing (indent preserves edit mode)
    const isEditing = await page.evaluate(() => {
      return !!document.querySelector('[contenteditable="true"]');
    });
    expect(isEditing).toBe(true);

    // Exit edit mode to check persisted text
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify text preserved after indent
    const blockText = await page.evaluate((id) => {
      const block = document.querySelector(`[data-block-id="${id}"]`);
      const content = block?.querySelector('.block-content');
      return content?.textContent || '';
    }, blockId);

    expect(blockText).toContain(typedText);
  });

  test('Cmd+Shift+Arrow (move block) preserves typed text', async ({ page }) => {
    // Create two blocks
    await enterEditModeAndClick(page);
    await page.keyboard.type('First block');
    await page.keyboard.press('Enter');

    // Type in second block
    const typedText = 'Moving this block up';
    await page.keyboard.type(typedText);

    const blockId = await page.evaluate(() => {
      const editable = document.querySelector('[contenteditable="true"]');
      return editable?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });

    // Move block up with Cmd+Shift+ArrowUp
    await pressKeyCombo(page, 'ArrowUp', ['Meta', 'Shift']);
    await page.waitForTimeout(100);

    // Exit edit mode
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify text preserved after move
    const blockText = await page.evaluate((id) => {
      const block = document.querySelector(`[data-block-id="${id}"]`);
      const content = block?.querySelector('.block-content');
      return content?.textContent || '';
    }, blockId);

    expect(blockText).toContain(typedText);
  });
});
