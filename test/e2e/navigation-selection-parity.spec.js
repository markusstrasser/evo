import { expect, test } from '@playwright/test';
import {
  pressGlobalKey,
  pressKeyCombo,
  pressKeyOnContentEditable,
  setCursorPosition as setExactCursor,
} from './helpers/index.js';

let navIds;

/**
 * LOGSEQ_PARITY_REGRESSIONS.md §4.1-4.4: Navigation & Selection Scope Fixes
 *
 * Tests the four critical parity issues:
 * - §4.1: Navigation scope isolation (page boundaries)
 * - §4.2: Horizontal boundary traversal (DOM order)
 * - §4.3: Shift+Click range selection (visibility-aware)
 * - §4.4: Shift+Arrow anchoring in edit mode
 */

// Helper to find exact block by text content (not parent blocks)
async function findBlockByText(page, text) {
  const blockId = await page.evaluate((searchText) => {
    const blocks = Array.from(document.querySelectorAll('[data-block-id]'));
    const target = blocks.find((el) => {
      const contentEl = el.querySelector('.block-content');
      return contentEl?.textContent?.trim() === searchText;
    });
    return target?.getAttribute('data-block-id');
  }, text);

  if (!blockId) {
    throw new Error(`Block with exact text "${text}" not found`);
  }

  return page.locator(`div.block[data-block-id="${blockId}"]`);
}

// Helper to enter edit mode on a specific block
async function enterEditModeOn(page, block) {
  const blockId = await block.getAttribute('data-block-id');
  if (!blockId) {
    throw new Error('Cannot enter edit mode: locator has no data-block-id');
  }

  await page.evaluate((id) => {
    window.TEST_HELPERS.dispatchIntent({ type: 'selection', mode: 'replace', ids: id });
    window.TEST_HELPERS.dispatchIntent({ type: 'enter-edit', 'block-id': id });
  }, blockId);
  await page.waitForFunction(
    (id) => window.TEST_HELPERS?.getSession?.()?.ui?.['editing-block-id'] === id,
    blockId
  );
  await page.waitForSelector('[contenteditable="true"]');
}

// Helper to set cursor position to start or end of current contenteditable
async function setCursorPosition(page, position) {
  const blockId = await page.evaluate(() => {
    const el = document.querySelector('[contenteditable]');
    return el?.closest('[data-block-id]')?.getAttribute('data-block-id');
  });

  if (!blockId) {
    throw new Error('No contenteditable element found');
  }

  const textLength = await page.evaluate(() => {
    const el = document.querySelector('[contenteditable]');
    return el?.textContent?.length || 0;
  });

  const offset = position === 'start' ? 0 : textLength;
  await setExactCursor(page, blockId, offset);
}

