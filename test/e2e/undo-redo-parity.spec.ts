import { test, expect } from '@playwright/test';

/**
 * E2E Tests for Undo/Redo Cursor and Selection Restoration (FR-Undo-01)
 *
 * LOGSEQ PARITY: Undo/redo must restore not just content, but also:
 * - Editing block ID
 * - Cursor position within the block
 * - Selection state (if any)
 *
 * Reference: dev/specs/LOGSEQ_SPEC.md §9.8
 */

test.describe('Undo/Redo Cursor Restoration (FR-Undo-01)', () => {
  test.beforeEach(async ({ page }) => {
    // Load with test mode to get empty database
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('networkidle');

    // Wait for the app to be ready
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('undo restores cursor position after text edit', async ({ page }) => {
    // Get first block
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();

    // Enter edit mode and type some text
    await page.keyboard.press('Enter');
    await page.keyboard.type('Hello World');

    // Move cursor to middle (after "Hello")
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('ArrowLeft');
    }

    // Type something at cursor position
    await page.keyboard.type(' Beautiful');

    // Verify text is "Hello Beautiful World"
    await expect(firstBlock).toContainText('Hello Beautiful World');

    // Undo the edit (Cmd+Z on Mac, Ctrl+Z elsewhere)
    const isMac = process.platform === 'darwin';
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');

    // Verify:
    // 1. Text is back to "Hello World"
    await expect(firstBlock).toContainText('Hello World');
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
    // Get first block and enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

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
    // Setup: Type text, undo, then redo
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    await page.keyboard.type('Test');

    const isMac = process.platform === 'darwin';

    // Undo
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    await expect(firstBlock).not.toContainText('Test');

    // Redo
    await page.keyboard.press(isMac ? 'Meta+Shift+z' : 'Control+Shift+z');

    // Verify:
    // 1. Text is back
    await expect(firstBlock).toContainText('Test');

    // 2. Cursor is at end of "Test" (where it was when we made the edit)
    const cursorPos = await page.evaluate(() => {
      const sel = window.getSelection();
      return sel?.anchorOffset;
    });
    expect(cursorPos).toBe(4); // After "Test"
  });

  test('undo restores cursor after merge (Backspace at start)', async ({ page }) => {
    // Create two blocks
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

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
    // Make several edits, undo them all, then redo them all
    // Cursor should be restored correctly at each step
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    const isMac = process.platform === 'darwin';

    // Edit 1: Type "A"
    await page.keyboard.type('A');
    const posAfterA = 1;

    // Edit 2: Type "B"
    await page.keyboard.type('B');
    const posAfterAB = 2;

    // Edit 3: Type "C"
    await page.keyboard.type('C');

    // Should have "ABC"
    await expect(firstBlock).toContainText('ABC');

    // Undo edit 3 (remove "C")
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    await expect(firstBlock).toContainText('AB');
    let cursorPos = await page.evaluate(() => window.getSelection()?.anchorOffset);
    expect(cursorPos).toBe(posAfterAB);

    // Undo edit 2 (remove "B")
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    await expect(firstBlock).toContainText('A');
    cursorPos = await page.evaluate(() => window.getSelection()?.anchorOffset);
    expect(cursorPos).toBe(posAfterA);

    // Undo edit 1 (remove "A")
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    await expect(firstBlock).not.toContainText('A');

    // Now redo all
    // Redo edit 1 (add "A" back)
    await page.keyboard.press(isMac ? 'Meta+Shift+z' : 'Control+Shift+z');
    await expect(firstBlock).toContainText('A');
    cursorPos = await page.evaluate(() => window.getSelection()?.anchorOffset);
    expect(cursorPos).toBe(posAfterA);

    // Redo edit 2 (add "B" back)
    await page.keyboard.press(isMac ? 'Meta+Shift+z' : 'Control+Shift+z');
    await expect(firstBlock).toContainText('AB');
    cursorPos = await page.evaluate(() => window.getSelection()?.anchorOffset);
    expect(cursorPos).toBe(posAfterAB);

    // Redo edit 3 (add "C" back)
    await page.keyboard.press(isMac ? 'Meta+Shift+z' : 'Control+Shift+z');
    await expect(firstBlock).toContainText('ABC');
  });

  test('undo after block movement restores position', async ({ page }) => {
    // Create two blocks
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

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
