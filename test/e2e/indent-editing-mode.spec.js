// @ts-check
import { test, expect } from '@playwright/test';
import { pressKeyOnContentEditable, waitForBlocks, getFirstBlockId, enterEditMode, getEditingBlockId } from './helpers/index.js';

/**
 * Tests for Tab/Shift+Tab while in editing mode.
 *
 * Critical regression test: After indent/outdent, editing mode must be preserved.
 */

test.describe('Indent/Outdent in Editing Mode', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await waitForBlocks(page);
  });

  test('Tab while editing preserves editing mode', async ({ page }) => {
    // Use the first block directly (it exists in demo data)
    const firstBlockId = await getFirstBlockId(page);

    // Enter edit mode
    await enterEditMode(page, firstBlockId);

    // Verify we're in editing mode
    const editingBefore = await getEditingBlockId(page);
    expect(editingBefore).toBe(firstBlockId);

    // Press Tab (indent) - even if indent fails (no prev sibling), editing should be preserved
    await pressKeyOnContentEditable(page, 'Tab');
    await page.waitForTimeout(150);

    // Verify we're STILL in editing mode after Tab
    const editingAfter = await getEditingBlockId(page);
    expect(editingAfter).toBe(firstBlockId);

    // Verify contenteditable is still focused
    const isFocused = await page.evaluate(() => {
      const active = document.activeElement;
      return active?.getAttribute('contenteditable') === 'true';
    });
    expect(isFocused).toBe(true);
  });

  test('Shift+Tab while editing preserves editing mode', async ({ page }) => {
    // SETUP: We need a nested structure where outdent keeps block in view
    // Create: Parent -> Child (we'll edit and outdent Child)
    // After outdent: Parent, Child (siblings) - both still visible

    const firstBlockId = await getFirstBlockId(page);
    const childId = 'test-child-block';

    // Create a child block under the first block using transact
    await page.evaluate(({ parentId, childId }) => {
      window.TEST_HELPERS?.transact([
        { op: 'create-node', id: childId, type: 'block', props: { text: 'Child block' } },
        { op: 'place', id: childId, under: parentId, at: 'last' }
      ]);
    }, { parentId: firstBlockId, childId });
    await page.waitForTimeout(100);

    // Enter edit mode on the CHILD block
    await enterEditMode(page, childId);

    // Verify we're in editing mode on child
    const editingBefore = await getEditingBlockId(page);
    expect(editingBefore).toBe(childId);

    // Press Shift+Tab (outdent) - child should become sibling of parent
    await pressKeyOnContentEditable(page, 'Tab', { shiftKey: true });
    await page.waitForTimeout(200);

    // Verify we're STILL in editing mode after Shift+Tab
    const editingAfter = await getEditingBlockId(page);
    expect(editingAfter).toBe(childId);

    // Verify contenteditable is still focused
    const isFocused = await page.evaluate(() => {
      const active = document.activeElement;
      return active?.getAttribute('contenteditable') === 'true';
    });
    expect(isFocused).toBe(true);
  });

  test('Enter creates new block and enters edit mode on new block', async ({ page }) => {
    // Get the first block
    const firstBlockId = await getFirstBlockId(page);

    // Set some text on first block
    await page.evaluate(({ id }) => {
      window.TEST_HELPERS?.setBlockText(id, 'Hello world');
    }, { id: firstBlockId });
    await page.waitForTimeout(50);

    // Enter edit mode at end of first block
    await enterEditMode(page, firstBlockId, 'end');

    // Verify we're editing first block
    const editingBefore = await getEditingBlockId(page);
    expect(editingBefore).toBe(firstBlockId);

    // Press Enter to create new block
    await pressKeyOnContentEditable(page, 'Enter');
    await page.waitForTimeout(200);

    // Verify we're editing a NEW block (not the original, not null)
    const editingAfter = await getEditingBlockId(page);

    // Should be editing something
    expect(editingAfter).not.toBeNull();
    expect(editingAfter).toBeDefined();
    // Should NOT be the original block (we created a new one)
    expect(editingAfter).not.toBe(firstBlockId);

    // Verify contenteditable is still focused
    const isFocused = await page.evaluate(() => {
      const active = document.activeElement;
      return active?.getAttribute('contenteditable') === 'true';
    });
    expect(isFocused).toBe(true);
  });
});
