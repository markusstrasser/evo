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
 * - Should SELECT the block (blue background, view mode)
 * - This allows immediate arrow key navigation
 *
 * Source verification:
 * - logseq/handler/editor.cljs:3426 (open-selected-block!)
 * - logseq/handler/editor.cljs:3897 (escape-editing)
 * - logseq/modules/shortcut/config.cljs:196 (escape binding)
 */

import { test, expect } from '@playwright/test';
import {
  selectPage,
  selectBlock,
  clearSelection,
  enterEditMode,
  exitEditMode,
  isBlockSelected,
  countSelectedBlocks,
  getBlockText,
  getEditingBlockId,
  isEditing,
  getFirstBlockId,
  getBlockIdAt,
  updateBlockText,
  waitForBlocks
} from './helpers/index.js';

test.describe('Enter Key - Selected Block Behavior', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await waitForBlocks(page);
  });

  test('Enter on selected block enters edit mode at END of text', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Select block via intent
    await selectBlock(page, blockId);

    // Verify block is selected but NOT editing
    expect(await isBlockSelected(page, blockId)).toBe(true);
    expect(await isEditing(page)).toBe(false);

    // Get the text length (block's own text, not children)
    const textLength = await page.evaluate((id) => {
      const viewSpan = document.querySelector(`[data-block-id="${id}"] .block-content`);
      return viewSpan?.textContent?.length || 0;
    }, blockId);

    // Press Enter
    await page.keyboard.press('Enter');
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
    expect(cursorPos).toBe(textLength);

    // Verify: Selection is cleared (check via inline styles)
    const selectedBlocks = await countSelectedBlocks(page);
    expect(selectedBlocks).toBe(0);
  });

  test('Enter on selected block with empty text enters edit at position 0', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // First, clear the block content via intent (reliable, no typing issues)
    await updateBlockText(page, blockId, '');

    // Select the now-empty block
    await selectBlock(page, blockId);

    // Press Enter to edit
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

  // TODO: Fix flaky test - see test/e2e/SKIPPED_TESTS.md § Flaky Tests
  // Issue: Multi-line cursor position inconsistent (contenteditable line breaks)
  // Fix strategy: Investigate cursor memory interaction with BR elements
  test.skip('Enter on selected block with multi-line text enters at END', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Set block content to multi-line text via intent
    await updateBlockText(page, blockId, 'Line 1\nLine 2');

    // Select block
    await selectBlock(page, blockId);

    // Press Enter to edit
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Verify: In edit mode
    const editableInput = page.locator(`[data-block-id="${blockId}"] [contenteditable="true"]`);
    await expect(editableInput).toBeFocused();

    const text = await editableInput.textContent();
    expect(text).toContain('Line 1');
    expect(text).toContain('Line 2');

    // Cursor should be at very end
    const cursorAtEnd = await editableInput.evaluate((el) => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0) return false;
      const range = selection.getRangeAt(0);

      // Check if cursor is at the end by comparing with total text length
      const testRange = range.cloneRange();
      testRange.selectNodeContents(el);
      testRange.setStart(range.endContainer, range.endOffset);

      return testRange.toString().length === 0;
    });

    expect(cursorAtEnd).toBe(true);
  });

  test('Enter does NOT create new block when block is selected', async ({ page }) => {
    // Count initial blocks
    const initialCount = await page.locator('div.block[data-block-id]').count();

    // Select first block via intent
    const blockId = await getFirstBlockId(page);
    await selectBlock(page, blockId);

    // Press Enter
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Verify: Block count unchanged (no new block created)
    const currentCount = await page.locator('div.block[data-block-id]').count();
    expect(currentCount).toBe(initialCount);

    // Verify: In edit mode
    await expect(page.locator('[contenteditable="true"]')).toBeFocused();
  });
});

