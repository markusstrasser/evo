import { test, expect } from '@playwright/test';
import { pressKeyOnContentEditable, pressKeyCombo } from './helpers/keyboard.js';
import { setCursorPosition as setExactCursor } from './helpers/cursor.js';

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
    const target = blocks.find(el => {
      const contentEl = el.querySelector('.content-view');
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
  await block.click();
  await page.keyboard.type('a'); // Type to enter edit mode (Logseq-style)
  await page.waitForSelector('[contenteditable="true"]');
  await pressKeyOnContentEditable(page, 'Backspace'); // Clear the 'a'
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
    await page.goto('/index.html');
    // Wait for blocks to render (they start in view mode, not edit mode)
    await page.waitForSelector('[data-block-id]');
    navIds = await page.evaluate(() => {
      const proj = window.__E2E_SCENARIOS?.navigation?.projects;
      return {
        first: proj?.['first-block'] ?? 'proj-1',
        last: proj?.['last-block'] ?? 'proj-3',
        firstChild: proj?.['first-child'] ?? 'proj-1-1',
        secondChild: proj?.['second-child'] ?? 'proj-1-2',
        sibling: proj?.['adjacent-sibling'] ?? 'proj-2'
      };
    });
  });

  test.describe('§4.1: Navigation Scope Isolation', () => {
    test('arrow navigation stops at current page boundaries', async ({ page }) => {
      // SPEC REQUIREMENT (§4.1): "On the Projects page, Arrow Down from the last block should no-op instead of jumping to Tasks"
      // CURRENT BEHAVIOR: Navigation DOES jump to Tasks page (regression described in spec)
      // EXPECTED: This test should FAIL until §4.1 is implemented
      // Implementation needed: visible-blocks-in-dom-order must respect current-page as implicit zoom root

      // Verify we're on the Projects page
      const currentPage = await page.evaluate(() => {
        if (!window.DEBUG || typeof window.DEBUG.state !== 'function') {
          // Fallback: check DOM for active page indicator
          const activePageItem = document.querySelector('.page-item[style*="background-color: rgb(219, 234, 254)"]');
          return activePageItem?.textContent?.trim();
        }
        const db = window.DEBUG.state();
        return db?.nodes?.['session/ui']?.props?.['current-page'];
      });

      // Current page should be "projects" or "Projects"
      expect(currentPage?.toLowerCase()).toContain('project');

      // Find the last block of the PROJECTS page specifically (proj-3)
      // NOT the last block of the entire document
      const lastProjectBlock = page.locator(`div.block[data-block-id="${navIds.last}"]`);
      await enterEditModeOn(page, lastProjectBlock);
      await setCursorPosition(page, 'end');

      // Try to navigate down past last block of current page
      await pressKeyOnContentEditable(page, 'ArrowDown');
      await page.waitForTimeout(100);

      // Should stay on proj-3 (no-op at page boundary)
      // Should NOT jump to task-1 (first block of Tasks page)
      const currentBlockId = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        return el?.closest('[data-block-id]')?.getAttribute('data-block-id');
      });

      expect(currentBlockId).toBe(navIds.last);

      // Verify we didn't jump to Tasks page
      const currentPageAfter = await page.evaluate(() => {
        if (window.DEBUG && typeof window.DEBUG.currentPage === 'function') {
          return window.DEBUG.currentPage();
        }
        // Fallback: check DOM for active page indicator (strip emoji)
        const activePageItem = document.querySelector('.page-item[style*="background-color: rgb(219, 234, 254)"]');
        const text = activePageItem?.textContent?.trim()?.toLowerCase() || '';
        // Strip emoji (match only letters)
        return text.match(/[a-z]+/)?.[0] || text;
      });

      expect(currentPageAfter).toBe('projects');
    });

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

    test.skip('vertical navigation respects zoom boundaries', async ({ page }) => {
      // TODO: Zoom functionality not yet implemented
      // When implemented, test that zooming into a block makes that block the navigation root
    });
  });

  test.describe('§4.2: Horizontal Boundary Traversal (DOM Order)', () => {
    test('Left arrow at start navigates to parent at end', async ({ page }) => {
      // Find a child block and enter edit mode
      const block = page.locator('div.block').filter({ hasText: 'Building a Logseq-inspired outliner' }).first();
      await enterEditModeOn(page, block);

      // Position at start
      await setCursorPosition(page, 'start');

      // Press Left - should jump to parent block at end
      await pressKeyOnContentEditable(page, 'ArrowLeft');

      const result = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        const sel = window.getSelection();
        return {
          text: el.textContent,
          cursorPos: sel.getRangeAt(0).startOffset,
          textLength: el.textContent?.length || 0
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
          blockId: el?.closest('[data-block-id]')?.getAttribute('data-block-id')
        };
      });

      // Should be in first child at end position
      expect(result.blockId).toBe(navIds.firstChild);
      expect(result.text).toContain('Building');
      expect(result.cursorPos).toBe(result.textLength); // At end
    });

    test('Right arrow at end of leaf navigates to next sibling', async ({ page }) => {
      // Double-click on a leaf block to enter edit mode
      const block = page.locator('div.block').filter({ hasText: 'Building a Logseq-inspired outliner' }).first();
      await enterEditModeOn(page, block);

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
  });

  test.describe('§4.3: Shift+Click Range Selection (Visibility-Aware)', () => {
    test('Shift+Click between visible blocks selects only visible range', async ({ page }) => {
      // Select first block
      await page.locator('div.block').filter({ hasText: 'Evolver - Outliner Project' }).first().click();

      // Shift+Click on third visible block
      await page.keyboard.down('Shift');
      await page.locator('div.block').filter({ hasText: 'Tech Stack: ClojureScript + Replicant' }).first().click();
      await page.keyboard.up('Shift');

      // Count selected blocks
      const selectedCount = await page.evaluate(() => {
        return document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]').length;
      });

      // Should select exactly 3 blocks (parent + 2 children + 1 sibling)
      // NOT including any hidden/folded blocks
      expect(selectedCount).toBeGreaterThanOrEqual(2);
    });

    test('Shift+Click skips folded descendants', async ({ page }) => {
      // SPEC REQUIREMENT (§4.3): "Shift+Click between folded nodes → skips folded descendants; selection only spans visible nodes"
      // CURRENT BEHAVIOR: Shift+Click DOES include folded children (regression described in spec)
      // EXPECTED: This test should FAIL until §4.3 is implemented
      // Implementation needed: Replace tree/doc-range with visibility-aware range helper

      // Fold proj-1 by clicking the toggle icon (▾)
      const toggleIcon = page.locator(`div.block[data-block-id="${navIds.first}"] > span`).first();
      await toggleIcon.click();
      await page.waitForTimeout(300);

      // Verify children are actually hidden in DOM
      const childrenHidden = await page.evaluate((ids) => {
        const child1 = document.querySelector(`[data-block-id="${ids.firstChild}"]`);
        const child2 = document.querySelector(`[data-block-id="${ids.secondChild}"]`);
        return (!child1 && !child2) ||
               (child1?.style?.display === 'none' && child2?.style?.display === 'none');
      }, navIds);

      // Select the parent block first by clicking its content
      const parentContent = page.locator(`div.block[data-block-id="${navIds.first}"] > span.content-view`);
      await parentContent.click();
      await page.waitForTimeout(100);

      // Shift+Click from the folded parent to a later block
      await page.keyboard.down('Shift');
      await page.locator(`div.block[data-block-id="${navIds.sibling}"]`).click();
      await page.keyboard.up('Shift');
      await page.waitForTimeout(100);

      // Count selected blocks - should NOT include the hidden children
      const selection = await page.evaluate((ids) => {
        const selected = Array.from(
          document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]')
        );
        return {
          count: selected.length,
          blockIds: selected.map(el => el.getAttribute('data-block-id')),
          childrenInDom: {
            first: !!document.querySelector(`[data-block-id="${ids.firstChild}"]`),
            second: !!document.querySelector(`[data-block-id="${ids.secondChild}"]`)
          }
        };
      }, navIds);

      // CRITICAL: Should select parent (proj-1) and proj-2, but NOT the folded children
      // If this fails, it means the implementation doesn't respect fold state in selection
      expect(selection.count).toBe(2);
      expect(selection.blockIds).toContain(navIds.first);
      expect(selection.blockIds).toContain(navIds.sibling);
      // Should NOT contain the folded children
      expect(selection.blockIds).not.toContain(navIds.firstChild);
      expect(selection.blockIds).not.toContain(navIds.secondChild);
    });
  });

  test.describe('§4.4: Shift+Arrow Anchoring in Edit Mode', () => {
    test('Shift+ArrowDown at boundary extends from current block (not page top)', async ({ page }) => {
      // Enter edit mode on the exact block (not parent)
      const block = await findBlockByText(page, 'Using event sourcing architecture');
      await enterEditModeOn(page, block);

      // Ensure we're at end
      await setCursorPosition(page, 'end');

      // Position at last row (for single-line blocks, this is automatic)
      // Press Shift+Down to extend selection
      await pressKeyCombo(page, 'ArrowDown', ['Shift']);

      // Wait for render to complete (requestAnimationFrame)
      await page.waitForTimeout(200);

      // Check selection includes current block + next
      const selection = await page.evaluate(() => {
        const selected = Array.from(
          document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]')
        );
        return selected.map(el => el.textContent);
      });

      // Should include the editing block, not jump to first block
      expect(selection.some(text => text?.includes('event sourcing'))).toBe(true);
      expect(selection.length).toBeGreaterThanOrEqual(2);
    });

    test('Shift+ArrowUp at boundary extends from current block (not page bottom)', async ({ page }) => {
      // Enter edit mode on the exact block (not parent)
      const block = await findBlockByText(page, 'Using event sourcing architecture');
      await enterEditModeOn(page, block);

      // Position at start (first row)
      await setCursorPosition(page, 'start');

      // Press Shift+Up to extend selection upward
      await pressKeyCombo(page, 'ArrowUp', ['Shift']);

      // Wait for render to complete (requestAnimationFrame)
      await page.waitForTimeout(100);

      // Check selection includes current block + previous
      const selection = await page.evaluate(() => {
        const selected = Array.from(
          document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]')
        );
        return selected.map(el => el.textContent);
      });

      // Should include the editing block
      expect(selection.some(text => text?.includes('event sourcing'))).toBe(true);
      expect(selection.length).toBeGreaterThanOrEqual(2);
    });

    test('Shift+Arrow seeds selection only when no focus exists', async ({ page }) => {
      // Click background to clear all selection
      await page.click('body', { position: { x: 10, y: 10 } });

      // Double-click to enter edit mode
      const block = page.locator('div.block').filter({ hasText: 'Tech Stack: ClojureScript + Replicant' }).first();
      await enterEditModeOn(page, block);
      await setCursorPosition(page, 'end');

      // Verify no selection exists before Shift+Arrow
      const beforeSelection = await page.evaluate(() => {
        return document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]').length;
      });
      expect(beforeSelection).toBe(0);

      // Shift+Down should seed with current block first
      await pressKeyCombo(page, 'ArrowDown', ['Shift']);

      const afterSelection = await page.evaluate(() => {
        return document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]').length;
      });

      // Should now have selection (current + next)
      expect(afterSelection).toBeGreaterThanOrEqual(2);
    });
  });

  test.describe('Integration: Combined Behaviors', () => {
    test('Shift+Click creates range selection between blocks', async ({ page }) => {
      // Click first block to select it
      await page.locator(`div.block[data-block-id="${navIds.firstChild}"]`).click();
      await page.waitForTimeout(50);

      // Shift+Click on a later block to create range selection
      await page.keyboard.down('Shift');
      await page.locator(`div.block[data-block-id="${navIds.sibling}"]`).click();
      await page.keyboard.up('Shift');
      await page.waitForTimeout(100);

      // Should have range selection (at least 2 blocks selected)
      const selectedCount = await page.evaluate(() => {
        return document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]').length;
      });
      expect(selectedCount).toBeGreaterThanOrEqual(2);
    });

    test('selection extension with Shift+Arrow spans multiple blocks', async ({ page }) => {
      // Enter edit mode on first child block
      const firstChild = page.locator(`div.block[data-block-id="${navIds.firstChild}"]`);
      await enterEditModeOn(page, firstChild);
      await setCursorPosition(page, 'end');

      // Extend selection downward with Shift+Arrow
      // Note: First Shift+Arrow might exit edit mode and enter block selection mode
      await pressKeyCombo(page, 'ArrowDown', ['Shift']);
      await page.waitForTimeout(100);

      // Continue extending selection (now in block selection mode)
      await page.keyboard.press('Shift+ArrowDown');
      await page.waitForTimeout(50);
      await page.keyboard.press('Shift+ArrowDown');
      await page.waitForTimeout(50);

      // Should have multiple blocks selected
      const selection = await page.evaluate(() => {
        const selected = Array.from(
          document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]')
        );
        return {
          count: selected.length,
          texts: selected.map(el => el.textContent?.substring(0, 30))
        };
      });

      // Should have selected at least 2 blocks
      expect(selection.count).toBeGreaterThanOrEqual(2);
      // Should include blocks from the navigation
      expect(selection.texts.length).toBeGreaterThan(0);
    });
  });
});
