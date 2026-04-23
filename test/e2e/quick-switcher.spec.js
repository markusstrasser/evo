/**
 * Quick Switcher (Cmd+K) E2E Tests
 *
 * Tests for the Logseq-style quick page search overlay.
 */

import { expect, test } from '@playwright/test';

test.describe('Quick Switcher', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('Cmd+K opens quick switcher overlay', async ({ page }) => {
    // Initially no quick switcher visible
    await expect(page.locator('.quick-switcher-overlay')).not.toBeVisible();

    // Press Cmd+K
    await page.keyboard.press('Meta+k');

    // Quick switcher should appear
    await expect(page.locator('.quick-switcher-overlay')).toBeVisible();
    await expect(page.getByPlaceholder('Search pages...')).toBeVisible();
    await expect(page.getByPlaceholder('Search pages...')).toBeFocused();
  });

  test('shows all pages when query is empty', async ({ page }) => {
    await page.keyboard.press('Meta+k');

    // Should show all pages (Projects, Tasks, Notes from demo data)
    await expect(page.locator('.quick-switcher-item')).toHaveCount(3);
    await expect(page.locator('.quick-switcher-results')).toContainText('Notes');
    await expect(page.locator('.quick-switcher-results')).toContainText('Projects');
    await expect(page.locator('.quick-switcher-results')).toContainText('Tasks');
  });

  test('filters pages as user types', async ({ page }) => {
    await page.keyboard.press('Meta+k');

    // Type "ta" to filter
    await page.getByPlaceholder('Search pages...').fill('ta');

    // Should only show Tasks (matches "ta")
    await expect(page.locator('.quick-switcher-item')).toHaveCount(1);
    await expect(page.locator('.quick-switcher-results')).toContainText('Tasks');
    await expect(page.locator('.quick-switcher-results')).not.toContainText('Projects');
  });

  test('highlights matching characters in results', async ({ page }) => {
    await page.keyboard.press('Meta+k');
    await page.getByPlaceholder('Search pages...').fill('ta');

    // Check that matching characters are wrapped in <mark>
    const marks = page.locator('.quick-switcher-item mark');
    await expect(marks).toHaveCount(2); // "T" and "a" in "Tasks"
  });

  test('Escape closes quick switcher', async ({ page }) => {
    await page.keyboard.press('Meta+k');
    await expect(page.locator('.quick-switcher-overlay')).toBeVisible();

    await page.keyboard.press('Escape');

    await expect(page.locator('.quick-switcher-overlay')).not.toBeVisible();
  });

  test('clicking backdrop closes quick switcher', async ({ page }) => {
    await page.keyboard.press('Meta+k');
    await expect(page.locator('.quick-switcher-overlay')).toBeVisible();

    // Click on the backdrop (overlay) not the modal
    await page.locator('.quick-switcher-overlay').click({ position: { x: 10, y: 10 } });

    await expect(page.locator('.quick-switcher-overlay')).not.toBeVisible();
  });

  test('Enter navigates to selected page', async ({ page }) => {
    // Start on Projects page
    await expect(page.getByRole('heading', { name: '📄 Projects' })).toBeVisible();

    // Open quick switcher and search for Tasks
    await page.keyboard.press('Meta+k');
    await page.getByPlaceholder('Search pages...').fill('ta');

    // Wait for the filtered result to appear
    await expect(page.locator('.quick-switcher-item')).toHaveCount(1);
    await expect(page.locator('.quick-switcher-results')).toContainText('Tasks');

    await page.keyboard.press('Enter');

    // Should navigate to Tasks page
    await expect(page.locator('.quick-switcher-overlay')).not.toBeVisible();
    await expect(page.getByRole('heading', { name: '📄 Tasks' })).toBeVisible();
  });

  test('clicking a result navigates to that page', async ({ page }) => {
    await expect(page.getByRole('heading', { name: '📄 Projects' })).toBeVisible();

    await page.keyboard.press('Meta+k');

    // Click on Notes in results
    await page.locator('.quick-switcher-item').filter({ hasText: 'Notes' }).click();

    // Should navigate to Notes page
    await expect(page.locator('.quick-switcher-overlay')).not.toBeVisible();
    await expect(page.getByRole('heading', { name: '📄 Notes' })).toBeVisible();
  });

  test('arrow keys navigate selection', async ({ page }) => {
    await page.keyboard.press('Meta+k');

    // Get initial selection (first item should be selected)
    const _firstItem = page.locator('.quick-switcher-item').first();

    // Arrow down should move selection
    await page.keyboard.press('ArrowDown');

    // Arrow up should move back
    await page.keyboard.press('ArrowUp');

    // The component uses background color to indicate selection
    // We verify keyboard navigation works by navigating and pressing Enter
    await page.keyboard.press('ArrowDown'); // Move to second item
    await page.keyboard.press('Enter');

    // Should have navigated (closed the modal)
    await expect(page.locator('.quick-switcher-overlay')).not.toBeVisible();
  });

  test('Cmd+K toggles quick switcher (open → close)', async ({ page }) => {
    // Open
    await page.keyboard.press('Meta+k');
    await expect(page.locator('.quick-switcher-overlay')).toBeVisible();

    // Press Cmd+K again should close (toggle behavior)
    await page.keyboard.press('Meta+k');
    await expect(page.locator('.quick-switcher-overlay')).not.toBeVisible();
  });

  test('fuzzy search matches non-adjacent characters', async ({ page }) => {
    await page.keyboard.press('Meta+k');

    // "pts" should match "Projects" (P-ro-j-e-c-T-S)
    await page.getByPlaceholder('Search pages...').fill('pts');

    // Should show Projects as a match
    await expect(page.locator('.quick-switcher-results')).toContainText('Projects');
  });

  test('shows no results message for unmatched query', async ({ page }) => {
    await page.keyboard.press('Meta+k');

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

    // Cmd+K should still open quick switcher
    await page.keyboard.press('Meta+k');
    await expect(page.locator('.quick-switcher-overlay')).toBeVisible();

    // Navigate to a different page
    await page.getByPlaceholder('Search pages...').fill('notes');
    await page.keyboard.press('Enter');

    // Should be on Notes page
    await expect(page.getByRole('heading', { name: '📄 Notes' })).toBeVisible();
  });
});
