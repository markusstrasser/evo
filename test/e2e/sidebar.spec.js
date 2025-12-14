/**
 * Sidebar E2E Tests
 *
 * Tests for the Logseq-style left sidebar with:
 * - Journals nav link (goes to today's journal)
 * - Favorites (star icon, persisted to localStorage)
 * - Recents (auto-populated on page visits, excludes journals, persisted)
 * - Pages (all regular pages)
 * - Delete with undo toast
 *
 * LOGSEQ PARITY:
 * - Journals is a nav link, not a page list
 * - Recents exclude journal pages
 * - Clicking existing recent doesn't move it
 */

import { test, expect } from '@playwright/test';

test.describe('Sidebar', () => {
  test.beforeEach(async ({ page }) => {
    // Clear localStorage BEFORE loading page to ensure clean state
    await page.goto('/index.html');
    await page.evaluate(() => {
      localStorage.removeItem('evo:favorites');
      localStorage.removeItem('evo:recents');
    });

    // Load with test mode for consistent state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test.describe('Page Display', () => {
    test('shows page sections with counts', async ({ page }) => {
      const sidebar = page.locator('.sidebar');

      // Should have at least the Pages section
      const sectionCount = await sidebar.locator('.sidebar-section-header').count();
      expect(sectionCount).toBeGreaterThanOrEqual(1);

      // Section should have a title and count
      const sectionTitles = sidebar.locator('.section-title');
      await expect(sectionTitles.first()).toBeVisible();
    });

    test('filters out Untitled pages', async ({ page }) => {
      const sidebar = page.locator('.sidebar');

      // Should not display any "Untitled" text as page titles
      const untitledPages = sidebar.locator('.sidebar-page-item').filter({ hasText: /^Untitled$/ });
      await expect(untitledPages).toHaveCount(0);
    });

    test('page items show title without icon (simplified UI)', async ({ page }) => {
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      if (await pagesSection.isVisible()) {
        const pageItem = pagesSection.locator('.sidebar-page-item').first();
        // Icons removed for cleaner UI
        await expect(pageItem.locator('.page-icon')).not.toBeVisible();
        // But title should be visible
        await expect(pageItem.locator('.page-title')).toBeVisible();
      }
    });
  });

  test.describe('Favorites', () => {
    test('star button favorites a page', async ({ page }) => {
      // Find a page in the Pages section
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const firstPage = pagesSection.locator('.sidebar-page-item').first();

      // Hover to reveal star button
      await firstPage.hover();

      // Click star button (Add to favorites)
      const starButton = firstPage.locator('button[title="Add to favorites"]');
      await starButton.click();

      // Favorites section should now be visible with the page
      const favoritesSection = page.locator('.sidebar-section').filter({ hasText: 'FAVORITES' });
      await expect(favoritesSection).toBeVisible();
      await expect(favoritesSection.locator('.section-count')).toContainText('1');
    });

    test('star button unfavorites a page', async ({ page }) => {
      // First, favorite a page
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const firstPage = pagesSection.locator('.sidebar-page-item').first();
      await firstPage.hover();
      await firstPage.locator('button[title="Add to favorites"]').click();

      // Now unfavorite it
      const favoritesSection = page.locator('.sidebar-section').filter({ hasText: 'FAVORITES' });
      const favoritedPage = favoritesSection.locator('.sidebar-page-item').first();
      await favoritedPage.hover();
      await favoritedPage.locator('button[title="Remove from favorites"]').click();

      // Favorites section should be gone (0 favorites)
      await expect(favoritesSection).not.toBeVisible();
    });

    test('favorited pages appear at top of sidebar', async ({ page }) => {
      // Favorite a page
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageToFavorite = pagesSection.locator('.sidebar-page-item').first();
      const pageName = await pageToFavorite.locator('.page-title').textContent();

      await pageToFavorite.hover();
      await pageToFavorite.locator('button[title="Add to favorites"]').click();

      // Check that Favorites is the first section (after storage/nav)
      const firstSection = page.locator('.sidebar-section').first();
      await expect(firstSection.locator('.section-title')).toContainText('Favorites');
      await expect(firstSection).toContainText(pageName);
    });

    test('favorites persist across page reload', async ({ page }) => {
      // Favorite a page
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageToFavorite = pagesSection.locator('.sidebar-page-item').first();
      const pageName = await pageToFavorite.locator('.page-title').textContent();

      await pageToFavorite.hover();
      await pageToFavorite.locator('button[title="Add to favorites"]').click();

      // Verify favorites section exists
      let favoritesSection = page.locator('.sidebar-section').filter({ hasText: 'FAVORITES' });
      await expect(favoritesSection).toContainText(pageName);

      // Reload the page
      await page.reload();
      await page.waitForSelector('[data-block-id]', { timeout: 5000 });

      // Favorites should still contain the page
      favoritesSection = page.locator('.sidebar-section').filter({ hasText: 'FAVORITES' });
      await expect(favoritesSection).toBeVisible();
      await expect(favoritesSection).toContainText(pageName);
    });
  });

  test.describe('Recents', () => {
    test('clicking a page adds it to recents', async ({ page }) => {
      // Click on a page that's not in recents
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageItem = pagesSection.locator('.sidebar-page-item').first();
      const pageName = await pageItem.locator('.page-title').textContent();

      await pageItem.click();

      // Wait for page switch
      await expect(page.getByRole('heading', { name: new RegExp(pageName) })).toBeVisible();

      // Recents section should appear with that page
      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'RECENTS' });
      await expect(recentsSection).toBeVisible();
      await expect(recentsSection).toContainText(pageName);
    });

    test('recents exclude journal pages (Logseq parity)', async ({ page }) => {
      // Click on Journals nav to go to a journal page
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });

      if (await journalsNav.isVisible()) {
        await journalsNav.click();
        await page.waitForTimeout(200);

        // Journal pages should NOT appear in Recents
        const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'RECENTS' });

        // If recents section exists, it should not contain date-formatted entries
        if (await recentsSection.isVisible()) {
          const recentItems = recentsSection.locator('.sidebar-page-item');
          const count = await recentItems.count();

          for (let i = 0; i < count; i++) {
            const title = await recentItems.nth(i).locator('.page-title').textContent();
            // Should not match date patterns like "Dec 14th, 2025"
            expect(title).not.toMatch(/[A-Z][a-z]{2} \d{1,2}(st|nd|rd|th), \d{4}/);
          }
        }
      }
    });

    test('clicking existing recent does not move it (Logseq parity)', async ({ page }) => {
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageItems = pagesSection.locator('.sidebar-page-item');

      if (await pageItems.count() >= 2) {
        // Click first page to add to recents
        await pageItems.nth(0).click();
        await page.waitForTimeout(100);

        // Click second page to add to recents
        await pageItems.nth(1).click();
        await page.waitForTimeout(100);

        // Get recents order
        const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'RECENTS' });
        const firstRecentBefore = await recentsSection.locator('.sidebar-page-item').first().locator('.page-title').textContent();

        // Click the first recent again
        await recentsSection.locator('.sidebar-page-item').first().click();
        await page.waitForTimeout(100);

        // Order should NOT change (Logseq behavior)
        const firstRecentAfter = await recentsSection.locator('.sidebar-page-item').first().locator('.page-title').textContent();
        expect(firstRecentAfter).toBe(firstRecentBefore);
      }
    });

    test('recents persist across page reload', async ({ page }) => {
      // Click on a page to add to recents
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageItem = pagesSection.locator('.sidebar-page-item').first();
      const pageName = await pageItem.locator('.page-title').textContent();

      await pageItem.click();
      await page.waitForTimeout(100);

      // Verify it's in recents
      let recentsSection = page.locator('.sidebar-section').filter({ hasText: 'RECENTS' });
      await expect(recentsSection).toContainText(pageName);

      // Reload the page
      await page.reload();
      await page.waitForSelector('[data-block-id]', { timeout: 5000 });

      // Recents should still contain the page
      recentsSection = page.locator('.sidebar-section').filter({ hasText: 'RECENTS' });
      await expect(recentsSection).toContainText(pageName);
    });
  });

  test.describe('Page Navigation', () => {
    test('clicking a page switches to that page', async ({ page }) => {
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageItem = pagesSection.locator('.sidebar-page-item').first();
      const pageName = await pageItem.locator('.page-title').textContent();

      await pageItem.click();

      // Page heading should show the selected page
      await expect(page.getByRole('heading', { name: new RegExp(pageName) })).toBeVisible();
    });

    test('active page is highlighted in sidebar', async ({ page }) => {
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageItem = pagesSection.locator('.sidebar-page-item').first();
      const pageName = await pageItem.locator('.page-title').textContent();

      await pageItem.click();

      // After clicking, the page moves to Recents - look for active there
      // The active item could be in Favorites, Recents, or Pages section
      const activeItem = page.locator('.sidebar-page-item.active');
      await expect(activeItem).toBeVisible();
      await expect(activeItem.locator('.page-title')).toHaveText(pageName);
    });
  });

  test.describe('Delete with Undo', () => {
    test('delete button removes page and shows toast', async ({ page }) => {
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageItem = pagesSection.locator('.sidebar-page-item').first();
      const pageName = await pageItem.locator('.page-title').textContent();
      const initialCount = await pagesSection.locator('.sidebar-page-item').count();

      // Hover and click delete
      await pageItem.hover();
      await pageItem.locator('button[title="Delete page"]').click();

      // Toast notification should appear
      const toast = page.locator('.notification');
      await expect(toast).toBeVisible();
      await expect(toast).toContainText(`Deleted "${pageName}"`);
      await expect(toast.locator('.notification__action')).toContainText('Undo');

      // Page should be removed from list
      const newCount = await pagesSection.locator('.sidebar-page-item').count();
      expect(newCount).toBe(initialCount - 1);
    });

    test('undo button restores deleted page', async ({ page }) => {
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageItem = pagesSection.locator('.sidebar-page-item').first();
      const pageName = await pageItem.locator('.page-title').textContent();
      const initialCount = await pagesSection.locator('.sidebar-page-item').count();

      // Delete the page
      await pageItem.hover();
      await pageItem.locator('button[title="Delete page"]').click();

      // Click Undo in toast
      const undoButton = page.locator('.notification button').filter({ hasText: 'Undo' });
      await undoButton.click();

      // Page should be restored
      await expect(pagesSection.locator('.sidebar-page-item')).toHaveCount(initialCount);
      await expect(pagesSection).toContainText(pageName);
    });

    test('toast auto-dismisses after timeout', async ({ page }) => {
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      const pageItem = pagesSection.locator('.sidebar-page-item').first();

      // Delete a page
      await pageItem.hover();
      await pageItem.locator('button[title="Delete page"]').click();

      // Toast should be visible
      const toast = page.locator('.notification');
      await expect(toast).toBeVisible();

      // Wait for auto-dismiss (5 seconds + buffer)
      await page.waitForTimeout(6000);

      // Toast should be gone
      await expect(toast).not.toBeVisible();
    });
  });

  test.describe('Collapsible Sections', () => {
    test('section headers are clickable', async ({ page }) => {
      const sectionHeader = page.locator('.sidebar-section-header').first();
      await expect(sectionHeader).toBeVisible();

      // Should have cursor pointer style (implicit via click not throwing)
      await sectionHeader.click();
    });

    test('chevron icon indicates collapse state', async ({ page }) => {
      const section = page.locator('.sidebar-section').first();
      const chevron = section.locator('.section-chevron svg');

      // Should have a chevron icon
      await expect(chevron).toBeVisible();
    });
  });

  test.describe('Journals Navigation (Logseq-style)', () => {
    test('Journals is a nav link, not a page list section', async ({ page }) => {
      // Journals should be a nav link that opens the journals view
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await expect(journalsNav).toBeVisible();

      // There should NOT be a "Journals" collapsible section with page items
      const journalsSection = page.locator('.sidebar-section').filter({ hasText: /^Journals$/ });
      await expect(journalsSection).not.toBeVisible();
    });

    test('clicking Journals opens the journals view', async ({ page }) => {
      // Create a journal page first
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Dec 14th, 2025' });
      });
      await page.waitForTimeout(200);

      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await journalsNav.click();

      // Should show journals view (not navigate to single page)
      await expect(page.locator('.journals-view')).toBeVisible();
      await expect(page.locator('.journals-header')).toBeVisible();
    });
  });

  test.describe('Storage Section', () => {
    test('shows folder connection status', async ({ page }) => {
      const storageSection = page.locator('.sidebar-storage');
      await expect(storageSection).toBeVisible();

      // Should show either connected folder or picker button
      const isConnected = await storageSection.locator('.storage-connected').isVisible();
      const isPicker = await storageSection.locator('.storage-picker').isVisible();
      expect(isConnected || isPicker).toBeTruthy();
    });

    test('connected folder shows name and disconnect button', async ({ page }) => {
      const storageConnected = page.locator('.storage-connected');

      if (await storageConnected.isVisible()) {
        // Should show folder name
        await expect(storageConnected.locator('.storage-name')).toBeVisible();

        // Hover to reveal disconnect button
        await storageConnected.hover();
        await expect(storageConnected.locator('.storage-disconnect')).toBeVisible();
      }
    });
  });
});
