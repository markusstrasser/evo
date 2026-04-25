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
import {
  enterEditModeAndClick,
  pressKeyCombo,
  pressKeyOnContentEditable,
} from './helpers/index.js';

async function loadTwoBlockFixture(page) {
  await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
  await page.evaluate(() => {
    window.TEST_HELPERS.loadFixture({
      ops: [
        { op: 'create-node', id: 'test-page', type: 'page', props: { title: 'Test Page' } },
        { op: 'place', id: 'test-page', under: 'doc', at: 'last' },
        { op: 'create-node', id: 'first-block', type: 'block', props: { text: 'First block' } },
        { op: 'place', id: 'first-block', under: 'test-page', at: 'last' },
        { op: 'create-node', id: 'second-block', type: 'block', props: { text: '' } },
        { op: 'place', id: 'second-block', under: 'test-page', at: 'last' },
      ],
      session: {
        ui: { 'current-page': 'test-page', 'journals-view?': false },
        selection: { nodes: [] },
      },
    });
  });
  await page.waitForSelector('[data-block-id="second-block"]', { timeout: 5000 });
}

async function enterSecondBlock(page) {
  await page.evaluate(() => {
    window.TEST_HELPERS.dispatchIntent({
      type: 'selection',
      mode: 'replace',
      ids: 'second-block',
    });
    window.TEST_HELPERS.dispatchIntent({ type: 'enter-edit', 'block-id': 'second-block' });
  });
  await page.waitForFunction(
    () => window.TEST_HELPERS?.getSession?.()?.ui?.['editing-block-id'] === 'second-block'
  );
  await page.locator('[contenteditable="true"]').click();
  return 'second-block';
}

test.describe('Buffer Auto-Commit', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
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
    await pressKeyOnContentEditable(page, 'Escape');
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
    await loadTwoBlockFixture(page);
    const blockId = await enterSecondBlock(page);

    const typedText = 'Indented text here';
    await page.keyboard.type(typedText);

    // Indent with Tab
    await pressKeyOnContentEditable(page, 'Tab');
    await page.waitForTimeout(100);

    // Verify still editing (indent preserves edit mode)
    const isEditing = await page.evaluate(() => {
      return !!document.querySelector('[contenteditable="true"]');
    });
    expect(isEditing).toBe(true);

    // Exit edit mode to check persisted text
    await pressKeyOnContentEditable(page, 'Escape');
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
    await loadTwoBlockFixture(page);
    const blockId = await enterSecondBlock(page);

    const typedText = 'Moving this block up';
    await page.keyboard.type(typedText);

    // Move block up with Cmd+Shift+ArrowUp
    await pressKeyCombo(page, 'ArrowUp', ['Meta', 'Shift']);
    await page.waitForTimeout(100);

    // Exit edit mode
    await pressKeyOnContentEditable(page, 'Escape');
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
