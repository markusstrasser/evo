/**
 * Enter and Escape Key Behavior Tests - Logseq Parity
 *
 * Verifies that Enter and Escape keys behave exactly like Logseq:
 *
 * Enter on selected block:
 * - Should enter edit mode in that block
 * - Cursor should be at END of text
 * - Selection should be cleared
 *
 * Escape while editing:
 * - Should exit edit mode
 * - Should NOT select the block
 * - Cursor should disappear
 *
 * Source verification:
 * - logseq/handler/editor.cljs:3426 (open-selected-block!)
 * - logseq/handler/editor.cljs:3897 (escape-editing)
 * - logseq/modules/shortcut/config.cljs:196 (escape binding)
 */

import { test, expect } from '@playwright/test';

test.describe('Enter Key - Selected Block Behavior', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8080');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('Enter on selected block enters edit mode at END of text', async ({ page }) => {
    // Create test blocks
    const blocks = await page.locator('[data-block-id]').all();
    const firstBlock = blocks[0];
    const blockId = await firstBlock.getAttribute('data-block-id');

    // Click to select block (not edit)
    await firstBlock.click();

    // Verify block is selected but NOT editing
    const isSelected = await page.locator(`[data-block-id="${blockId}"].selected`).count() > 0;
    const isEditing = await page.locator(`[data-block-id="${blockId}"] [contenteditable="true"]`).count() > 0;

    expect(isSelected).toBe(true);
    expect(isEditing).toBe(false);

    // Get the text content
    const textContent = await firstBlock.textContent();
    const textLength = textContent?.trim().length || 0;

    // Press Enter
    await page.keyboard.press('Enter');

    // Wait for edit mode
    await page.waitForTimeout(100);

    // Verify: Now in edit mode
    const editableInput = page.locator(`[data-block-id="${blockId}"] [contenteditable="true"]`);
    await expect(editableInput).toBeFocused();

    // Verify: Cursor is at END of text
    const cursorPos = await editableInput.evaluate((el) => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0) return -1;
      const range = selection.getRangeAt(0);
      return range.startOffset;
    });

    // In contenteditable, cursor position is relative to text node
    // At end of text, cursor offset should equal text length
    expect(cursorPos).toBe(textLength);

    // Verify: Selection is cleared
    const selectedBlocks = await page.locator('.selected').count();
    expect(selectedBlocks).toBe(0);
  });

  test('Enter on selected block with empty text enters edit at position 0', async ({ page }) => {
    // Create empty block by pressing Enter twice
    const blocks = await page.locator('[data-block-id]').all();
    const firstBlock = blocks[0];

    // Click to select
    await firstBlock.click();

    // Clear the block
    await page.keyboard.press('Enter');
    await page.keyboard.press('Backspace');
    await page.keyboard.press('Escape');

    // Now we have an empty selected block
    const blockId = await firstBlock.getAttribute('data-block-id');

    // Press Enter
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Verify: In edit mode at position 0
    const editableInput = page.locator(`[data-block-id="${blockId}"] [contenteditable="true"]`);
    await expect(editableInput).toBeFocused();

    const cursorPos = await editableInput.evaluate((el) => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0) return -1;
      return selection.getRangeAt(0).startOffset;
    });

    expect(cursorPos).toBe(0);
  });

  test('Enter on selected block with multi-line text enters at END', async ({ page }) => {
    // Create block with newlines (using Shift+Enter)
    const blocks = await page.locator('[data-block-id]').all();
    const firstBlock = blocks[0];

    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Type multi-line text
    await page.keyboard.type('Line 1');
    await page.keyboard.press('Shift+Enter');
    await page.keyboard.type('Line 2');

    const fullText = 'Line 1\nLine 2';

    // Exit edit mode
    await page.keyboard.press('Escape');

    // Select block again
    await firstBlock.click();

    // Press Enter
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Verify: Cursor at end (after "Line 2")
    const editableInput = page.locator('[contenteditable="true"]').first();
    const text = await editableInput.textContent();

    expect(text).toContain('Line 1');
    expect(text).toContain('Line 2');

    // Cursor should be at very end
    const cursorAtEnd = await editableInput.evaluate((el) => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0) return false;
      const range = selection.getRangeAt(0);

      // Check if cursor is at the end by trying to move forward
      const testRange = range.cloneRange();
      testRange.selectNodeContents(el);
      testRange.setStart(range.endContainer, range.endOffset);

      return testRange.toString().length === 0;
    });

    expect(cursorAtEnd).toBe(true);
  });

  test('Enter does NOT create new block when block is selected', async ({ page }) => {
    // Count initial blocks
    const initialCount = await page.locator('[data-block-id]').count();

    // Select first block
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();

    // Press Enter
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Verify: Block count unchanged (no new block created)
    const currentCount = await page.locator('[data-block-id]').count();
    expect(currentCount).toBe(initialCount);

    // Verify: In edit mode
    await expect(page.locator('[contenteditable="true"]')).toBeFocused();
  });
});

