/**
 * Block Navigation E2E Tests
 *
 * Tests for arrow key navigation and cursor position preservation.
 */

import { test, expect } from '@playwright/test';
import { getCursorPosition, setCursorPosition, getAllBlocks, enterEditModeAndClick, pressKeyOnContentEditable } from './helpers/index.js';

const NAV_PARENT_HOP = 'NAV-BOUNDARY-LEFT-01';

test.describe('Block Navigation', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
  });

  test('arrow down preserves cursor column', async ({ page }) => {
    // Create two blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Hello world this is a long line');
    await page.keyboard.press('Enter');
    // Wait for second block to exist
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 2);
    await page.keyboard.type('Short line');

    // Navigate up to first block
    await page.keyboard.press('ArrowUp');

    // Set cursor at position 10
    const blocks = await getAllBlocks(page);
    await setCursorPosition(page, blocks[0].id, 10);

    // Navigate down and wait for focus to move
    await page.keyboard.press('ArrowDown');
    await page.waitForFunction(
      (fromId) => {
        const el = document.activeElement;
        const blockId = el?.getAttribute('data-block-id') || el?.closest('[data-block-id]')?.getAttribute('data-block-id');
        return blockId && blockId !== fromId;
      },
      blocks[0].id,
      { timeout: 5000 }
    );

    const cursor = await getCursorPosition(page);

    // Cursor should be at same column (or end if shorter)
    expect(cursor.offset).toBe(10);
    expect(cursor.elementId).not.toBe(blocks[0].id);
  });

  test('arrow up to shorter block goes to end', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Short');
    await page.keyboard.press('Enter');
    // Wait for second block to exist
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 2);
    await page.keyboard.type('Very long line with lots of text');

    // Set cursor at position 20 in second block
    const blocks = await getAllBlocks(page);
    await setCursorPosition(page, blocks[1].id, 20);

    // Navigate up (first block only has 5 chars)
    await page.keyboard.press('ArrowUp');

    // Wait for focus to move to first block
    await page.waitForFunction(
      (fromId) => {
        const el = document.activeElement;
        const blockId = el?.getAttribute('data-block-id') || el?.closest('[data-block-id]')?.getAttribute('data-block-id');
        return blockId && blockId !== fromId;
      },
      blocks[1].id,
      { timeout: 5000 }
    );

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
    // Wait for second block to exist
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 2);
    await page.keyboard.type('Second');

    // Clear events
    await page.evaluate(() => { window.navigationEvents = []; });

    // Get initial block (we're in the second block after typing)
    const initialBlocks = await getAllBlocks(page);
    const initialFocused = initialBlocks.find(b => b.isFocused);

    // Navigate up and wait for focus to move
    await page.keyboard.press('ArrowUp');
    await page.waitForFunction(
      (fromId) => {
        const el = document.activeElement;
        const blockId = el?.getAttribute('data-block-id') || el?.closest('[data-block-id]')?.getAttribute('data-block-id');
        return blockId && blockId !== fromId;
      },
      initialFocused?.id,
      { timeout: 5000 }
    );

    // Check that only one block change occurred
    const blocksBefore = await getAllBlocks(page);
    const focusedBefore = blocksBefore.find(b => b.isFocused);

    // Navigate down and wait for focus to move
    await page.keyboard.press('ArrowDown');
    await page.waitForFunction(
      (fromId) => {
        const el = document.activeElement;
        const blockId = el?.getAttribute('data-block-id') || el?.closest('[data-block-id]')?.getAttribute('data-block-id');
        return blockId && blockId !== fromId;
      },
      focusedBefore?.id,
      { timeout: 5000 }
    );

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

