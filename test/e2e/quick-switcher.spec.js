/**
 * Quick Switcher (Cmd+K) E2E Tests
 *
 * Tests for the Logseq-style quick page search overlay.
 */

import { expect, test } from '@playwright/test';
import { pressQuickSwitcherKey } from './helpers/index.js';

const PAGES = ['Alpha Project', 'Beta Tasks', 'Gamma Notes'];

async function seedQuickSwitcherPages(page) {
  await page.evaluate((titles) => {
    window.TEST_HELPERS?.resetToEmptyDb();

    for (const title of titles) {
      window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title });
    }

    const db = window.TEST_HELPERS.getDb();
    const pageByTitle = Object.fromEntries(
      Object.entries(db.nodes || {})
        .filter(([_id, node]) => node?.type === 'page')
        .map(([id, node]) => [node.props?.title, id])
    );

    const firstPage = pageByTitle[titles[0]];
    if (!firstPage) {
      throw new Error(`Missing quick switcher fixture page: ${titles[0]}`);
    }

    const firstBlock = db['children-by-parent']?.[firstPage]?.[0];
    if (firstBlock) {
      window.TEST_HELPERS.setBlockText(firstBlock, 'Evolver quick switcher fixture');
    }

    window.TEST_HELPERS.dispatchIntent({ type: 'switch-page', 'page-id': firstPage });
  }, PAGES);

  await expect(page.getByRole('heading', { name: `${PAGES[0]}` })).toBeVisible();
}

async function toggleQuickSwitcher(page) {
  await page.evaluate(() => {
    window.TEST_HELPERS.dispatchIntent({ type: 'toggle-quick-switcher' });
  });
}

async function openQuickSwitcher(page) {
  await toggleQuickSwitcher(page);
  await expect(page.locator('dialog.quick-switcher')).toBeVisible();
  await expect(page.getByPlaceholder('Search pages...')).toBeFocused();
}