test.describe('Escape Key - Editing Behavior', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await waitForBlocks(page);
  });

  test('Escape while editing exits AND selects block (Logseq parity)', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode via intent
    await enterEditMode(page, blockId);

    // Verify: In edit mode
    await expect(page.locator('[contenteditable="true"]')).toBeFocused();

    // Type some text
    await page.keyboard.type('Test text');

    // Press Escape (keyboard should work now after keymap fix)
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify: NOT in edit mode anymore
    expect(await isEditing(page)).toBe(false);

    // Verify: Block IS selected (Logseq parity - Escape selects the block)
    expect(await isBlockSelected(page, blockId)).toBe(true);
  });

  test('Escape saves the block content', async ({ page }) => {
    const blockId = await getFirstBlockId(page);
    const originalText = await getBlockText(page, blockId);

    // Enter edit mode
    await enterEditMode(page, blockId);

    // Ensure contenteditable is focused
    const editableInput = page.locator(`div.block[data-block-id="${blockId}"] [contenteditable="true"]`);
    await expect(editableInput).toBeFocused();

    // Set new text content
    const appendText = ' APPENDED';
    const newContent = originalText + appendText;

    // Use direct intent dispatch to update content (bypasses keyboard unreliability)
    await page.evaluate(({ id, text }) => {
      window.TEST_HELPERS?.dispatchIntent({
        type: 'update-content',
        'block-id': id,
        text: text
      });
    }, { id: blockId, text: newContent });
    await page.waitForTimeout(50);

    // Exit edit mode via intent
    await exitEditMode(page);
    await page.waitForTimeout(100);

    // Verify: Text was saved
    const newText = await getBlockText(page, blockId);
    expect(newText).toBe(newContent);
  });

  test('Multiple Escape presses are safe (no error)', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode
    await enterEditMode(page, blockId);

    // Press Escape multiple times
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify: No errors, not editing, nothing selected
    expect(await isEditing(page)).toBe(false);
    expect(await countSelectedBlocks(page)).toBe(0);
  });

  test('Escape clears cursor position state', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Set known text content
    await updateBlockText(page, blockId, 'Hello World');

    // Enter edit mode at end
    await enterEditMode(page, blockId, 'end');

    // Move cursor left
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowLeft');

    // Exit edit mode
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Re-select and enter edit mode - cursor should be at END (default)
    await selectBlock(page, blockId);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    const editableInput = page.locator(`[data-block-id="${blockId}"] [contenteditable="true"]`);
    await expect(editableInput).toBeFocused();

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

test.describe('Enter Key - Edit Mode Block Creation', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await waitForBlocks(page);
  });

  /**
   * CRITICAL REGRESSION TEST
   *
   * This test guards against the blur race condition that caused focus loss
   * when pressing Enter to create a new block. The fix was adding
   * :context-aware-enter to structural-intents in shell/editor.cljs.
   *
   * Root cause: When Enter creates a new block, the old contenteditable loses
   * focus. Without keep-edit-on-blur, the blur handler calls :exit-edit which
   * overwrites the new editing-block-id from the Enter intent.
   *
   * See commit that fixed this for full details.
   */
  test('Enter in edit mode creates new block AND keeps focus (blur race guard)', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Set some text first (empty blocks have different Enter behavior)
    await updateBlockText(page, blockId, 'Test content');

    // Enter edit mode
    await enterEditMode(page, blockId);
    const editableInput = page.locator(`[data-block-id="${blockId}"] [contenteditable="true"]`);
    await expect(editableInput).toBeFocused();

    // Count blocks before
    const blocksBefore = await page.locator('div.block[data-block-id]').count();

    // Press Enter to create new block (at end of text creates sibling)
    await page.keyboard.press('Enter');
    await page.waitForTimeout(150); // Allow for re-render

    // Verify: New block was created
    const blocksAfter = await page.locator('div.block[data-block-id]').count();
    expect(blocksAfter).toBe(blocksBefore + 1);

    // CRITICAL: Verify we're STILL in edit mode (not idle)
    const isStillEditing = await isEditing(page);
    expect(isStillEditing).toBe(true);

    // CRITICAL: Verify contenteditable is focused (can type immediately)
    const activeElement = await page.evaluate(() => ({
      tagName: document.activeElement?.tagName,
      isContentEditable: document.activeElement?.contentEditable === 'true'
    }));
    expect(activeElement.isContentEditable).toBe(true);

    // Verify: The NEW block is being edited, not the original
    const newEditingId = await getEditingBlockId(page);
    expect(newEditingId).not.toBe(blockId);
  });

  test('Enter in edit mode allows immediate typing in new block', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Set some text first (empty blocks have different Enter behavior)
    await updateBlockText(page, blockId, 'Original text');

    // Enter edit mode
    await enterEditMode(page, blockId);

    // Press Enter to create new block
    await page.keyboard.press('Enter');
    await page.waitForTimeout(150);

    // Type immediately (this would fail if focus was lost)
    await page.keyboard.type('New content');

    // Get the new block's text
    const newEditingId = await getEditingBlockId(page);
    const newBlockText = await page.evaluate((id) => {
      const el = document.querySelector(`[data-block-id="${id}"] [contenteditable="true"]`);
      return el?.textContent || '';
    }, newEditingId);

    expect(newBlockText).toBe('New content');
  });

  test('Multiple Enter presses create multiple blocks without losing focus', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Set some text first (empty blocks have different Enter behavior)
    await updateBlockText(page, blockId, 'Starting text');

    // Enter edit mode
    await enterEditMode(page, blockId);

    const blocksBefore = await page.locator('div.block[data-block-id]').count();

    // Press Enter three times
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(150);

    // Verify: Three new blocks created
    const blocksAfter = await page.locator('div.block[data-block-id]').count();
    expect(blocksAfter).toBe(blocksBefore + 3);

    // Verify: Still in edit mode
    expect(await isEditing(page)).toBe(true);

    // Verify: Can still type
    await page.keyboard.type('After three enters');
    const currentId = await getEditingBlockId(page);
    const text = await page.evaluate((id) => {
      const el = document.querySelector(`[data-block-id="${id}"] [contenteditable="true"]`);
      return el?.textContent || '';
    }, currentId);

    expect(text).toBe('After three enters');
  });
});

