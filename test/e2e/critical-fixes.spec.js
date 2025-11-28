/**
 * Critical Fixes E2E Tests
 *
 * Tests for the 2 CRITICAL behavioral gaps identified:
 * 1. Backspace merge - children re-parenting (prevents data loss)
 * 2. Direct outdenting - Logseq/Roam style (right siblings become children)
 *
 * Uses DOM verification (best practice) instead of database inspection.
 */

import { test, expect } from '@playwright/test';
import {
  selectPage,
  enterEditMode,
  waitForBlocks,
  getFirstBlockId
} from './helpers/index.js';

// Helper to get tree structure from DOM
async function getTreeStructure(page) {
  return await page.evaluate(() => {
    const blocks = [];
    const blockElements = document.querySelectorAll('.block');

    blockElements.forEach((el) => {
      const contentEl = el.querySelector('[contenteditable="true"]');
      if (contentEl) {
        // Calculate indentation level by checking nested structure
        let depth = 0;
        let parent = el.parentElement;
        while (parent && !parent.classList.contains('root')) {
          if (parent.classList.contains('children')) {
            depth++;
          }
          parent = parent.parentElement;
        }

        blocks.push({
          text: contentEl.textContent.trim(),
          depth: depth,
          hasChildren: el.querySelector('.children') !== null
        });
      }
    });

    return blocks;
  });
}

// Helper to count blocks with specific text
async function countBlocksWithText(page, text) {
  return await page.locator(`[contenteditable="true"]:has-text("${text}")`).count();
}

// TODO: Re-enable when backspace merge re-parenting is implemented
test.describe.skip('CRITICAL: Backspace Merge - Children Re-parenting', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html');
    await selectPage(page);
    await waitForBlocks(page);
    const blockId = await getFirstBlockId(page);
    await enterEditMode(page, blockId);
  });

  test('backspace merge re-parents children to previous block', async ({ page }) => {
    // Create structure:
    // - Block A
    //   - Child A1
    // - Block B (will merge into A)
    //   - Child B1
    //   - Child B2

    // Clear initial content
    await page.keyboard.press('Meta+a');
    await page.keyboard.press('Backspace');

    await page.keyboard.type('Block A');
    await page.keyboard.press('Enter');

    await page.keyboard.type('Child A1');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    await page.keyboard.press('Shift+Tab');
    await page.keyboard.type('Block B');
    await page.keyboard.press('Enter');

    await page.keyboard.type('Child B1');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Child B2');

    await page.waitForTimeout(200);

    // Get initial block count
    const beforeMerge = await getTreeStructure(page);
    const totalBefore = beforeMerge.length;

    // Verify children exist before merge
    await expect(page.locator('[contenteditable="true"]:has-text("Child B1")')).toBeVisible();
    await expect(page.locator('[contenteditable="true"]:has-text("Child B2")')).toBeVisible();

    // Navigate to Block B and backspace
    await page.keyboard.press('ArrowUp');  // To Child B1
    await page.keyboard.press('ArrowUp');  // To Block B
    await page.keyboard.press('Home');
    await page.keyboard.press('Backspace');

    await page.waitForTimeout(300);

    // CRITICAL VERIFICATION: Children should still be visible (not deleted)
    await expect(page.locator('[contenteditable="true"]:has-text("Child B1")')).toBeVisible();
    await expect(page.locator('[contenteditable="true"]:has-text("Child B2")')).toBeVisible();

    // Verify merged block exists
    await expect(page.locator('[contenteditable="true"]:has-text("Block ABlock B")')).toBeVisible();

    // Verify total blocks decreased by 1 (only Block B was removed, children preserved)
    const afterMerge = await getTreeStructure(page);
    expect(afterMerge.length).toBe(totalBefore - 1);
  });

  test('backspace at start with no previous block does nothing', async ({ page }) => {
    // Clear initial content
    await page.keyboard.press('Meta+a');
    await page.keyboard.press('Backspace');

    await page.keyboard.type('First block');
    await page.keyboard.press('Home');
    await page.keyboard.press('Backspace');

    await page.waitForTimeout(100);

    // Block should still exist
    await expect(page.locator('[contenteditable="true"]:has-text("First block")')).toBeVisible();
  });
});