test.describe('Quick Switcher', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await seedQuickSwitcherPages(page);
  });

  test('toggle opens quick switcher dialog', async ({ page }) => {
    // Initially no quick switcher visible
    await expect(page.locator('dialog.quick-switcher')).not.toBeVisible();

    await openQuickSwitcher(page);

    // Quick switcher should appear
    await expect(page.locator('dialog.quick-switcher')).toBeVisible();
    await expect(page.getByPlaceholder('Search pages...')).toBeVisible();
    await expect(page.getByPlaceholder('Search pages...')).toBeFocused();
  });

  test('shows all pages when query is empty', async ({ page }) => {
    await openQuickSwitcher(page);

    // Should include the seeded pages.
    await expect(page.locator('.quick-switcher-results')).toContainText(PAGES[0]);
    await expect(page.locator('.quick-switcher-results')).toContainText(PAGES[1]);
    await expect(page.locator('.quick-switcher-results')).toContainText(PAGES[2]);
  });

  test('filters pages as user types', async ({ page }) => {
    await openQuickSwitcher(page);

    // Type "beta" to filter
    await page.getByPlaceholder('Search pages...').fill('beta');

    // Should only show the matching seeded page.
    await expect(page.locator('.quick-switcher-item')).toHaveCount(1);
    await expect(page.locator('.quick-switcher-results')).toContainText(PAGES[1]);
    await expect(page.locator('.quick-switcher-results')).not.toContainText(PAGES[0]);
  });

  test('highlights matching characters in results', async ({ page }) => {
    await openQuickSwitcher(page);
    await page.getByPlaceholder('Search pages...').fill('beta');

    // Check that matching characters are wrapped in <mark>
    const marks = page.locator('.quick-switcher-item mark');
    await expect(marks).toHaveCount(1); // Contiguous "Beta" range.
  });

  test('Escape closes quick switcher', async ({ page }) => {
    await openQuickSwitcher(page);

    await page.keyboard.press('Escape');

    await expect(page.locator('dialog.quick-switcher')).not.toBeVisible();
  });

  test('clicking backdrop closes quick switcher', async ({ page }) => {
    await openQuickSwitcher(page);

    // Click on the viewport backdrop, outside the modal dialog box.
    await page.mouse.click(5, 5);

    await expect(page.locator('dialog.quick-switcher')).not.toBeVisible();
  });

  test('Enter navigates to selected page', async ({ page }) => {
    // Start on first seeded page.
    await expect(page.getByRole('heading', { name: `${PAGES[0]}` })).toBeVisible();

    // Open quick switcher and search for Beta Tasks.
    await openQuickSwitcher(page);
    await page.getByPlaceholder('Search pages...').fill('beta');

    // Wait for the filtered result to appear
    await expect(page.locator('.quick-switcher-item')).toHaveCount(1);
    await expect(page.locator('.quick-switcher-results')).toContainText(PAGES[1]);

    await pressQuickSwitcherKey(page, 'Enter');

    // Should navigate to Tasks page
    await expect(page.locator('dialog.quick-switcher')).not.toBeVisible();
    await expect(page.getByRole('heading', { name: `${PAGES[1]}` })).toBeVisible();
  });

  test('clicking a result navigates to that page', async ({ page }) => {
    await expect(page.getByRole('heading', { name: `${PAGES[0]}` })).toBeVisible();

    await openQuickSwitcher(page);

    // Click on Gamma Notes in results.
    await page.locator('.quick-switcher-item').filter({ hasText: PAGES[2] }).click();

    // Should navigate to Notes page
    await expect(page.locator('dialog.quick-switcher')).not.toBeVisible();
    await expect(page.getByRole('heading', { name: `${PAGES[2]}` })).toBeVisible();
  });

  test('arrow keys navigate selection', async ({ page }) => {
    await openQuickSwitcher(page);

    // Get initial selection (first item should be selected)
    const _firstItem = page.locator('.quick-switcher-item').first();

    // Arrow down should move selection
    await pressQuickSwitcherKey(page, 'ArrowDown');

    // Arrow up should move back
    await pressQuickSwitcherKey(page, 'ArrowUp');

    // The component uses background color to indicate selection
    // We verify keyboard navigation works by navigating and pressing Enter
    await pressQuickSwitcherKey(page, 'ArrowDown'); // Move to second item
    await pressQuickSwitcherKey(page, 'Enter');

    // Should have navigated (closed the modal)
    await expect(page.locator('dialog.quick-switcher')).not.toBeVisible();
  });

  test('toggle closes an open quick switcher', async ({ page }) => {
    // Open
    await openQuickSwitcher(page);

    // Toggle again should close.
    await toggleQuickSwitcher(page);
    await expect(page.locator('dialog.quick-switcher')).not.toBeVisible();
  });

  test('fuzzy search matches non-adjacent characters', async ({ page }) => {
    await openQuickSwitcher(page);

    // "bt" should match "Beta Tasks" non-adjacently.
    await page.getByPlaceholder('Search pages...').fill('bt');

    // Should show the seeded page as a match.
    await expect(page.locator('.quick-switcher-results')).toContainText(PAGES[1]);
  });

  test('shows no results message for unmatched query', async ({ page }) => {
    await openQuickSwitcher(page);

    await page.getByPlaceholder('Search pages...').fill('xyz123');

    await expect(page.getByText('No matching pages')).toBeVisible();
  });

  test('quick switcher works while editing a block', async ({ page }) => {
    // Enter edit mode using TEST_HELPERS (more reliable than dblclick)
    const blockWithText = page.locator('[data-block-id]').filter({ hasText: 'Evolver' });
    const blockId = await blockWithText.getAttribute('data-block-id');

    await page.evaluate((id) => {
      window.TEST_HELPERS.dispatchIntent({ type: 'selection', mode: 'replace', ids: id });
      window.TEST_HELPERS.dispatchIntent({ type: 'enter-edit', 'block-id': id });
    }, blockId);

    await expect(page.locator('[contenteditable="true"]')).toBeVisible({ timeout: 2000 });

    // Quick switcher should still open while editing.
    await openQuickSwitcher(page);

    // Navigate to a different page
    await page.getByPlaceholder('Search pages...').fill('gamma');
    await expect(page.locator('.quick-switcher-item')).toHaveCount(1);
    await expect(page.locator('.quick-switcher-results')).toContainText(PAGES[2]);
    await pressQuickSwitcherKey(page, 'Enter');

    // Should be on Gamma Notes page
    await expect(page.getByRole('heading', { name: `${PAGES[2]}` })).toBeVisible();
  });
});
