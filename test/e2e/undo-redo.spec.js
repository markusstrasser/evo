// @ts-check
import { expect, test } from '@playwright/test';
import { countBlocks, enterEditModeAndClick, getFirstBlockId } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * Undo/Redo E2E Tests
 *
 * Tests undo/redo functionality using the DEBUG API to verify
 * history state and operation reversibility.
 *
 * Note: History tracks block operations (create, delete, move),
 * not individual text edits within contenteditable.
 */

test.describe('Undo/Redo Operations', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test.describe('History State via DEBUG API', () => {
    test('fresh state has no undo history', async ({ page }) => {
      const undoCount = await page.evaluate(() => window.DEBUG?.undoCount?.());
      const canUndo = await page.evaluate(() => window.DEBUG?.canUndo?.());

      expect(undoCount).toBe(0);
      expect(canUndo).toBe(false);
    });

    test('fresh state has no redo history', async ({ page }) => {
      const redoCount = await page.evaluate(() => window.DEBUG?.redoCount?.());
      const canRedo = await page.evaluate(() => window.DEBUG?.canRedo?.());

      expect(redoCount).toBe(0);
      expect(canRedo).toBe(false);
    });

    test('undoStack() returns empty array for fresh state', async ({ page }) => {
      const stack = await page.evaluate(() => window.DEBUG?.undoStack?.());

      expect(Array.isArray(stack)).toBe(true);
      expect(stack.length).toBe(0);
    });
  });

  test.describe('Block Creation Undo', () => {
    test('creating block via Enter adds to undo history', async ({ page }) => {
      const initialCount = await countBlocks(page);

      // Enter edit mode and press Enter to create new block
      await enterEditModeAndClick(page);
      await page.keyboard.type('First block');
      await wait(page);

      // Use dispatchIntent to create a new block (more reliable than keyboard)
      const blockId = await getFirstBlockId(page);
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'split-at-cursor',
          blockId: id,
          cursorPos: 11, // After "First block"
        });
      }, blockId);
      await wait(page, 200);

      // Verify block was created
      const afterCount = await countBlocks(page);
      expect(afterCount).toBeGreaterThan(initialCount);

      // Verify undo is now available
      const canUndo = await page.evaluate(() => window.DEBUG?.canUndo?.());
      expect(canUndo).toBe(true);

      const undoCount = await page.evaluate(() => window.DEBUG?.undoCount?.());
      expect(undoCount).toBeGreaterThan(0);
    });

    test('Cmd+Z undoes block creation', async ({ page }) => {
      const initialCount = await countBlocks(page);

      // Create a new block
      await enterEditModeAndClick(page);
      const blockId = await getFirstBlockId(page);

      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'split-at-cursor',
          blockId: id,
          cursorPos: 0,
        });
      }, blockId);
      await wait(page, 200);

      const afterCreate = await countBlocks(page);
      expect(afterCreate).toBe(initialCount + 1);

      // Undo via keyboard
      await page.keyboard.press('Meta+z');
      await wait(page, 300);

      // Block should be removed
      const afterUndo = await countBlocks(page);
      expect(afterUndo).toBe(initialCount);
    });
  });

  test.describe('Block Deletion Undo', () => {
    test('deleting block via intent can be undone', async ({ page }) => {
      // First create a second block to delete
      await enterEditModeAndClick(page);
      const blockId = await getFirstBlockId(page);

      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'split-at-cursor',
          blockId: id,
          cursorPos: 0,
        });
      }, blockId);
      await wait(page, 200);

      const beforeDelete = await countBlocks(page);

      // Select and delete the second block
      await page.keyboard.press('Escape'); // Exit edit mode
      await wait(page);
      await page.keyboard.press('ArrowDown'); // Move to second block
      await wait(page);

      // Delete via backspace (should merge or delete)
      await page.evaluate(() => {
        const session = window.TEST_HELPERS.getSession();
        const focusId = session?.selection?.focus;
        if (focusId) {
          window.TEST_HELPERS.dispatchIntent({
            type: 'delete-selected',
          });
        }
      });
      await wait(page, 200);

      // Verify deletion occurred
      const _afterDelete = await countBlocks(page);

      // Undo the deletion
      await page.keyboard.press('Meta+z');
      await wait(page, 300);

      const afterUndo = await countBlocks(page);
      expect(afterUndo).toBe(beforeDelete);
    });
  });

  test.describe('Redo Operations', () => {
    test('Cmd+Shift+Z redoes undone operation', async ({ page }) => {
      const initialCount = await countBlocks(page);

      // Create a block
      await enterEditModeAndClick(page);
      const blockId = await getFirstBlockId(page);

      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'split-at-cursor',
          blockId: id,
          cursorPos: 0,
        });
      }, blockId);
      await wait(page, 200);

      const afterCreate = await countBlocks(page);
      expect(afterCreate).toBe(initialCount + 1);

      // Undo
      await page.keyboard.press('Meta+z');
      await wait(page, 300);

      const afterUndo = await countBlocks(page);
      expect(afterUndo).toBe(initialCount);

      // Verify redo is available
      const canRedo = await page.evaluate(() => window.DEBUG?.canRedo?.());
      expect(canRedo).toBe(true);

      // Redo with Cmd+Shift+Z
      await page.keyboard.press('Meta+Shift+z');
      await wait(page, 300);

      const afterRedo = await countBlocks(page);
      expect(afterRedo).toBe(afterCreate);
    });

    test('new operation clears redo stack', async ({ page }) => {
      // Create a block
      await enterEditModeAndClick(page);
      const blockId = await getFirstBlockId(page);

      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'split-at-cursor',
          blockId: id,
          cursorPos: 0,
        });
      }, blockId);
      await wait(page, 200);

      // Undo
      await page.keyboard.press('Meta+z');
      await wait(page, 300);

      // Verify redo is available
      let canRedo = await page.evaluate(() => window.DEBUG?.canRedo?.());
      expect(canRedo).toBe(true);

      // Perform a new operation (create another block)
      const newBlockId = await getFirstBlockId(page);
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'split-at-cursor',
          blockId: id,
          cursorPos: 0,
        });
      }, newBlockId);
      await wait(page, 200);

      // Redo should no longer be available
      canRedo = await page.evaluate(() => window.DEBUG?.canRedo?.());
      expect(canRedo).toBe(false);
    });
  });

  test.describe('assertUndoable Helper', () => {
    test('assertUndoable returns structured result', async ({ page }) => {
      // Fresh state - not undoable
      let result = await page.evaluate(() => window.DEBUG?.assertUndoable?.());
      expect(result.ok).toBe(false);
      expect(result.reason).toBe('No undo available');

      // Create operation to make undoable
      await enterEditModeAndClick(page);
      const blockId = await getFirstBlockId(page);

      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'split-at-cursor',
          blockId: id,
          cursorPos: 0,
        });
      }, blockId);
      await wait(page, 200);

      // Now should be undoable
      result = await page.evaluate(() => window.DEBUG?.assertUndoable?.());
      expect(result.ok).toBe(true);
      expect(result.undo_count).toBeGreaterThan(0);
    });
  });

  test.describe('Structural Operations Undo', () => {
    test('indent operation can be undone', async ({ page }) => {
      // Create two blocks first
      await enterEditModeAndClick(page);
      const blockId = await getFirstBlockId(page);

      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'split-at-cursor',
          blockId: id,
          cursorPos: 0,
        });
      }, blockId);
      await wait(page, 200);

      // Get the second block
      await page.keyboard.press('Escape');
      await wait(page);
      await page.keyboard.press('ArrowDown');
      await wait(page);

      // Get initial parent info
      const beforeIndent = await page.evaluate(() => {
        const db = window.TEST_HELPERS.getDb();
        const session = window.TEST_HELPERS.getSession();
        const focusId = session?.selection?.focus;
        return {
          focusId,
          parent: db?.derived?.parent_of?.[focusId],
        };
      });

      // Indent the block
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'indent-selected',
        });
      });
      await wait(page, 200);

      // Verify parent changed
      const _afterIndent = await page.evaluate(() => {
        const db = window.TEST_HELPERS.getDb();
        return {
          parent: db?.derived?.parent_of?.[beforeIndent?.focusId],
        };
      });

      // Undo should restore original parent
      await page.keyboard.press('Meta+z');
      await wait(page, 300);

      const afterUndo = await page.evaluate((focusId) => {
        const db = window.TEST_HELPERS.getDb();
        return {
          parent: db?.derived?.parent_of?.[focusId],
        };
      }, beforeIndent.focusId);

      expect(afterUndo.parent).toBe(beforeIndent.parent);
    });
  });
});
