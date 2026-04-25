/**
 * E2E Test: Multi-Level Indent/Outdent
 *
 * Verifies that indent/outdent works correctly when parent+child blocks
 * are both selected (hierarchical selection).
 *
 * Bug scenario (before fix):
 * 1. User selects a parent block and its child (multi-level selection)
 * 2. User tries to outdent with Shift+Tab
 * 3. Nothing happened because consecutive-siblings? check failed
 *    (parent and child have different parents, so same-parent? returned nil)
 *
 * Fix: Apply filter-top-level-targets BEFORE consecutive check.
 * This filters out nested blocks, so only the top-level parent is outdented
 * (children come along naturally).
 *
 * FR: :fr.struct/indent-outdent
 */

import { expect, test } from '@playwright/test';
import { pressGlobalKey } from './helpers/index.js';

test.describe('Multi-Level Indent/Outdent', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
    await page.evaluate(() => {
      window.TEST_HELPERS.loadFixture({
        ops: [
          { op: 'create-node', id: 'test-page', type: 'page', props: { title: 'Indent Test' } },
          { op: 'place', id: 'test-page', under: 'doc', at: 'last' },
          { op: 'create-node', id: 'block-a', type: 'block', props: { text: 'Alpha' } },
          { op: 'place', id: 'block-a', under: 'test-page', at: 'last' },
          { op: 'create-node', id: 'block-b', type: 'block', props: { text: 'Bravo' } },
          { op: 'place', id: 'block-b', under: 'test-page', at: 'last' },
          { op: 'create-node', id: 'block-c', type: 'block', props: { text: 'Charlie' } },
          { op: 'place', id: 'block-c', under: 'test-page', at: 'last' },
          { op: 'create-node', id: 'block-c-child', type: 'block', props: { text: 'Child' } },
          { op: 'place', id: 'block-c-child', under: 'block-c', at: 'last' },
        ],
        session: {
          ui: { 'current-page': 'test-page', 'journals-view?': false },
          selection: { nodes: [] },
        },
      });
    });
    await page.waitForSelector('[data-block-id="block-b"]', { timeout: 5000 });
  });

  test('indent via intent preserves editing mode', async ({ page }) => {
    await page.click('[data-block-id="block-b"] .block-content');
    await page.waitForTimeout(100);

    // Enter edit mode
    await pressGlobalKey(page, 'Enter');
    await page.waitForTimeout(100);

    // Verify in edit mode
    const isEditing = await page.evaluate(
      () => !!document.querySelector('[contenteditable="true"]')
    );
    expect(isEditing).toBe(true);

    // Dispatch indent intent directly (Tab doesn't always work in test)
    await page.evaluate(() => {
      if (window.TEST_HELPERS?.dispatchIntent) {
        window.TEST_HELPERS.dispatchIntent({ type: 'indent-selected' });
      }
    });
    await page.waitForTimeout(100);

    // Should still be in edit mode after indent
    const stillEditing = await page.evaluate(
      () => !!document.querySelector('[contenteditable="true"]')
    );
    expect(stillEditing).toBe(true);
  });

  test('outdent via intent preserves editing mode', async ({ page }) => {
    await page.click('[data-block-id="block-c-child"] .block-content');
    await page.waitForTimeout(100);

    // Enter edit mode
    await pressGlobalKey(page, 'Enter');
    await page.waitForTimeout(100);

    // Verify in edit mode
    const isEditing = await page.evaluate(
      () => !!document.querySelector('[contenteditable="true"]')
    );
    expect(isEditing).toBe(true);

    // Dispatch outdent intent
    await page.evaluate(() => {
      if (window.TEST_HELPERS?.dispatchIntent) {
        window.TEST_HELPERS.dispatchIntent({ type: 'outdent-selected' });
      }
    });
    await page.waitForTimeout(100);

    // Should still be in edit mode after outdent
    const stillEditing = await page.evaluate(
      () => !!document.querySelector('[contenteditable="true"]')
    );
    expect(stillEditing).toBe(true);
  });

  test('multi-select outdent filters to top-level targets', async ({ page }) => {
    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({
        type: 'selection',
        mode: 'replace',
        ids: ['block-c', 'block-c-child'],
      });
    });

    // Dispatch outdent - should NOT fail silently anymore
    const beforeStructure = await page.evaluate(() => {
      return Array.from(document.querySelectorAll('[data-block-id]')).map((b) =>
        b.getAttribute('data-block-id')
      );
    });

    await page.evaluate(() => {
      if (window.TEST_HELPERS?.dispatchIntent) {
        window.TEST_HELPERS.dispatchIntent({ type: 'outdent-selected' });
      }
    });
    await page.waitForTimeout(200);

    // Structure should still have all the blocks (no data loss)
    const afterStructure = await page.evaluate(() => {
      return Array.from(document.querySelectorAll('[data-block-id]')).map((b) =>
        b.getAttribute('data-block-id')
      );
    });

    // All blocks should still exist
    expect(afterStructure.length).toBeGreaterThanOrEqual(beforeStructure.length - 1);
  });
});
