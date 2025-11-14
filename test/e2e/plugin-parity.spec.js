/**
 * Plugin Parity E2E Tests
 *
 * Tests for Logseq parity features:
 * - Move up/down climb semantics at boundaries
 * - Empty list item Enter behavior
 *
 * Based on PLUGIN_THICK_THIN_PARITY.md spec
 */

import { test, expect } from '@playwright/test';
import { enterEditModeAndClick } from './helpers/edit-mode.js';

test.describe('Move Climb Semantics', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/blocks.html');
    await enterEditModeAndClick(page);
  });

  test('Mod+Shift+Up on first child climbs out to parent level', async ({ page }) => {
    // Create nested structure:
    // - Parent
    //   - Child A (first child)
    //   - Child B

    const editable = page.locator('[contenteditable="true"]').first();
    await editable.click();

    // Create parent
    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');

    // Create and indent child A
    await page.keyboard.type('Child A');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    // Create child B
    await page.keyboard.type('Child B');

    // Navigate back to Child A
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);

    // Verify we're on Child A
    let text = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.anchorNode?.textContent || '';
    });
    expect(text).toContain('Child A');

    // Move up at first-child boundary - should climb out
    const isMac = process.platform === 'darwin';
    const modKey = isMac ? 'Meta' : 'Alt';
    await page.keyboard.press(`${modKey}+Shift+ArrowUp`);
    await page.waitForTimeout(200);

    // Verify structure changed: Child A should now be sibling of Parent
    const dbState = await page.evaluate(() => {
      return window.DEBUG?.state?.() || {};
    });

    // Get doc children - should now have Child A, Parent, and possibly others
    const docChildren = dbState?.['children-by-parent']?.[':doc'] || [];

    // Find blocks by text content
    const blocks = Object.entries(dbState.nodes || {})
      .filter(([_, node]) => node.type === ':block')
      .map(([id, node]) => ({
        id,
        text: node.props?.text || '',
        parent: dbState.derived?.['parent-of']?.[id]
      }));

    const parentBlock = blocks.find(b => b.text === 'Parent');
    const childA = blocks.find(b => b.text === 'Child A');
    const childB = blocks.find(b => b.text === 'Child B');

    // Child A should now be at doc level (climbed out)
    expect(childA?.parent).toBe(':doc');

    // Child B should still be under Parent
    expect(childB?.parent).toBe(parentBlock?.id);

    // Child A should appear before Parent in doc children
    const childAIndex = docChildren.indexOf(childA?.id);
    const parentIndex = docChildren.indexOf(parentBlock?.id);
    expect(childAIndex).toBeLessThan(parentIndex);
  });

  test('Mod+Shift+Down on last child descends into uncle', async ({ page }) => {
    // Create structure:
    // - Parent
    //   - Child A
    //   - Child C (last child)
    // - Uncle

    const editable = page.locator('[contenteditable="true"]').first();
    await editable.click();

    // Create parent
    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');

    // Create and indent child A
    await page.keyboard.type('Child A');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    // Create child C
    await page.keyboard.type('Child C');

    // Outdent to create Uncle at same level as Parent
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Uncle');

    // Navigate back to Child C (up twice from Uncle)
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);

    // Verify we're on Child C
    let text = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.anchorNode?.textContent || '';
    });
    expect(text).toContain('Child C');

    // Move down at last-child boundary - should descend into Uncle
    const isMac = process.platform === 'darwin';
    const modKey = isMac ? 'Meta' : 'Alt';
    await page.keyboard.press(`${modKey}+Shift+ArrowDown`);
    await page.waitForTimeout(200);

    // Verify structure changed: Child C should now be first child of Uncle
    const dbState = await page.evaluate(() => {
      return window.DEBUG?.state?.() || {};
    });

    const blocks = Object.entries(dbState.nodes || {})
      .filter(([_, node]) => node.type === ':block')
      .map(([id, node]) => ({
        id,
        text: node.props?.text || '',
        parent: dbState.derived?.['parent-of']?.[id]
      }));

    const parentBlock = blocks.find(b => b.text === 'Parent');
    const childA = blocks.find(b => b.text === 'Child A');
    const childC = blocks.find(b => b.text === 'Child C');
    const uncleBlock = blocks.find(b => b.text === 'Uncle');

    // Child C should now be under Uncle (descended)
    expect(childC?.parent).toBe(uncleBlock?.id);

    // Child A should still be under Parent
    expect(childA?.parent).toBe(parentBlock?.id);

    // Child C should be first child of Uncle
    const uncleChildren = dbState?.['children-by-parent']?.[uncleBlock?.id] || [];
    expect(uncleChildren[0]).toBe(childC?.id);
  });

  test('Mod+Shift+Up at top level does nothing (boundary)', async ({ page }) => {
    const editable = page.locator('[contenteditable="true"]').first();
    await editable.click();

    // Create a top-level block
    await page.keyboard.type('Top Level Block');
    await page.waitForTimeout(100);

    // Get initial state
    const initialState = await page.evaluate(() => {
      return JSON.parse(JSON.stringify(window.DEBUG?.state?.() || {}));
    });

    // Try to move up (should be no-op since already at top)
    const isMac = process.platform === 'darwin';
    const modKey = isMac ? 'Meta' : 'Alt';
    await page.keyboard.press(`${modKey}+Shift+ArrowUp`);
    await page.waitForTimeout(200);

    // Get final state
    const finalState = await page.evaluate(() => {
      return JSON.parse(JSON.stringify(window.DEBUG?.state?.() || {}));
    });

    // Structure should be unchanged
    expect(finalState['children-by-parent']).toEqual(initialState['children-by-parent']);
  });
});

