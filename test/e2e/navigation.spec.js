/**
 * Block Navigation E2E Tests
 *
 * Tests for arrow key navigation and cursor position preservation.
 */

import { test, expect } from '@playwright/test';
import { getCursorPosition, setCursorPosition } from './helpers/cursor.js';
import { getAllBlocks } from './helpers/blocks.js';
import { enterEditModeAndClick } from './helpers/edit-mode.js';

const NAV_PARENT_HOP = 'NAV-BOUNDARY-LEFT-01';

test.describe('Block Navigation', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/blocks.html');
    await enterEditModeAndClick(page);
    await page.waitForFunction(() => typeof window.DEBUG?.state === 'function');
  });

  test('arrow down preserves cursor column', async ({ page }) => {
    // Create two blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Hello world this is a long line');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Short line');

    // Navigate up to first block
    await page.keyboard.press('ArrowUp');

    // Set cursor at position 10
    const blocks = await getAllBlocks(page);
    await setCursorPosition(page, blocks[0].id, 10);

    // Navigate down
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);

    const cursor = await getCursorPosition(page);

    // Cursor should be at same column (or end if shorter)
    expect(cursor.offset).toBe(10);
    expect(cursor.elementId).not.toBe(blocks[0].id);
  });

  test('arrow up to shorter block goes to end', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Short');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Very long line with lots of text');

    // Set cursor at position 20 in second block
    const blocks = await getAllBlocks(page);
    await setCursorPosition(page, blocks[1].id, 20);

    // Navigate up (first block only has 5 chars)
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);

    const cursor = await getCursorPosition(page);
    // Should be at end of shorter block
    expect(cursor.offset).toBe(5);
    expect(cursor.text).toBe('Short');
  });

  test('REGRESSION: navigation does not trigger duplicate handlers', async ({ page }) => {
    // Track navigation events
    await page.evaluate(() => {
      window.navigationEvents = [];
      document.addEventListener('keydown', (e) => {
        if (e.key.startsWith('Arrow')) {
          window.navigationEvents.push(e.key);
        }
      });
    });

    // Create blocks and navigate
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('First');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Second');

    // Clear events
    await page.evaluate(() => { window.navigationEvents = []; });

    // Navigate once
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);

    // Check that only one block change occurred
    const blocksBefore = await getAllBlocks(page);
    const focusedBefore = blocksBefore.find(b => b.isFocused);

    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);

    const blocksAfter = await getAllBlocks(page);
    const focusedAfter = blocksAfter.find(b => b.isFocused);

    // Should have moved exactly one block (not two)
    expect(focusedAfter.index).toBe(focusedBefore.index + 1);
  });

  test('arrow left/right within block does not change focus', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Hello world');

    const blockBefore = await page.evaluate(() => {
      return document.activeElement.getAttribute('data-block-id') ||
             document.activeElement.id;
    });

    // Move cursor within block
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowRight');

    const blockAfter = await page.evaluate(() => {
      return document.activeElement.getAttribute('data-block-id') ||
             document.activeElement.id;
    });

    // Should still be in same block
    expect(blockAfter).toBe(blockBefore);
  });
});

test.describe(`${NAV_PARENT_HOP}`, () => {
  test('ArrowLeft at block start hops to parent and lands caret at end', async ({ page }) => {
    test.fail(true, 'LOGSEQ-PARITY-112: boundary hop not implemented yet');
    await page.goto('/blocks.html?test=true');
    await enterEditModeAndClick(page);

    // Create parent block
    const parentText = 'Parent nav target';
    await page.keyboard.type(parentText);
    const parentId = await page.evaluate(() => document.activeElement?.getAttribute('data-block-id'));
    await page.keyboard.press('Enter');

    // Create child and indent under parent
    const childText = 'Child boundary test';
    const childBlock = page.locator('.block').last();
    await childBlock.click();
    await page.keyboard.type(childText);
    const childIdRaw = await page.evaluate(() => document.activeElement?.getAttribute('data-block-id'));
    await page.keyboard.press('Tab');
    await page.waitForFunction(() => document.querySelectorAll('.block[data-block-id]').length >= 2);

    expect(parentId).toBeTruthy();
    expect(childIdRaw).toBeTruthy();

    // Ensure cursor is at start of child block
    await setCursorPosition(page, childIdRaw, 0);
    await page.waitForTimeout(50);

    // ArrowLeft at boundary should move focus to parent and place caret at end
    await page.keyboard.press('ArrowLeft');
    await page.waitForTimeout(150);

    const cursor = await getCursorPosition(page);
    expect(cursor.elementId).toBe(parentId);
    expect(cursor.offset).toBe(parentText.length);
  });
});