test.describe('Empty Block Navigation', () => {
  test('arrow down through empty block', async ({ page }) => {
    await page.goto('/blocks.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);

    // Type in first block
    await page.keyboard.type('First block');

    // Create second block (empty)
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);

    // Create third block with content
    await page.keyboard.press('Enter');
    await page.waitForTimeout(100);
    await page.keyboard.type('Third block');

    // Go back up twice to first block
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);

    // Verify we're in first block
    let text = await page.evaluate(() => document.activeElement?.textContent);
    expect(text).toBe('First block');

    // Now go down - should go to empty block
    await page.keyboard.press('ArrowDown');
    await page.waitForFunction(
      (prevText) => document.activeElement?.textContent !== prevText,
      'First block',
      { timeout: 3000 }
    );

    text = await page.evaluate(() => document.activeElement?.textContent);
    expect(text).toBe(''); // Empty block

    // Go down again - should go to third block
    await page.keyboard.press('ArrowDown');
    await page.waitForFunction(
      (prevText) => document.activeElement?.textContent !== prevText,
      '',
      { timeout: 3000 }
    );

    text = await page.evaluate(() => document.activeElement?.textContent);
    expect(text).toBe('Third block');
  });
});

test.describe('Cross-Page Navigation (Journals)', () => {
  test.skip('arrow down navigates from one journal page to the next', async ({ page }) => {
    // TODO: This test requires a more complex setup with multiple journal pages.
    // The cross-page navigation feature is implemented and works manually.
    // A proper test would need:
    // 1. Multiple journal pages visible (today + yesterday)
    // 2. Each with at least one block
    // 3. Navigate from last block of one to first block of next
    //
    // For now, skip this test and rely on manual verification.
    // The core navigation code is tested in other tests.
    expect(true).toBe(true);
  });
});

test.describe('Navigation State Sync Edge Cases', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/blocks.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
  });

  test('sequential navigation maintains state consistency', async ({ page }) => {
    // Create multiple blocks
    await page.keyboard.type('First');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 2);
    await page.keyboard.type('Second');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 3);
    await page.keyboard.type('Third');

    // Navigate up twice (with waits to ensure each navigation completes)
    await page.keyboard.press('ArrowUp');
    await page.waitForFunction(
      () => document.activeElement?.textContent === 'Second',
      { timeout: 3000 }
    );

    await page.keyboard.press('ArrowUp');
    await page.waitForFunction(
      () => document.activeElement?.textContent === 'First',
      { timeout: 3000 }
    );

    // Verify kernel state matches DOM
    const kernelState = await page.evaluate(() => {
      const session = window.TEST_HELPERS?.getSession();
      return session?.ui?.editing_block_id || session?.ui?.['editing-block-id'] || null;
    });
    const domFocusedId = await page.evaluate(() => {
      const el = document.activeElement;
      return el?.getAttribute('data-block-id') ||
             el?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });

    expect(kernelState).toBe(domFocusedId);
    // Should be on first block after two ArrowUp from third
    const text = await page.evaluate(() => document.activeElement?.textContent);
    expect(text).toBe('First');
  });

  test('ArrowUp at document top is no-op (stays in first block)', async ({ page }) => {
    await page.keyboard.type('Only block');

    const blocksBefore = await getAllBlocks(page);
    const firstBlockId = blocksBefore[0].id;

    // Try to navigate up when already at top
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(50);

    const domFocusedId = await page.evaluate(() => {
      const el = document.activeElement;
      return el?.getAttribute('data-block-id') ||
             el?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });

    expect(domFocusedId).toBe(firstBlockId);
    // Focus should still be on contenteditable
    const isFocused = await page.evaluate(() =>
      document.activeElement?.getAttribute('contenteditable') === 'true'
    );
    expect(isFocused).toBe(true);
  });

  test('ArrowDown at document bottom is no-op (stays in last block)', async ({ page }) => {
    await page.keyboard.type('First');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 2);
    await page.keyboard.type('Last block');

    const blocks = await getAllBlocks(page);
    const lastBlockId = blocks[blocks.length - 1].id;

    // We're already in last block after typing
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(50);

    const domFocusedId = await page.evaluate(() => {
      const el = document.activeElement;
      return el?.getAttribute('data-block-id') ||
             el?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });

    expect(domFocusedId).toBe(lastBlockId);
  });

  test('focus persists on contenteditable after navigation', async ({ page }) => {
    await page.keyboard.type('Block one');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 2);
    await page.keyboard.type('Block two');

    // Navigate through all blocks
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(50);
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(50);

    // Verify focus is on contenteditable
    const focusInfo = await page.evaluate(() => ({
      isContentEditable: document.activeElement?.getAttribute('contenteditable') === 'true',
      tagName: document.activeElement?.tagName,
      hasBlockId: !!document.activeElement?.getAttribute('data-block-id') ||
                  !!document.activeElement?.closest('[data-block-id]')
    }));

    expect(focusInfo.isContentEditable).toBe(true);
    expect(focusInfo.hasBlockId).toBe(true);
  });

  test('kernel editing state matches DOM active element after navigation', async ({ page }) => {
    await page.keyboard.type('Alpha');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 2);
    await page.keyboard.type('Beta');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 3);
    await page.keyboard.type('Gamma');

    // Navigate to middle block
    await page.keyboard.press('ArrowUp');
    await page.waitForFunction(
      () => document.activeElement?.textContent === 'Beta',
      { timeout: 3000 }
    );

    // Check state consistency
    const { kernelId, domId, match } = await page.evaluate(() => {
      const session = window.TEST_HELPERS?.getSession();
      const kernelId = session?.ui?.editing_block_id || session?.ui?.['editing-block-id'] || null;
      const el = document.activeElement;
      const domId = el?.getAttribute('data-block-id') ||
                   el?.closest('[data-block-id]')?.getAttribute('data-block-id');
      return { kernelId, domId, match: kernelId === domId };
    });

    expect(match).toBe(true);
    expect(kernelId).toBeTruthy();
    expect(domId).toBeTruthy();
  });
});