test.describe('Escape Key - Editing Behavior', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8080');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('Escape while editing exits WITHOUT selecting block', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    const blockId = await firstBlock.getAttribute('data-block-id');

    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Verify: In edit mode
    await expect(page.locator('[contenteditable="true"]')).toBeFocused();

    // Type some text
    await page.keyboard.type('Test text');

    // Press Escape
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify: NOT in edit mode anymore
    const isEditing = await page.locator('[contenteditable="true"]').count() > 0;
    expect(isEditing).toBe(false);

    // Verify: Block is NOT selected (this is the key difference from wrong implementation)
    const selectedBlocks = await page.locator('.selected, [data-selected="true"]').count();
    expect(selectedBlocks).toBe(0);

    // Verify: No focus anywhere
    const focusedElement = await page.evaluate(() => document.activeElement?.tagName);
    expect(focusedElement).toBe('BODY'); // Focus should return to body
  });

  test('Escape saves the block content', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();

    // Enter edit mode
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Type new text
    const testText = 'New content ' + Date.now();
    await page.keyboard.type(testText);

    // Press Escape
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify: Text was saved
    const blockText = await firstBlock.textContent();
    expect(blockText).toContain(testText);
  });

  test('Multiple Escape presses are safe (no error)', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();

    // Enter edit mode
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Press Escape multiple times
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify: No errors, still not editing, nothing selected
    const isEditing = await page.locator('[contenteditable="true"]').count() > 0;
    const selectedCount = await page.locator('.selected').count();

    expect(isEditing).toBe(false);
    expect(selectedCount).toBe(0);
  });

  test('Escape clears cursor position state', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();

    // Enter edit mode
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Move cursor
    await page.keyboard.type('Hello');
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowLeft');

    // Press Escape
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Enter edit mode again - cursor should be at END (default), not where it was
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    const editableInput = page.locator('[contenteditable="true"]').first();

    // Verify cursor is at END (not at the position before Escape)
    const cursorAtEnd = await editableInput.evaluate((el) => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0) return false;
      const range = selection.getRangeAt(0);

      const testRange = range.cloneRange();
      testRange.selectNodeContents(el);
      testRange.setStart(range.endContainer, range.endOffset);

      return testRange.toString().length === 0;
    });

    expect(cursorAtEnd).toBe(true);
  });
});

test.describe('Enter and Escape - Integration Flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8080');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('Natural navigation flow: Select → Enter → Edit → Escape → Not selected', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();
    const blockId = await firstBlock.getAttribute('data-block-id');

    // 1. Select block
    await firstBlock.click();
    let selectedCount = await page.locator(`[data-block-id="${blockId}"].selected`).count();
    expect(selectedCount).toBeGreaterThan(0);

    // 2. Enter to edit
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);
    await expect(page.locator('[contenteditable="true"]')).toBeFocused();

    // 3. Type something
    await page.keyboard.type('Test');

    // 4. Escape to exit
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // 5. Verify: NOT editing, NOT selected
    const isEditing = await page.locator('[contenteditable="true"]').count() > 0;
    selectedCount = await page.locator('.selected').count();

    expect(isEditing).toBe(false);
    expect(selectedCount).toBe(0);

    // 6. Can still navigate with arrows
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);

    // Should select next block
    const secondBlock = page.locator('[data-block-id]').nth(1);
    const isSecondSelected = await secondBlock.evaluate((el) =>
      el.classList.contains('selected') || el.hasAttribute('data-selected')
    );

    expect(isSecondSelected).toBe(true);
  });

  test('Edit → Escape → Arrow keys navigate blocks (not cursor)', async ({ page }) => {
    const firstBlock = page.locator('[data-block-id]').first();

    // Enter edit mode
    await firstBlock.click();
    await page.keyboard.press('Enter');
    await page.keyboard.type('Hello');

    // Escape
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Press Down arrow - should navigate to next BLOCK, not move cursor
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);

    // Verify: Second block is now selected
    const blocks = await page.locator('[data-block-id]').all();
    if (blocks.length > 1) {
      const secondBlockId = await blocks[1].getAttribute('data-block-id');
      const isSelected = await page.locator(`[data-block-id="${secondBlockId}"].selected`).count() > 0;
      expect(isSelected).toBe(true);
    }

    // Verify: Still not editing
    const isEditing = await page.locator('[contenteditable="true"]').count() > 0;
    expect(isEditing).toBe(false);
  });

  test('Enter on different selected blocks enters edit in THAT block', async ({ page }) => {
    const blocks = await page.locator('[data-block-id]').all();
    if (blocks.length < 2) {
      // Create another block
      await blocks[0].click();
      await page.keyboard.press('Enter');
      await page.keyboard.type('Second block');
      await page.keyboard.press('Escape');
    }

    // Select second block
    const secondBlock = page.locator('[data-block-id]').nth(1);
    await secondBlock.click();

    const secondBlockId = await secondBlock.getAttribute('data-block-id');

    // Press Enter
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Verify: Editing the SECOND block (not first)
    const editingBlockId = await page.evaluate(() => {
      const editable = document.querySelector('[contenteditable="true"]');
      return editable?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });

    expect(editingBlockId).toBe(secondBlockId);
  });
});
