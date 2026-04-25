// @ts-check
import { expect, test } from '@playwright/test';
import {
  enterEditMode,
  getBlockIdAt,
  getCursorState,
  getSelectionState,
  pressGlobalKey,
  pressKeyCombo,
  selectBlock,
  waitForBlocks,
} from './helpers/index.js';

/**
 * Selection Direction Tests
 *
 * Tests the critical edge cases around selection direction reversal,
 * which can cause anchor/focus corruption if not handled correctly.
 *
 * CRITICAL GAPS ADDRESSED:
 * - Shift+Down then Shift+Up should CONTRACT selection (not reverse)
 * - Shift+Up then Shift+Down should CONTRACT selection (reverse direction)
 * - Anchor should remain stable during extend/contract operations
 * - Direction tracking should update correctly
 */

test.describe('Selection Direction Reversal', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await waitForBlocks(page);
    await page.evaluate(() => {
      window.TEST_HELPERS?.resetToEmptyDb();
      window.TEST_HELPERS?.setBlockText('test-block-1', 'Selection A');
      window.TEST_HELPERS?.transact([
        {
          op: 'create-node',
          id: 'selection-block-b',
          type: 'block',
          props: { text: 'Selection B' },
        },
        { op: 'place', id: 'selection-block-b', under: 'test-page', at: 'last' },
        {
          op: 'create-node',
          id: 'selection-block-c',
          type: 'block',
          props: { text: 'Selection C' },
        },
        { op: 'place', id: 'selection-block-c', under: 'test-page', at: 'last' },
      ]);
    });
    await page.waitForFunction(
      () => document.querySelectorAll('div.block[data-block-id]').length >= 3
    );
  });

  test.describe('Extend Down Then Contract Up', () => {
    test('Shift+Down×2 then Shift+Up contracts selection (removes last)', async ({ page }) => {
      // Get block IDs
      const blockA = await getBlockIdAt(page, 0);
      const blockB = await getBlockIdAt(page, 1);
      const blockC = await getBlockIdAt(page, 2);

      // Select block A
      await selectBlock(page, blockA);
      await page.waitForTimeout(100);

      // Extend down to B (Shift+ArrowDown)
      await pressGlobalKey(page, 'Shift+ArrowDown');
      await page.waitForTimeout(100);

      // Verify: A and B selected
      let state = await getSelectionState(page);
      expect(state.nodes).toContain(blockA);
      expect(state.nodes).toContain(blockB);
      expect(state.nodes.length).toBe(2);

      // Extend down to C
      await pressGlobalKey(page, 'Shift+ArrowDown');
      await page.waitForTimeout(100);

      // Verify: A, B, C selected
      state = await getSelectionState(page);
      expect(state.nodes).toContain(blockA);
      expect(state.nodes).toContain(blockB);
      expect(state.nodes).toContain(blockC);
      expect(state.nodes.length).toBe(3);
      expect(state.anchor).toBe(blockA); // Anchor should be first block

      // Now contract by pressing Shift+Up
      await pressGlobalKey(page, 'Shift+ArrowUp');
      await page.waitForTimeout(100);

      // Verify: Should contract to A, B (C removed)
      state = await getSelectionState(page);
      expect(state.nodes).toContain(blockA);
      expect(state.nodes).toContain(blockB);
      expect(state.nodes.length).toBe(2);
      expect(state.anchor).toBe(blockA); // Anchor should remain at A
      expect(state.focus).toBe(blockB); // Focus should move to B
    });

    test('Contract selection to single block then extend opposite direction', async ({ page }) => {
      const blockA = await getBlockIdAt(page, 0);
      const blockB = await getBlockIdAt(page, 1);
      const _blockC = await getBlockIdAt(page, 2);

      // Start with B selected
      await selectBlock(page, blockB);
      await page.waitForTimeout(100);

      // Extend down to C
      await pressGlobalKey(page, 'Shift+ArrowDown');
      await page.waitForTimeout(100);

      // Contract back to B
      await pressGlobalKey(page, 'Shift+ArrowUp');
      await page.waitForTimeout(100);

      // Verify back to single block B
      let state = await getSelectionState(page);
      expect(state.nodes.length).toBe(1);
      expect(state.nodes).toContain(blockB);

      // Now extend UP (opposite direction) to A
      await pressGlobalKey(page, 'Shift+ArrowUp');
      await page.waitForTimeout(100);

      // Should now have A and B selected, direction reversed
      state = await getSelectionState(page);
      expect(state.nodes).toContain(blockA);
      expect(state.nodes).toContain(blockB);
      expect(state.nodes.length).toBe(2);
    });
  });

  test.describe('Extend Up Then Contract Down', () => {
    test('Shift+Up×2 then Shift+Down contracts selection', async ({ page }) => {
      const _blockA = await getBlockIdAt(page, 0);
      const blockB = await getBlockIdAt(page, 1);
      const blockC = await getBlockIdAt(page, 2);

      // Start with C selected
      await selectBlock(page, blockC);
      await page.waitForTimeout(100);

      // Extend up to B
      await pressGlobalKey(page, 'Shift+ArrowUp');
      await page.waitForTimeout(100);

      // Verify B and C selected
      let state = await getSelectionState(page);
      expect(state.nodes).toContain(blockB);
      expect(state.nodes).toContain(blockC);
      expect(state.nodes.length).toBe(2);

      // Extend up to A
      await pressGlobalKey(page, 'Shift+ArrowUp');
      await page.waitForTimeout(100);

      // Verify A, B, C selected
      state = await getSelectionState(page);
      expect(state.nodes.length).toBe(3);
      expect(state.anchor).toBe(blockC); // Anchor at original selection

      // Contract by pressing Shift+Down
      await pressGlobalKey(page, 'Shift+ArrowDown');
      await page.waitForTimeout(100);

      // Should contract to B, C (A removed)
      state = await getSelectionState(page);
      expect(state.nodes).toContain(blockB);
      expect(state.nodes).toContain(blockC);
      expect(state.nodes.length).toBe(2);
    });
  });

  test.describe('Edit Mode to Selection Transition', () => {
    test('Shift+Arrow from edit mode seeds selection with current block as anchor', async ({
      page,
    }) => {
      const blockB = await getBlockIdAt(page, 1);
      const _blockC = await getBlockIdAt(page, 2);

      // Enter edit mode on block B
      await enterEditMode(page, blockB);
      await page.waitForTimeout(100);

      // Verify in edit mode
      let cursorState = await getCursorState(page);
      expect(cursorState.editingBlockId).toBe(blockB);

      // Press Shift+Down - should exit edit and start block selection
      await pressKeyCombo(page, 'ArrowDown', ['Shift']);
      await page.waitForTimeout(200);

      // Should be in selection mode with B as anchor
      const state = await getSelectionState(page);
      expect(state.nodes).toContain(blockB);
      expect(state.anchor).toBe(blockB);

      // Should have exited edit mode
      cursorState = await getCursorState(page);
      expect(cursorState.editingBlockId).toBe(null);
    });
  });

  test.describe('Boundary Conditions', () => {
    test('Extending past first block boundary is no-op', async ({ page }) => {
      const blockA = await getBlockIdAt(page, 0);

      // Select first block
      await selectBlock(page, blockA);
      await page.waitForTimeout(100);

      // Try to extend up (past boundary)
      await pressGlobalKey(page, 'Shift+ArrowUp');
      await page.waitForTimeout(100);

      // Should remain unchanged
      const state = await getSelectionState(page);
      expect(state.nodes.length).toBe(1);
      expect(state.nodes).toContain(blockA);
    });

    test('Extending past last block boundary is no-op', async ({ page }) => {
      // Get last block
      const blocks = await page.locator('div.block[data-block-id]').all();
      const lastBlock = blocks[blocks.length - 1];
      const lastBlockId = await lastBlock.getAttribute('data-block-id');

      // Select last block
      await selectBlock(page, lastBlockId);
      await page.waitForTimeout(100);

      // Try to extend down (past boundary)
      await pressGlobalKey(page, 'Shift+ArrowDown');
      await page.waitForTimeout(100);

      // Should remain unchanged
      const state = await getSelectionState(page);
      expect(state.nodes.length).toBe(1);
      expect(state.nodes).toContain(lastBlockId);
    });
  });

  test.describe('Multiple Extension-Contraction Cycles', () => {
    test('Repeated extend/contract maintains anchor stability', async ({ page }) => {
      const blockA = await getBlockIdAt(page, 0);
      const _blockB = await getBlockIdAt(page, 1);
      const _blockC = await getBlockIdAt(page, 2);

      // Start with A selected
      await selectBlock(page, blockA);
      await page.waitForTimeout(100);

      // Cycle 1: Extend down, contract up
      await pressGlobalKey(page, 'Shift+ArrowDown'); // A, B
      await page.waitForTimeout(50);
      await pressGlobalKey(page, 'Shift+ArrowUp'); // Back to A
      await page.waitForTimeout(50);

      let state = await getSelectionState(page);
      expect(state.nodes.length).toBe(1);
      expect(state.anchor).toBe(blockA);

      // Cycle 2: Extend down twice, contract twice
      await pressGlobalKey(page, 'Shift+ArrowDown'); // A, B
      await page.waitForTimeout(50);
      await pressGlobalKey(page, 'Shift+ArrowDown'); // A, B, C
      await page.waitForTimeout(50);
      await pressGlobalKey(page, 'Shift+ArrowUp'); // A, B
      await page.waitForTimeout(50);
      await pressGlobalKey(page, 'Shift+ArrowUp'); // A
      await page.waitForTimeout(50);

      state = await getSelectionState(page);
      expect(state.nodes.length).toBe(1);
      expect(state.anchor).toBe(blockA);
      expect(state.focus).toBe(blockA);
    });
  });
});