test.describe('Navigation & Selection Parity (§4.1-4.4)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
    navIds = {
      first: 'proj-1',
      last: 'proj-3',
      firstChild: 'proj-1-1',
      secondChild: 'proj-1-2',
      sibling: 'proj-2',
    };
    await page.evaluate(() => {
      window.TEST_HELPERS.loadFixture({
        ops: [
          { op: 'create-node', id: 'test-page', type: 'page', props: { title: 'Projects' } },
          { op: 'place', id: 'test-page', under: 'doc', at: 'last' },
          {
            op: 'create-node',
            id: 'proj-1',
            type: 'block',
            props: { text: 'Evolver - Outliner Project' },
          },
          { op: 'place', id: 'proj-1', under: 'test-page', at: 'last' },
          {
            op: 'create-node',
            id: 'proj-1-1',
            type: 'block',
            props: { text: 'Building a Logseq-inspired outliner' },
          },
          { op: 'place', id: 'proj-1-1', under: 'proj-1', at: 'last' },
          {
            op: 'create-node',
            id: 'proj-1-2',
            type: 'block',
            props: { text: 'Using event sourcing architecture' },
          },
          { op: 'place', id: 'proj-1-2', under: 'proj-1', at: 'last' },
          {
            op: 'create-node',
            id: 'proj-2',
            type: 'block',
            props: { text: 'Tech Stack: ClojureScript + Replicant' },
          },
          { op: 'place', id: 'proj-2', under: 'test-page', at: 'last' },
          { op: 'create-node', id: 'proj-3', type: 'block', props: { text: 'Release notes' } },
          { op: 'place', id: 'proj-3', under: 'test-page', at: 'last' },
        ],
        session: {
          ui: { 'current-page': 'test-page', 'journals-view?': false },
          selection: { nodes: [] },
        },
      });
    });
    await page.waitForSelector(`div.block[data-block-id="${navIds.first}"]`);
  });

  test.describe('§4.1: Navigation Scope Isolation', () => {
    test('arrow navigation up from first block stays at page boundary', async ({ page }) => {
      // SPEC: Navigation should be page-scoped
      // Navigate up from first block of Projects page should no-op
      const firstProjectBlock = page.locator(`div.block[data-block-id="${navIds.first}"]`);
      await enterEditModeOn(page, firstProjectBlock);
      await setCursorPosition(page, 'start');

      // Try to navigate up past first block of current page
      await pressKeyOnContentEditable(page, 'ArrowUp');
      await page.waitForTimeout(100);

      // Should stay on proj-1 (no-op at page boundary)
      const currentBlockId = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        return el?.closest('[data-block-id]')?.getAttribute('data-block-id');
      });

      expect(currentBlockId).toBe(navIds.first);
    });
  });

  test.describe('§4.2: Horizontal Boundary Traversal (DOM Order)', () => {
    test('Left arrow at start navigates to parent at end', async ({ page }) => {
      const firstChild = page.locator(`div.block[data-block-id="${navIds.firstChild}"]`);
      await enterEditModeOn(page, firstChild);

      // Position at start
      await setCursorPosition(page, 'start');

      // Press Left - should jump to parent block at end
      await pressKeyOnContentEditable(page, 'ArrowLeft');

      // Wait for re-render after navigation
      await page.waitForTimeout(100);

      const result = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        const sel = window.getSelection();
        return {
          text: el?.textContent,
          cursorPos: sel?.rangeCount > 0 ? sel.getRangeAt(0).startOffset : null,
          textLength: el?.textContent?.length || 0,
        };
      });

      // Should be in parent block "Evolver - Outliner Project" at end
      expect(result.text).toContain('Evolver');
      expect(result.cursorPos).toBe(result.textLength); // At end
    });

    test('Left arrow from second child navigates to first child at end', async ({ page }) => {
      // This tests horizontal navigation between siblings (child-to-child)
      // Start at second child
      const secondChild = page.locator(`div.block[data-block-id="${navIds.secondChild}"]`);
      await enterEditModeOn(page, secondChild);
      await setCursorPosition(page, 'start');

      // Press Left - should go to first child at end
      await pressKeyOnContentEditable(page, 'ArrowLeft');
      await page.waitForTimeout(100);

      const result = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        const sel = window.getSelection();
        return {
          text: el?.textContent,
          cursorPos: sel.anchorOffset,
          textLength: el?.textContent?.length || 0,
          blockId: el?.closest('[data-block-id]')?.getAttribute('data-block-id'),
        };
      });

      // Should be in first child at end position
      expect(result.blockId).toBe(navIds.firstChild);
      expect(result.text).toContain('Building');
      expect(result.cursorPos).toBe(result.textLength); // At end
    });

    test('Right arrow at end of leaf navigates to next sibling', async ({ page }) => {
      const firstChild = page.locator(`div.block[data-block-id="${navIds.firstChild}"]`);
      await enterEditModeOn(page, firstChild);

      // Position cursor at end
      await setCursorPosition(page, 'end');

      // Press Right - should go to next sibling
      await pressKeyOnContentEditable(page, 'ArrowRight');

      // Wait for navigation to complete
      await page.waitForTimeout(100);

      const result = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        return { text: el?.textContent };
      });

      // Should be in next sibling "Using event sourcing architecture"
      expect(result.text).toContain('event sourcing');
    });

    test('Right arrow at end of parent navigates to first child', async ({ page }) => {
      // SPEC: Right at block end → first child at start (if children exist)
      // Click once to select the block (focus), then click again to enter edit mode
      const contentView = page.locator(
        `div.block[data-block-id="${navIds.first}"] > .block-content`
      );
      await contentView.click(); // Select block
      await contentView.click(); // Enter edit mode on focused block
      await page.waitForSelector('[contenteditable="true"]');

      // Verify we're editing the parent block (proj-1), not a child
      const beforeBlockId = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        return el?.closest('[data-block-id]')?.getAttribute('data-block-id');
      });
      expect(beforeBlockId).toBe(navIds.first);

      // Position cursor at end
      await setCursorPosition(page, 'end');

      // Press Right - should go to first child, not next sibling
      await pressKeyOnContentEditable(page, 'ArrowRight');

      // Wait for navigation to complete
      await page.waitForTimeout(100);

      const result = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        const selection = window.getSelection();
        return {
          text: el?.textContent,
          cursorPos: selection?.anchorOffset || 0,
        };
      });

      // Should be in first child "Building a Logseq-inspired outliner" at start
      expect(result.text).toContain('Logseq-inspired');
      expect(result.cursorPos).toBe(0); // At start of child
    });
  });

  test.describe('§4.4: Shift+Arrow Anchoring in Edit Mode', () => {
    test('Shift+ArrowDown at boundary extends from current block (not page top)', async ({
      page,
    }) => {
      // Enter edit mode on the exact block (not parent)
      const block = await findBlockByText(page, 'Using event sourcing architecture');
      await enterEditModeOn(page, block);

      // Get the block ID we started with
      const startBlockId = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        return el?.closest('[data-block-id]')?.getAttribute('data-block-id');
      });

      // Ensure we're at end
      await setCursorPosition(page, 'end');

      // Position at last row (for single-line blocks, this is automatic)
      // Press Shift+Down to extend selection
      await pressKeyCombo(page, 'ArrowDown', ['Shift']);

      // Wait for render to complete (requestAnimationFrame)
      await page.waitForTimeout(200);

      // Check selection via session state (not CSS colors)
      const selection = await page.evaluate((blockId) => {
        const sess = window.TEST_HELPERS?.getSession?.();
        const nodes = sess?.selection?.nodes || [];
        return {
          count: nodes.length,
          includesStartBlock: nodes.includes(blockId),
          anchor: sess?.selection?.anchor,
          focus: sess?.selection?.focus,
        };
      }, startBlockId);

      // Should include the editing block (anchor), not jump to first block
      expect(selection.includesStartBlock).toBe(true);
      expect(selection.count).toBeGreaterThanOrEqual(2);
    });

    test('Shift+ArrowUp at boundary extends from current block (not page bottom)', async ({
      page,
    }) => {
      // Enter edit mode on the exact block (not parent)
      const block = await findBlockByText(page, 'Using event sourcing architecture');
      await enterEditModeOn(page, block);

      // Get the block ID we started with
      const startBlockId = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        return el?.closest('[data-block-id]')?.getAttribute('data-block-id');
      });

      // Position at start (first row)
      await setCursorPosition(page, 'start');

      // Press Shift+Up to extend selection upward
      await pressKeyCombo(page, 'ArrowUp', ['Shift']);

      // Wait for render to complete (requestAnimationFrame)
      await page.waitForTimeout(100);

      // Check selection via session state (not CSS colors)
      const selection = await page.evaluate((blockId) => {
        const sess = window.TEST_HELPERS?.getSession?.();
        const nodes = sess?.selection?.nodes || [];
        return {
          count: nodes.length,
          includesStartBlock: nodes.includes(blockId),
          anchor: sess?.selection?.anchor,
        };
      }, startBlockId);

      // Should include the editing block (anchor)
      expect(selection.includesStartBlock).toBe(true);
      expect(selection.count).toBeGreaterThanOrEqual(2);
    });

    test('Shift+Arrow seeds selection only when no focus exists', async ({ page }) => {
      // Click background to clear all selection
      await page.click('body', { position: { x: 10, y: 10 } });

      // Double-click to enter edit mode
      const block = page
        .locator('div.block')
        .filter({ hasText: 'Tech Stack: ClojureScript + Replicant' })
        .first();
      await enterEditModeOn(page, block);
      await setCursorPosition(page, 'end');

      // Verify no block selection exists before Shift+Arrow (editing doesn't count as selection)
      const beforeState = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        return {
          selectionCount: sess?.selection?.nodes?.length || 0,
          isEditing: !!sess?.ui?.['editing-block-id'],
        };
      });
      expect(beforeState.selectionCount).toBe(0);
      expect(beforeState.isEditing).toBe(true);

      // Shift+Down should seed with current block first
      await pressKeyCombo(page, 'ArrowDown', ['Shift']);
      await page.waitForTimeout(100); // Wait for re-render

      // Check selection via session state
      const afterState = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        return {
          selectionCount: sess?.selection?.nodes?.length || 0,
          isEditing: !!sess?.ui?.['editing-block-id'],
        };
      });

      // Should now have selection (current + next) and have exited edit mode
      expect(afterState.isEditing).toBe(false);
      expect(afterState.selectionCount).toBeGreaterThanOrEqual(2);
    });

    test('Shift+Arrow always exits edit mode and starts block selection (Logseq parity)', async ({
      page,
    }) => {
      // LOGSEQ PARITY: Shift+Arrow ALWAYS exits edit and starts block selection
      // (No text selection within blocks via Shift+Arrow - use mouse drag for that)

      // Find any block and enter edit mode
      const firstBlock = page.locator('[data-block-id]').first();
      await enterEditModeOn(page, firstBlock);
      await page.waitForTimeout(50);

      // Verify we're in edit mode
      const beforeState = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        return {
          isEditing: !!sess?.ui?.['editing-block-id'],
          blockSelectionCount: sess?.selection?.nodes?.length || 0,
        };
      });
      expect(beforeState.isEditing).toBe(true);
      expect(beforeState.blockSelectionCount).toBe(0);

      // Press Shift+Down - should exit edit and start block selection
      await pressKeyCombo(page, 'ArrowDown', ['Shift']);
      await page.waitForTimeout(100);

      // Should have exited edit mode and have block selection
      const afterState = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        return {
          isEditing: !!sess?.ui?.['editing-block-id'],
          blockSelectionCount: sess?.selection?.nodes?.length || 0,
        };
      });

      expect(afterState.isEditing).toBe(false);
      expect(afterState.blockSelectionCount).toBeGreaterThan(0);
    });
  });

  test.describe('Integration: Combined Behaviors', () => {
    test('Shift+Click creates range selection between blocks', async ({ page }) => {
      // Click first block to select it
      await page.locator(`div.block[data-block-id="${navIds.firstChild}"]`).click();
      await page.waitForTimeout(50);

      // Shift+Click on a later block to create range selection
      await page.locator(`div.block[data-block-id="${navIds.sibling}"]`).click({
        modifiers: ['Shift'],
      });
      await page.waitForTimeout(100);

      // Should have range selection (at least 2 blocks selected) - check via session state
      const selection = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        return {
          count: sess?.selection?.nodes?.length || 0,
          anchor: sess?.selection?.anchor,
          focus: sess?.selection?.focus,
        };
      });
      expect(selection.count).toBeGreaterThanOrEqual(2);
    });

    test('selection extension with Shift+Arrow spans multiple blocks', async ({ page }) => {
      // Enter edit mode on first child block
      const firstChild = page.locator(`div.block[data-block-id="${navIds.firstChild}"]`);
      await enterEditModeOn(page, firstChild);
      await setCursorPosition(page, 'end');

      // Extend selection downward with Shift+Arrow
      // Note: First Shift+Arrow exits edit mode and enters block selection mode
      await pressKeyCombo(page, 'ArrowDown', ['Shift']);
      await page.waitForTimeout(100);

      // Continue extending selection (now in block selection mode)
      await pressGlobalKey(page, 'Shift+ArrowDown');
      await page.waitForTimeout(50);
      await pressGlobalKey(page, 'Shift+ArrowDown');
      await page.waitForTimeout(50);

      // Should have multiple blocks selected - check via session state
      const selection = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        const nodes = sess?.selection?.nodes || [];
        return {
          count: nodes.length,
          isEditing: !!sess?.ui?.['editing-block-id'],
          anchor: sess?.selection?.anchor,
          focus: sess?.selection?.focus,
        };
      });

      // Should have selected at least 2 blocks and exited edit mode
      expect(selection.isEditing).toBe(false);
      expect(selection.count).toBeGreaterThanOrEqual(2);
    });
  });
});