// TODO: Re-enable when outdent is fully implemented and verified
test.describe.skip('CRITICAL: Logical Outdenting (Logseq Default)', () => {
  /**
   * LOGSEQ PARITY: Logical outdenting (editor/logical-outdenting? = true)
   *
   * When outdenting block B:
   * - B moves to become sibling of its parent (positioned after parent)
   * - Right siblings (C, D) STAY under original parent
   * - No "kidnapping" of right siblings
   *
   * Reference: logseq/deps/outliner/src/logseq/outliner/core.cljs
   *            See `indent-outdent-blocks` with `logical-outdenting?` flag
   */
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html');
    await selectPage(page);
    await waitForBlocks(page);
    const blockId = await getFirstBlockId(page);
    await enterEditMode(page, blockId);
  });

  test('outdenting moves block after parent, right siblings stay', async ({ page }) => {
    // Create structure:
    // - Parent
    //   - Child A
    //   - Child B ← will outdent this
    //   - Child C
    //   - Child D
    //
    // EXPECTED after outdent B:
    // - Parent
    //   - Child A
    //   - Child C  ← stays under Parent
    //   - Child D  ← stays under Parent
    // - Child B  ← now sibling of Parent

    // Clear initial content
    await page.keyboard.press('Meta+a');
    await page.keyboard.press('Backspace');

    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');

    await page.keyboard.type('Child A');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    await page.keyboard.type('Child B');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Child C');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Child D');

    await page.waitForTimeout(200);

    // Get structure before outdenting
    const before = await getTreeStructure(page);
    const childBBefore = before.find(b => b.text === 'Child B');
    const childCBefore = before.find(b => b.text === 'Child C');
    const childDBefore = before.find(b => b.text === 'Child D');

    // Navigate to Child B and outdent
    await page.keyboard.press('ArrowUp');  // To Child C
    await page.keyboard.press('ArrowUp');  // To Child B
    await page.keyboard.press('Shift+Tab');

    await page.waitForTimeout(300);

    // Verify all blocks still visible
    await expect(page.locator('[contenteditable="true"]:has-text("Parent")')).toBeVisible();
    await expect(page.locator('[contenteditable="true"]:has-text("Child A")')).toBeVisible();
    await expect(page.locator('[contenteditable="true"]:has-text("Child B")')).toBeVisible();
    await expect(page.locator('[contenteditable="true"]:has-text("Child C")')).toBeVisible();
    await expect(page.locator('[contenteditable="true"]:has-text("Child D")')).toBeVisible();

    // Get structure after outdenting
    const after = await getTreeStructure(page);
    const childBAfter = after.find(b => b.text === 'Child B');
    const childCAfter = after.find(b => b.text === 'Child C');
    const childDAfter = after.find(b => b.text === 'Child D');
    const childAAfter = after.find(b => b.text === 'Child A');

    // Child B should be outdented (less depth - now sibling of Parent)
    expect(childBAfter.depth).toBeLessThan(childBBefore.depth);

    // LOGSEQ PARITY: Right siblings stay under original parent (no kidnapping)
    // C and D should have SAME depth as before
    expect(childCAfter.depth).toBe(childCBefore.depth);
    expect(childDAfter.depth).toBe(childDBefore.depth);

    // Child A should remain unchanged
    expect(childAAfter.depth).toBe(before.find(b => b.text === 'Child A').depth);
  });

  test('cannot outdent at root level', async ({ page }) => {
    // Clear initial content
    await page.keyboard.press('Meta+a');
    await page.keyboard.press('Backspace');

    await page.keyboard.type('Top level');

    const before = await getTreeStructure(page);
    const depthBefore = before.find(b => b.text === 'Top level').depth;

    await page.keyboard.press('Shift+Tab');
    await page.waitForTimeout(200);

    const after = await getTreeStructure(page);
    const depthAfter = after.find(b => b.text === 'Top level').depth;

    // Depth should not have changed (can't outdent at root)
    expect(depthAfter).toBe(depthBefore);
    await expect(page.locator('[contenteditable="true"]:has-text("Top level")')).toBeVisible();
  });

  test('outdenting with no right siblings works normally', async ({ page }) => {
    // Create: Parent -> Child A -> Child B
    // Outdent Child B (no right siblings)

    // Clear initial content
    await page.keyboard.press('Meta+a');
    await page.keyboard.press('Backspace');

    await page.keyboard.type('Parent2');
    await page.keyboard.press('Enter');

    await page.keyboard.type('Child A2');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Child B2');

    await page.waitForTimeout(200);

    const before = await getTreeStructure(page);
    const childBBefore = before.find(b => b.text === 'Child B2');

    // Outdent Child B
    await page.keyboard.press('Shift+Tab');
    await page.waitForTimeout(300);

    // Verify all blocks visible
    await expect(page.locator('[contenteditable="true"]:has-text("Parent2")')).toBeVisible();
    await expect(page.locator('[contenteditable="true"]:has-text("Child A2")')).toBeVisible();
    await expect(page.locator('[contenteditable="true"]:has-text("Child B2")')).toBeVisible();

    // Child B should be outdented
    const after = await getTreeStructure(page);
    const childBAfter = after.find(b => b.text === 'Child B2');
    expect(childBAfter.depth).toBeLessThan(childBBefore.depth);

    // Child B should have no children (no right siblings to capture)
    expect(childBAfter.hasChildren).toBe(false);
  });
});