test.describe(`${NAV_PARENT_HOP}`, () => {
  test('ArrowLeft at block start hops to parent and lands caret at end', async ({ page }) => {
    // LOGSEQ-PARITY-112: Testing boundary hop navigation
    await page.goto('/blocks.html?test=true');
    await enterEditModeAndClick(page);

    // Create parent block
    const parentText = 'Parent nav target';
    await page.keyboard.type(parentText);
    const parentId = await page.evaluate(() =>
      document.activeElement?.getAttribute('data-block-id') ||
      document.activeElement?.closest('[data-block-id]')?.getAttribute('data-block-id')
    );
    await page.keyboard.press('Enter');
    // Wait for second block to exist
    await page.waitForFunction(() => document.querySelectorAll('[data-block-id]').length >= 2);

    // Create child block
    const childText = 'Child boundary test';
    await page.keyboard.type(childText);
    const childIdRaw = await page.evaluate(() =>
      document.activeElement?.getAttribute('data-block-id') ||
      document.activeElement?.closest('[data-block-id]')?.getAttribute('data-block-id')
    );

    // Indent the child under parent by dispatching the intent directly
    // (avoids page.keyboard.press('Tab') which doesn't trigger handlers on contenteditable)
    await page.evaluate(() => {
      if (window.TEST_HELPERS?.dispatchIntent) {
        window.TEST_HELPERS.dispatchIntent({ type: 'indent-selected' });
      }
    });

    expect(parentId).toBeTruthy();
    expect(childIdRaw).toBeTruthy();

    // Wait for re-render after indent - child should still be in edit mode
    await page.waitForSelector(`[data-block-id="${childIdRaw}"] [contenteditable="true"]`, { timeout: 5000 });

    // Set cursor at start
    await setCursorPosition(page, childIdRaw, 0);

    // ArrowLeft at boundary should navigate to parent
    await pressKeyOnContentEditable(page, 'ArrowLeft');
    // Wait for focus to move to parent element
    await page.waitForFunction(
      (expectedId) => {
        const el = document.activeElement;
        const blockId = el?.getAttribute('data-block-id') || el?.closest('[data-block-id]')?.getAttribute('data-block-id');
        return blockId === expectedId;
      },
      parentId,
      { timeout: 5000 }
    );

    const cursor = await getCursorPosition(page);
    expect(cursor.elementId).toBe(parentId);
    expect(cursor.offset).toBe(parentText.length);
  });
});