test.describe('Empty List Item Enter Behavior', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/blocks.html');
    await enterEditModeAndClick(page);
  });

  test('Enter on empty list item unformats and creates peer block', async ({ page }) => {
    // Create structure:
    // - Parent
    //   - Child with content
    //   - (empty list item)

    const editable = page.locator('[contenteditable="true"]').first();
    await editable.click();

    // Create parent
    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');

    // Create and indent child
    await page.keyboard.type('Child with content');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    // Create empty list item
    await page.keyboard.type('- ');
    await page.waitForTimeout(100);

    // Verify we have an empty list marker
    const textBefore = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.anchorNode?.textContent || '';
    });
    expect(textBefore).toBe('- ');

    // Get initial structure
    const initialState = await page.evaluate(() => {
      const db = window.DEBUG?.state?.() || {};
      const blocks = Object.entries(db.nodes || {})
        .filter(([_, node]) => node.type === ':block')
        .map(([id, node]) => ({
          id,
          text: node.props?.text || '',
          parent: db.derived?.['parent-of']?.[id]
        }));

      const parentBlock = blocks.find(b => b.text === 'Parent');
      return {
        parentId: parentBlock?.id,
        parentChildren: db['children-by-parent']?.[parentBlock?.id] || [],
        docChildren: db['children-by-parent']?.[':doc'] || []
      };
    });

    // Press Enter on empty list item
    await page.keyboard.press('Enter');
    await page.waitForTimeout(200);

    // Verify behavior: should unformat current block AND create peer after parent
    const finalState = await page.evaluate(() => {
      const db = window.DEBUG?.state?.() || {};
      const blocks = Object.entries(db.nodes || {})
        .filter(([_, node]) => node.type === ':block')
        .map(([id, node]) => ({
          id,
          text: node.props?.text || '',
          parent: db.derived?.['parent-of']?.[id]
        }));

      const parentBlock = blocks.find(b => b.text === 'Parent');
      const childBlock = blocks.find(b => b.text === 'Child with content');
      const editingId = db.nodes?.['session/ui']?.props?.['editing-block-id'];
      const editingBlock = blocks.find(b => b.id === editingId);

      return {
        parentId: parentBlock?.id,
        childBlockParent: childBlock?.parent,
        docChildren: db['children-by-parent']?.[':doc'] || [],
        editingBlock: editingBlock,
        allBlocks: blocks
      };
    });

    // The unformatted block should have empty text
    const unformattedBlock = finalState.allBlocks.find(b =>
      b.text === '' && b.parent === initialState.parentId
    );
    expect(unformattedBlock).toBeDefined();

    // A new peer block should be created after Parent at doc level
    expect(finalState.docChildren.length).toBeGreaterThan(initialState.docChildren.length);

    // The new peer should be after Parent
    const parentIndex = finalState.docChildren.indexOf(initialState.parentId);
    const newPeerIndex = finalState.docChildren.findIndex(id =>
      !initialState.docChildren.includes(id)
    );
    expect(newPeerIndex).toBeGreaterThan(parentIndex);

    // Cursor should be in the new peer block
    expect(finalState.editingBlock?.parent).toBe(':doc');
  });

  test('Enter on non-empty list continues list pattern', async ({ page }) => {
    const editable = page.locator('[contenteditable="true"]').first();
    await editable.click();

    // Create a list item with content
    await page.keyboard.type('- First item');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Should have created a new list item
    const text = await page.evaluate(() => {
      const selection = window.getSelection();
      return selection.anchorNode?.textContent || '';
    });
    expect(text).toBe('- ');
  });
});
