import { test, expect } from '@playwright/test';

/**
 * E2E Tests for Alt+Click Subtree Toggle (FR-Pointer-01)
 *
 * LOGSEQ PARITY: Alt+Click on bullet toggles entire subtree,
 * not just direct children.
 *
 * Reference: dev/specs/LOGSEQ_SPEC.md §9.3
 */

test.describe('Alt+Click Subtree Toggle (FR-Pointer-01)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8000');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('Alt+Click collapses entire subtree', async ({ page }) => {
    // Create nested structure: Parent → Child1 → Grandchild1, Child2 → Grandchild2
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Create Parent
    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');

    // Create Child1 (indented)
    await page.keyboard.type('Child1');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    // Create Grandchild1 (indented under Child1)
    await page.keyboard.type('Grandchild1');
    await page.keyboard.press('Tab');

    // Navigate back to Parent level
    await page.keyboard.press('Escape');
    const parentBlock = page.locator('[data-block-id]').filter({ hasText: 'Parent' }).first();
    await parentBlock.click();
    await page.keyboard.press('Enter');

    // Move to end of Parent and create Child2
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('ArrowRight');
    }
    await page.keyboard.press('Enter');
    await page.keyboard.type('Child2');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    // Create Grandchild2
    await page.keyboard.type('Grandchild2');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');

    // Verify all blocks visible
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Child1' })).toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild1' })).toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Child2' })).toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild2' })).toBeVisible();

    // Find the bullet for Parent block
    const parentBullet = parentBlock.locator('span').first();

    // Alt+Click on Parent bullet to collapse entire subtree
    await parentBullet.click({ modifiers: ['Alt'] });

    // Verify entire subtree is hidden (all descendants)
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Child1' })).not.toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild1' })).not.toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Child2' })).not.toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild2' })).not.toBeVisible();

    // Verify Parent is still visible with collapsed indicator
    await expect(parentBlock).toBeVisible();
    await expect(parentBullet).toContainText('▸');
  });

  test('Alt+Click expands entire subtree', async ({ page }) => {
    // Setup: Create nested structure and collapse with Alt+Click
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Child');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Grandchild');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');

    const parentBlock = page.locator('[data-block-id]').filter({ hasText: 'Parent' }).first();
    const parentBullet = parentBlock.locator('span').first();

    // Collapse with Alt+Click
    await parentBullet.click({ modifiers: ['Alt'] });

    // Verify collapsed
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Child' })).not.toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild' })).not.toBeVisible();

    // Alt+Click again to expand entire subtree
    await parentBullet.click({ modifiers: ['Alt'] });

    // Verify entire subtree is visible
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Child' })).toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild' })).toBeVisible();

    // Verify Parent shows expanded indicator
    await expect(parentBullet).toContainText('▾');
  });

  test('Regular click toggles only direct children, not grandchildren', async ({ page }) => {
    // Create nested structure
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Child');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Grandchild');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');

    const parentBlock = page.locator('[data-block-id]').filter({ hasText: 'Parent' }).first();
    const parentBullet = parentBlock.locator('span').first();

    // Regular click on Parent bullet (no Alt)
    await parentBullet.click();

    // Verify only direct children hidden, grandchildren not affected
    // (In Logseq, regular click just hides children; they're still there when parent re-expands)
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Child' })).not.toBeVisible();

    // Re-expand to verify grandchild is still there
    await parentBullet.click();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Child' })).toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild' })).toBeVisible();
  });

  test('Alt+Click on multi-level tree collapses all descendants', async ({ page }) => {
    // Create deep tree: Root → L1 → L2 → L3
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    await page.keyboard.type('Root');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Level 1');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Level 2');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Level 3');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');

    const rootBlock = page.locator('[data-block-id]').filter({ hasText: 'Root' }).first();
    const rootBullet = rootBlock.locator('span').first();

    // Alt+Click to collapse all levels
    await rootBullet.click({ modifiers: ['Alt'] });

    // Verify all descendants hidden
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Level 1' })).not.toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Level 2' })).not.toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Level 3' })).not.toBeVisible();
  });

  test('Alt+Click vs regular click behavior difference', async ({ page }) => {
    // Create Parent → Child → Grandchild
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Child');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Grandchild');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');

    const parentBlock = page.locator('[data-block-id]').filter({ hasText: 'Parent' }).first();
    const childBlock = page.locator('[data-block-id]').filter({ hasText: /^Child$/ }).first();
    const parentBullet = parentBlock.locator('span').first();
    const childBullet = childBlock.locator('span').first();

    // First, regular click on Child to collapse Grandchild
    await childBullet.click();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild' })).not.toBeVisible();

    // Now regular click on Parent to collapse Child
    await parentBullet.click();
    await expect(childBlock).not.toBeVisible();

    // Regular click to expand Parent shows Child (with Grandchild still collapsed)
    await parentBullet.click();
    await expect(childBlock).toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild' })).not.toBeVisible();

    // Now Alt+Click to expand entire subtree
    await parentBullet.click({ modifiers: ['Alt'] });
    await expect(childBlock).toBeVisible();
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Grandchild' })).toBeVisible();
  });
});
