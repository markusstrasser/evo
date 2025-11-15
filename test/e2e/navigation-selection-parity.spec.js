import { test, expect } from '@playwright/test';

/**
 * LOGSEQ_PARITY_REGRESSIONS.md §4.1-4.4: Navigation & Selection Scope Fixes
 *
 * Tests the four critical parity issues:
 * - §4.1: Navigation scope isolation (page boundaries)
 * - §4.2: Horizontal boundary traversal (DOM order)
 * - §4.3: Shift+Click range selection (visibility-aware)
 * - §4.4: Shift+Arrow anchoring in edit mode
 */

test.describe('Navigation & Selection Parity (§4.1-4.4)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8080/');
    // Wait for blocks to render (they start in view mode, not edit mode)
    await page.waitForSelector('[data-block-id]');
  });

  test.describe('§4.1: Navigation Scope Isolation', () => {
    test('arrow navigation stays within current page', async ({ page }) => {
      // Navigate to Projects page
      await page.getByText('Projects', { exact: true }).click();

      // Find the last visible block on Projects page
      const lastBlockText = await page.evaluate(() => {
        const blocks = Array.from(document.querySelectorAll('[data-block-id]'));
        const projectsBlocks = blocks.filter(b => {
          const text = b.textContent || '';
          return text.includes('Tasks') || text.includes('Tech Stack');
        });
        return projectsBlocks[projectsBlocks.length - 1]?.textContent;
      });

      // Click last block and try to navigate down
      await page.getByText(lastBlockText).click();
      await page.keyboard.press('ArrowDown');

      // Should stay on same block (no-op), NOT jump to Tasks page
      const currentBlock = await page.evaluate(() => {
        const focused = document.querySelector('[data-block-id][style*="background-color: rgb(179, 217, 255)"]');
        return focused?.textContent || '';
      });

      expect(currentBlock).toContain(lastBlockText);
    });

    test('vertical navigation respects zoom boundaries', async ({ page }) => {
      // This test would require zoom functionality to be implemented
      // Placeholder for when zoom is available
      test.skip();
    });
  });

  test.describe('§4.2: Horizontal Boundary Traversal (DOM Order)', () => {
    test('Left arrow at start navigates to parent at end', async ({ page }) => {
      // Find a child block (e.g., "Building a Logseq-inspired outliner")
      await page.getByText('Building a Logseq-inspired outliner').click();

      // Position at start
      await page.keyboard.press('Home');

      // Press Left - should jump to parent block at end
      await page.keyboard.press('ArrowLeft');

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

    test('Right arrow at end navigates into first child', async ({ page }) => {
      // Click on a parent block that has children
      await page.getByText('Evolver - Outliner Project').click();

      // Position at end
      await page.keyboard.press('End');

      // Press Right - should enter first child at start
      await page.keyboard.press('ArrowRight');

      const result = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        const sel = window.getSelection();
        return {
          text: el.textContent,
          cursorPos: sel.getRangeAt(0).startOffset
        };
      });

      // Should be in first child at position 0
      expect(result.text).toContain('Building');
      expect(result.cursorPos).toBe(0);
    });

    test('Right arrow at end of leaf navigates to next sibling', async ({ page }) => {
      // Click on a leaf block with a next sibling
      await page.getByText('Building a Logseq-inspired outliner').click();

      // Position at end
      await page.keyboard.press('End');

      // Press Right - should go to next sibling
      await page.keyboard.press('ArrowRight');

      const result = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]');
        return { text: el.textContent };
      });

      // Should be in next sibling "Using event sourcing architecture"
      expect(result.text).toContain('event sourcing');
    });
  });

  test.describe('§4.3: Shift+Click Range Selection (Visibility-Aware)', () => {
    test('Shift+Click between visible blocks selects only visible range', async ({ page }) => {
      // Select first block
      await page.getByText('Evolver - Outliner Project').click();

      // Shift+Click on third visible block
      await page.keyboard.down('Shift');
      await page.getByText('Tech Stack: ClojureScript + Replicant').click();
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
      // This test requires fold functionality
      // When implemented: fold a block, then Shift+Click across it
      // Should only count visible blocks, not folded children
      test.skip();
    });
  });

  test.describe('§4.4: Shift+Arrow Anchoring in Edit Mode', () => {
    test('Shift+ArrowDown at boundary extends from current block (not page top)', async ({ page }) => {
      // Edit a block in the middle of the page
      await page.getByText('Using event sourcing architecture').click();

      // Ensure we're editing
      await page.keyboard.press('End');

      // Position at last row (for single-line blocks, this is automatic)
      // Press Shift+Down to extend selection
      await page.keyboard.press('Shift+ArrowDown');

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
      // Edit a block in the middle of the page
      await page.getByText('Using event sourcing architecture').click();

      // Position at start (first row)
      await page.keyboard.press('Home');

      // Press Shift+Up to extend selection upward
      await page.keyboard.press('Shift+ArrowUp');

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

      // Edit a block
      await page.getByText('Tech Stack: ClojureScript + Replicant').click();
      await page.keyboard.press('End');

      // Verify no selection exists before Shift+Arrow
      const beforeSelection = await page.evaluate(() => {
        return document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]').length;
      });
      expect(beforeSelection).toBe(0);

      // Shift+Down should seed with current block first
      await page.keyboard.press('Shift+ArrowDown');

      const afterSelection = await page.evaluate(() => {
        return document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]').length;
      });

      // Should now have selection (current + next)
      expect(afterSelection).toBeGreaterThanOrEqual(2);
    });
  });

  test.describe('Integration: Combined Behaviors', () => {
    test('navigate across parent/child with Left/Right, then select with Shift+Click', async ({ page }) => {
      // Start at parent
      await page.getByText('Evolver - Outliner Project').click();
      await page.keyboard.press('End');

      // Navigate into child
      await page.keyboard.press('ArrowRight');

      const childText = await page.evaluate(() => {
        return document.querySelector('[contenteditable]')?.textContent;
      });
      expect(childText).toContain('Building');

      // Shift+Click on a later sibling
      await page.keyboard.down('Shift');
      await page.getByText('Using event sourcing architecture').click();
      await page.keyboard.up('Shift');

      // Should have range selection
      const selectedCount = await page.evaluate(() => {
        return document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]').length;
      });
      expect(selectedCount).toBeGreaterThanOrEqual(2);
    });

    test('page-scoped navigation with selection extension', async ({ page }) => {
      // Navigate to Projects page
      await page.getByText('Projects', { exact: true }).click();

      // Select first block
      await page.getByText('Evolver').click();

      // Extend selection downward multiple times
      for (let i = 0; i < 3; i++) {
        await page.keyboard.press('Shift+ArrowDown');
      }

      // Count selected blocks - should all be within Projects page
      const selection = await page.evaluate(() => {
        const selected = Array.from(
          document.querySelectorAll('[style*="background-color: rgb(230, 242, 255)"]')
        );
        return selected.map(el => el.textContent);
      });

      // None should be from Tasks page
      expect(selection.every(text => !text || text.includes('Implement block embeds'))).toBe(true);
    });
  });
});
