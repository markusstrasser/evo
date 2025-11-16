import { test, expect } from '@playwright/test';

/**
 * Logseq Parity: Scope Boundaries (FR-Scope-01..03)
 *
 * Spec: LOGSEQ_SPEC.md §7 (Fold & Zoom Constraints)
 * PRD: LOGSEQ_PARITY_PRD.md §5.3
 * Gap: LOGSEQ_PARITY.md G-Scope-01
 *
 * When zoomed into block Z, any operation that would move a block outside of Z
 * (outdent, Shift+Arrow extend, Cmd+Shift+Arrow move) is a no-op.
 */

test.describe('Scope Boundaries (FR-Scope-01..03)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8080/blocks.html');
    await page.waitForSelector('[data-block-id]');
  });

  /**
   * Helper: Zoom into a block by clicking it and pressing Cmd+.
   * Verifies zoom worked by checking that only the block's children are visible.
   */
  async function zoomIntoBlock(page, blockId) {
    // Click on the block to select it
    await page.click(`[data-block-id="${blockId}"]`);
    await page.waitForTimeout(100);

    // Press Cmd+. to zoom in
    await page.keyboard.press('Meta+Period');
    await page.waitForTimeout(200);

    // Verify zoom worked: block should be the only root-level block visible
    // (its children will be visible, but not its siblings)
    const visibleBlocks = await page.locator('[data-block-id]').all();
    const visibleIds = await Promise.all(
      visibleBlocks.map(b => b.getAttribute('data-block-id'))
    );

    // When zoomed into proj-1, we should see proj-1 and its children (proj-1-1, proj-1-2)
    // but NOT proj-2, proj-3, or other top-level blocks
    if (blockId === 'proj-1') {
      expect(visibleIds).toContain('proj-1');
      expect(visibleIds).toContain('proj-1-1');
      expect(visibleIds).toContain('proj-1-2');
      expect(visibleIds).not.toContain('proj-2');
      expect(visibleIds).not.toContain('proj-3');
    }
  }

  /**
   * Helper: Get all visible block IDs in DOM order
   */
  async function getVisibleBlockIds(page) {
    const blocks = await page.locator('[data-block-id]').all();
    return await Promise.all(blocks.map(b => b.getAttribute('data-block-id')));
  }

  /**
   * Helper: Get parent-child relationships from DOM structure
   * Returns a map of {parentId: [childId, childId, ...]}
   */
  async function getBlockStructure(page) {
    return await page.evaluate(() => {
      const structure = {};
      const blocks = document.querySelectorAll('[data-block-id]');

      blocks.forEach(block => {
        const blockId = block.getAttribute('data-block-id');
        const childDivs = block.querySelectorAll(':scope > div[style*="margin-top"] > [data-block-id]');

        if (childDivs.length > 0) {
          structure[blockId] = Array.from(childDivs).map(c => c.getAttribute('data-block-id'));
        }
      });

      return structure;
    });
  }

  test.describe('FR-Scope-02: Outdent blocked by zoom boundary', () => {
    test('Shift+Tab on block inside zoom root is no-op when grandparent outside zoom', async ({ page }) => {
      await zoomIntoBlock(page, 'proj-1');

      // Get structure before outdent
      const beforeStructure = await getBlockStructure(page);
      expect(beforeStructure['proj-1']).toEqual(['proj-1-1', 'proj-1-2']);

      // Click on proj-1-1 to select it
      await page.click('[data-block-id="proj-1-1"]');
      await page.waitForTimeout(50);

      // Try to outdent proj-1-1 (would move it to projects level, outside zoom)
      await page.keyboard.press('Shift+Tab');
      await page.waitForTimeout(100);

      // Structure should be unchanged (outdent was blocked)
      const afterStructure = await getBlockStructure(page);
      expect(afterStructure['proj-1']).toEqual(['proj-1-1', 'proj-1-2']);

      // proj-1-1 should still be visible (not hidden because it didn't leave zoom)
      const visibleIds = await getVisibleBlockIds(page);
      expect(visibleIds).toContain('proj-1-1');
    });

    test('Shift+Tab works normally within zoom scope', async ({ page }) => {
      await zoomIntoBlock(page, 'proj-1');

      // Create a nested structure: proj-1-1 -> [child]
      await page.click('[data-block-id="proj-1-1"]');
      await page.waitForTimeout(50);

      // Enter edit mode and create child
      await page.keyboard.press('Enter');
      await page.waitForTimeout(100);
      await page.keyboard.press('Tab');
      await page.waitForTimeout(100);
      await page.keyboard.type('test-child');
      await page.keyboard.press('Escape');
      await page.waitForTimeout(100);

      // Find the child block (should be nested under proj-1-1)
      const structureBefore = await getBlockStructure(page);
      expect(structureBefore['proj-1-1']).toBeDefined();
      expect(structureBefore['proj-1-1'].length).toBe(1);
      const childId = structureBefore['proj-1-1'][0];

      // Click on the child to select it
      await page.click(`[data-block-id="${childId}"]`);
      await page.waitForTimeout(50);

      // Outdent the child (should work - moves to proj-1 level, still within zoom)
      await page.keyboard.press('Shift+Tab');
      await page.waitForTimeout(100);

      // child should now be under proj-1 (sibling of proj-1-1)
      const structureAfter = await getBlockStructure(page);
      expect(structureAfter['proj-1']).toContain(childId);
      expect(structureAfter['proj-1-1'] || []).not.toContain(childId);
    });
  });

  test.describe('FR-Scope-02: Move up (climb) blocked by zoom boundary', () => {
    test('Cmd+Shift+Up on first child is no-op when climb target outside zoom', async ({ page }) => {
      await zoomIntoBlock(page, 'proj-1');

      // Click on proj-1-1 (first child of proj-1)
      await page.click('[data-block-id="proj-1-1"]');
      await page.waitForTimeout(50);

      // Get structure before move
      const beforeStructure = await getBlockStructure(page);
      expect(beforeStructure['proj-1']).toEqual(['proj-1-1', 'proj-1-2']);

      // Try to move up (climb) - would move proj-1-1 to projects level
      await page.keyboard.press('Meta+Shift+ArrowUp');
      await page.waitForTimeout(100);

      // Structure should be unchanged (climb was blocked)
      const afterStructure = await getBlockStructure(page);
      expect(afterStructure['proj-1']).toEqual(['proj-1-1', 'proj-1-2']);
    });

    test('Cmd+Shift+Up works normally within zoom scope', async ({ page }) => {
      await zoomIntoBlock(page, 'proj-1');

      // Create nested structure: proj-1-1 -> [child-a, child-b]
      await page.click('[data-block-id="proj-1-1"]');
      await page.keyboard.press('Enter');
      await page.waitForTimeout(100);
      await page.keyboard.press('Tab');
      await page.keyboard.type('child-a');
      await page.keyboard.press('Enter');
      await page.keyboard.type('child-b');
      await page.keyboard.press('Escape');
      await page.waitForTimeout(100);

      // Get the child block IDs
      const structure = await getBlockStructure(page);
      const [childA, childB] = structure['proj-1-1'];

      // Click on child-a (first child of proj-1-1)
      await page.click(`[data-block-id="${childA}"]`);
      await page.waitForTimeout(50);

      // Move up (climb) - should move child-a to proj-1 level
      await page.keyboard.press('Meta+Shift+ArrowUp');
      await page.waitForTimeout(100);

      // child-a should now be under proj-1 (before proj-1-1)
      const afterStructure = await getBlockStructure(page);
      expect(afterStructure['proj-1'][0]).toBe(childA);
      expect(afterStructure['proj-1']).toContain('proj-1-1');
      expect(afterStructure['proj-1-1'] || []).not.toContain(childA);
    });
  });

  test.describe('FR-Scope-02: Move down (descend) blocked by zoom boundary', () => {
    test('Cmd+Shift+Down on last child is no-op when descend target outside zoom', async ({ page }) => {
      await zoomIntoBlock(page, 'proj-1');

      // Click on proj-1-2 (last child of proj-1)
      await page.click('[data-block-id="proj-1-2"]');
      await page.waitForTimeout(50);

      // Get structure before move
      const beforeStructure = await getBlockStructure(page);
      expect(beforeStructure['proj-1']).toEqual(['proj-1-1', 'proj-1-2']);

      // Try to move down (descend) - would try to descend into proj-2 (outside zoom)
      await page.keyboard.press('Meta+Shift+ArrowDown');
      await page.waitForTimeout(100);

      // Structure should be unchanged (descend was blocked)
      const afterStructure = await getBlockStructure(page);
      expect(afterStructure['proj-1']).toEqual(['proj-1-1', 'proj-1-2']);
    });

    test('Cmd+Shift+Down works normally within zoom scope', async ({ page }) => {
      await zoomIntoBlock(page, 'proj-1');

      // Create nested structure: proj-1-2 -> [nested-child]
      await page.click('[data-block-id="proj-1-2"]');
      await page.keyboard.press('Enter');
      await page.waitForTimeout(100);
      await page.keyboard.press('Tab');
      await page.keyboard.type('nested-child');
      await page.keyboard.press('Escape');
      await page.waitForTimeout(100);

      // Click on proj-1-1 (has next sibling proj-1-2)
      await page.click('[data-block-id="proj-1-1"]');
      await page.waitForTimeout(50);

      // Move down (descend) - should move proj-1-1 into proj-1-2
      await page.keyboard.press('Meta+Shift+ArrowDown');
      await page.waitForTimeout(100);

      // proj-1-1 should now be under proj-1-2 (as first child)
      const afterStructure = await getBlockStructure(page);
      expect(afterStructure['proj-1-2'][0]).toBe('proj-1-1');
      expect(afterStructure['proj-1']).toEqual(['proj-1-2']);
    });
  });

  test.describe('FR-Scope-01: Navigation stays within visible outline', () => {
    test('ArrowDown navigation respects zoom root', async ({ page }) => {
      await zoomIntoBlock(page, 'proj-1');

      // Click on proj-1-2 (last visible block in zoom)
      await page.click('[data-block-id="proj-1-2"]');
      await page.waitForTimeout(50);

      // Press ArrowDown - should NOT navigate to proj-2 (outside zoom)
      await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(50);

      // proj-2 should not be visible
      const visibleIds = await getVisibleBlockIds(page);
      expect(visibleIds).not.toContain('proj-2');

      // Focus should still be within the zoom scope (either proj-1-2 or wrapped to first)
      const focusedElement = await page.evaluate(() => document.activeElement?.closest('[data-block-id]')?.getAttribute('data-block-id'));
      const zoomBlocks = ['proj-1', 'proj-1-1', 'proj-1-2'];
      expect(zoomBlocks).toContain(focusedElement);
    });

    test('Zoom out restores normal scope', async ({ page }) => {
      await zoomIntoBlock(page, 'proj-1');

      // Verify proj-2 is NOT visible while zoomed
      let visibleIds = await getVisibleBlockIds(page);
      expect(visibleIds).not.toContain('proj-2');

      // Zoom out with Cmd+,
      await page.keyboard.press('Meta+Comma');
      await page.waitForTimeout(200);

      // Now proj-2 should be visible again
      visibleIds = await getVisibleBlockIds(page);
      expect(visibleIds).toContain('proj-2');
      expect(visibleIds).toContain('proj-3');

      // Now outdent should work (can move proj-1-1 to projects level)
      await page.click('[data-block-id="proj-1-1"]');
      await page.waitForTimeout(50);

      const beforeOutdent = await getBlockStructure(page);
      expect(beforeOutdent['proj-1']).toContain('proj-1-1');

      await page.keyboard.press('Shift+Tab');
      await page.waitForTimeout(100);

      // proj-1-1 should now be sibling of proj-1 (under projects)
      const afterOutdent = await getBlockStructure(page);
      expect(afterOutdent['projects']).toBeDefined();
      expect(afterOutdent['proj-1'] || []).not.toContain('proj-1-1');
    });
  });
});
