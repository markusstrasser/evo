import { test, expect } from '@playwright/test';
import {
  selectPage,
  selectBlock,
  enterEditMode,
  waitForBlocks,
  getFirstBlockId
} from './helpers/index.js';

/**
 * E2E Tests for Undo/Redo Cursor and Selection Restoration (FR-Undo-01)
 *
 * LOGSEQ PARITY: Undo/redo must restore not just content, but also:
 * - Editing block ID
 * - Cursor position within the block
 * - Selection state (if any)
 *
 * Reference: docs/LOGSEQ_SPEC.md §9.8
 *
 * NOTE: These tests use real keyboard events (not intent dispatch) because
 * we're testing the actual undo/redo behavior including cursor restoration.
 */

test.describe('Undo/Redo Cursor Restoration (FR-Undo-01)', () => {
  test.beforeEach(async ({ page }) => {
    // Load with test mode to get empty database
    // Test mode automatically sets up test-page with one empty block
    // Don't call selectPage() as that would switch to non-existent 'Projects' page
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('networkidle');
    await waitForBlocks(page);
  });

  test('undo restores cursor position after text edit', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode via intent (reliable)
    await enterEditMode(page, blockId);

    // Type some text (real keyboard for testing input flow)
    await page.keyboard.type('Hello World');

    // Move cursor to middle (after "Hello")
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('ArrowLeft');
    }

    // Type something at cursor position
    await page.keyboard.type(' Beautiful');

    // Verify text is "Hello Beautiful World"
    const block = page.locator(`[data-block-id="${blockId}"]`);
    await expect(block).toContainText('Hello Beautiful World');

    // Undo the edit (Cmd+Z on Mac, Ctrl+Z elsewhere)
    const isMac = process.platform === 'darwin';
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');

    // Verify:
    // 1. Text is back to "Hello World"
    await expect(block).toContainText('Hello World');

    // 2. Cursor is still in edit mode at the same position (after "Hello")
    const selection = await page.evaluate(() => {
      const sel = window.getSelection();
      return {
        isCollapsed: sel?.isCollapsed,
        offset: sel?.anchorOffset
      };
    });
    expect(selection.isCollapsed).toBe(true);
    expect(selection.offset).toBe(5); // After "Hello"
  });

  test('undo restores editing block after Enter split', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode
    await enterEditMode(page, blockId);

    // Type text and split the block
    await page.keyboard.type('First part');
    await page.keyboard.press('Enter'); // Creates new block
    await page.keyboard.type('Second part');

    // Now we should be editing the second block
    // Undo the split (should restore us to editing first block)
    const isMac = process.platform === 'darwin';
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');

    // Verify:
    // 1. Only one block with "First part" exists
    const blocks = page.locator('[data-block-id]');
    await expect(blocks).toHaveCount(1);
    await expect(blocks.first()).toContainText('First part');

    // 2. Cursor is at the position where Enter was pressed (end of "First part")
    const cursorPos = await page.evaluate(() => {
      const sel = window.getSelection();
      return sel?.anchorOffset;
    });
    expect(cursorPos).toBe(10); // After "First part"
  });

  test('redo restores cursor position', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode
    await enterEditMode(page, blockId);

    await page.keyboard.type('Test');

    const isMac = process.platform === 'darwin';

    // Undo
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    const block = page.locator(`[data-block-id="${blockId}"]`);
    await expect(block).not.toContainText('Test');

    // Redo
    await page.keyboard.press(isMac ? 'Meta+Shift+z' : 'Control+Shift+z');

    // Verify:
    // 1. Text is back
    await expect(block).toContainText('Test');

    // 2. Cursor is at end of "Test" (where it was when we made the edit)
    const cursorPos = await page.evaluate(() => {
      const sel = window.getSelection();
      return sel?.anchorOffset;
    });
    expect(cursorPos).toBe(4); // After "Test"
  });

  test('undo restores cursor after merge (Backspace at start)', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode
    await enterEditMode(page, blockId);

    await page.keyboard.type('First');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Second');

    // Move cursor to start of second block
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('ArrowLeft');
    }

    // Merge blocks (Backspace at start)
    await page.keyboard.press('Backspace');

    // Verify merged: "FirstSecond"
    const blocks = page.locator('[data-block-id]');
    await expect(blocks.first()).toContainText('FirstSecond');

    // Undo
    const isMac = process.platform === 'darwin';
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');

    // Verify:
    // 1. Two blocks again
    await expect(blocks).toHaveCount(2);

    // 2. Cursor is at start of second block (where it was when we pressed Backspace)
    const cursorPos = await page.evaluate(() => {
      const sel = window.getSelection();
      return {
        offset: sel?.anchorOffset,
        text: sel?.anchorNode?.textContent
      };
    });
    expect(cursorPos.text).toBe('Second');
    expect(cursorPos.offset).toBe(0); // At start
  });

  test('undo/redo cycle maintains cursor position', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode
    await enterEditMode(page, blockId);

    const isMac = process.platform === 'darwin';
    const block = page.locator(`[data-block-id="${blockId}"]`);

    // Edit 1: Type "A"
    await page.keyboard.type('A');
    const posAfterA = 1;

    // Edit 2: Type "B"
    await page.keyboard.type('B');
    const posAfterAB = 2;

    // Edit 3: Type "C"
    await page.keyboard.type('C');

    // Should have "ABC"
    await expect(block).toContainText('ABC');

    // Undo edit 3 (remove "C")
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    await expect(block).toContainText('AB');
    let cursorPos = await page.evaluate(() => window.getSelection()?.anchorOffset);
    expect(cursorPos).toBe(posAfterAB);

    // Undo edit 2 (remove "B")
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    await expect(block).toContainText('A');
    cursorPos = await page.evaluate(() => window.getSelection()?.anchorOffset);
    expect(cursorPos).toBe(posAfterA);

    // Undo edit 1 (remove "A")
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    await expect(block).not.toContainText('A');

    // Now redo all
    // Redo edit 1 (add "A" back)
    await page.keyboard.press(isMac ? 'Meta+Shift+z' : 'Control+Shift+z');
    await expect(block).toContainText('A');
    cursorPos = await page.evaluate(() => window.getSelection()?.anchorOffset);
    expect(cursorPos).toBe(posAfterA);

    // Redo edit 2 (add "B" back)
    await page.keyboard.press(isMac ? 'Meta+Shift+z' : 'Control+Shift+z');
    await expect(block).toContainText('AB');
    cursorPos = await page.evaluate(() => window.getSelection()?.anchorOffset);
    expect(cursorPos).toBe(posAfterAB);

    // Redo edit 3 (add "C" back)
    await page.keyboard.press(isMac ? 'Meta+Shift+z' : 'Control+Shift+z');
    await expect(block).toContainText('ABC');
  });

  test('undo after block movement restores position', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode
    await enterEditMode(page, blockId);

    await page.keyboard.type('Block 1');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Block 2');

    // Move Block 2 up (Cmd+Shift+ArrowUp)
    const isMac = process.platform === 'darwin';
    await page.keyboard.press(isMac ? 'Meta+Shift+ArrowUp' : 'Alt+Shift+ArrowUp');

    // Verify order changed
    const blocks = page.locator('[data-block-id]');
    await expect(blocks.first()).toContainText('Block 2');
    await expect(blocks.nth(1)).toContainText('Block 1');

    // Undo
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');

    // Verify:
    // 1. Order restored
    await expect(blocks.first()).toContainText('Block 1');
    await expect(blocks.nth(1)).toContainText('Block 2');

    // 2. Cursor is still editing "Block 2" (the block that was moved)
    const editingText = await page.evaluate(() => {
      const sel = window.getSelection();
      return sel?.anchorNode?.textContent;
    });
    expect(editingText).toContain('Block 2');
  });
});