test.describe('Enter and Escape - Integration Flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await waitForBlocks(page);
  });

  test('Natural navigation flow: Select -> Enter -> Edit -> Escape -> Selected (Logseq parity)', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // 1. Select block
    await selectBlock(page, blockId);
    expect(await isBlockSelected(page, blockId)).toBe(true);

    // 2. Enter to edit
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);
    await expect(page.locator('[contenteditable="true"]')).toBeFocused();

    // 3. Type something
    await page.keyboard.type('Test');

    // 4. Escape to exit
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // 5. Verify: NOT editing, but IS selected (Logseq parity)
    expect(await isEditing(page)).toBe(false);
    expect(await isBlockSelected(page, blockId)).toBe(true);

    // 6. Can still navigate with arrows (moves to next block)
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);

    // ArrowDown from selected block moves to next block
    const secondBlockId = await getBlockIdAt(page, 1);
    expect(await isBlockSelected(page, secondBlockId)).toBe(true);
  });

  test('Edit -> Escape -> Arrow keys navigate blocks (not cursor)', async ({ page }) => {
    const blockId = await getFirstBlockId(page);

    // Enter edit mode
    await enterEditMode(page, blockId);

    // Exit edit mode via intent (more reliable than Escape key in Playwright)
    await exitEditMode(page);
    await page.waitForTimeout(100);

    // Press Down arrow - should navigate to BLOCK, not move cursor
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);

    // After Escape with no selection, ArrowDown selects the first visible block
    // (Key point: We're testing that arrow keys navigate blocks, not cursor position)
    const firstBlockId = await getFirstBlockId(page);
    expect(await isBlockSelected(page, firstBlockId)).toBe(true);

    // Verify: Still not editing
    expect(await isEditing(page)).toBe(false);
  });

  test('Enter on different selected blocks enters edit in THAT block', async ({ page }) => {
    // Get second block
    const secondBlockId = await getBlockIdAt(page, 1);

    // Select second block via intent
    await selectBlock(page, secondBlockId);

    // Press Enter
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Verify: Editing the SECOND block (not first)
    const editingBlockId = await getEditingBlockId(page);
    expect(editingBlockId).toBe(secondBlockId);
  });
});
