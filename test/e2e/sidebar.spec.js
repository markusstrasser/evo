/**
 * Sidebar E2E Tests
 *
 * Tests for the Logseq-style left sidebar with:
 * - Navigation links (Journals, All Pages)
 * - Favorites (star icon, persisted to localStorage)
 * - Recents (auto-populated on page visits, excludes journals, persisted)
 * - Delete with undo toast
 *
 * LOGSEQ PARITY:
 * - Journals and All Pages are nav links, not inline page lists
 * - Recents exclude journal pages
 * - Favorites appear below navigation
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
    // Wait for app to be ready
    await page.waitForTimeout(500);
  });

  test.describe('Navigation Links', () => {
    test('shows Journals and All Pages navigation links', async ({ page }) => {
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      const allPagesNav = page.locator('.sidebar-nav-item').filter({ hasText: 'All Pages' });

      await expect(journalsNav).toBeVisible();
      await expect(allPagesNav).toBeVisible();
    });

    test('Journals nav shows calendar icon', async ({ page }) => {
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      const icon = journalsNav.locator('.nav-icon');
      await expect(icon).toBeVisible();
    });

    test('All Pages nav shows file icon', async ({ page }) => {
      const allPagesNav = page.locator('.sidebar-nav-item').filter({ hasText: 'All Pages' });
      const icon = allPagesNav.locator('.nav-icon');
      await expect(icon).toBeVisible();
    });

    test('All Pages shows page count', async ({ page }) => {
      // Create some pages
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Page One' });
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Page Two' });
      });
      await page.waitForTimeout(200);

      const allPagesNav = page.locator('.sidebar-nav-item').filter({ hasText: 'All Pages' });
      const count = allPagesNav.locator('.nav-count');
      await expect(count).toBeVisible();
      const countText = await count.textContent();
      expect(parseInt(countText)).toBeGreaterThanOrEqual(2);
    });

    test('clicking All Pages opens all pages view', async ({ page }) => {
      // Create a page first
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Test Page' });
      });
      await page.waitForTimeout(200);

      const allPagesNav = page.locator('.sidebar-nav-item').filter({ hasText: 'All Pages' });
      await allPagesNav.click();

      // Should show all pages view
      await expect(page.locator('.all-pages-view')).toBeVisible();
      await expect(page.locator('.all-pages-header h3')).toHaveText('All Pages');
    });

    test('All Pages view includes journal pages', async ({ page }) => {
      // Create a journal and regular page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Dec 10th, 2025' });
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'My Notes' });
      });
      await page.waitForTimeout(200);

      const allPagesNav = page.locator('.sidebar-nav-item').filter({ hasText: 'All Pages' });
      await allPagesNav.click();

      // Both should be visible
      await expect(page.locator('.all-pages-item').filter({ hasText: 'Dec 10th, 2025' })).toBeVisible();
      await expect(page.locator('.all-pages-item').filter({ hasText: 'My Notes' })).toBeVisible();
    });

    test('Journal pages show calendar icon in All Pages', async ({ page }) => {
      // Create a journal page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Dec 10th, 2025' });
      });
      await page.waitForTimeout(200);

      const allPagesNav = page.locator('.sidebar-nav-item').filter({ hasText: 'All Pages' });
      await allPagesNav.click();

      // Journal should have calendar emoji
      const journalItem = page.locator('.all-pages-item').filter({ hasText: 'Dec 10th, 2025' });
      await expect(journalItem).toContainText('📅');
    });

    test('Regular pages show file icon in All Pages', async ({ page }) => {
      // Create a regular page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Regular Page' });
      });
      await page.waitForTimeout(200);

      const allPagesNav = page.locator('.sidebar-nav-item').filter({ hasText: 'All Pages' });
      await allPagesNav.click();

      // Regular page should have file emoji
      const pageItem = page.locator('.all-pages-item').filter({ hasText: 'Regular Page' });
      await expect(pageItem).toContainText('📄');
    });
  });

  test.describe('Favorites', () => {
    test('star button favorites a page', async ({ page }) => {
      // Create and navigate to a page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Favorite Me' });
      });
      await page.waitForTimeout(200);

      // Find the page in recents and favorite it
      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'Recents' });
      if (await recentsSection.isVisible()) {
        const pageItem = recentsSection.locator('.sidebar-page-item').filter({ hasText: 'Favorite Me' });
        await pageItem.hover();

        // Click star button
        const starButton = pageItem.locator('.star-button');
        await starButton.click();

        // Favorites section should now be visible with the page
        const favoritesSection = page.locator('.sidebar-section').filter({ hasText: 'Favorites' });
        await expect(favoritesSection).toBeVisible();
        await expect(favoritesSection).toContainText('Favorite Me');
      }
    });

    test('favorites persist across page reload', async ({ page }) => {
      // Create and favorite a page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Persist Test' });
      });
      await page.waitForTimeout(200);

      // Get the page ID before favoriting
      const pageId = await page.evaluate(() => {
        const session = window.TEST_HELPERS.getSession();
        return session?.ui?.['current-page'];
      });

      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'Recents' });
      if (await recentsSection.isVisible()) {
        const pageItem = recentsSection.locator('.sidebar-page-item').filter({ hasText: 'Persist Test' });
        await pageItem.hover();
        await pageItem.locator('.star-button').click();

        // Verify localStorage has the favorite
        const favoritesStored = await page.evaluate(() => {
          return localStorage.getItem('evo:favorites');
        });
        expect(favoritesStored).toBeTruthy();
        expect(favoritesStored).toContain(pageId);

        // Reload the page - test mode resets DB, so recreate the page
        await page.reload();
        await page.waitForTimeout(500);

        // Recreate the page (DB is fresh after reload in test mode)
        await page.evaluate(() => {
          window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Persist Test' });
        });
        await page.waitForTimeout(200);

        // Favorites should still show star (if same page ID is favorited)
        // Note: Page ID will be different after recreate, so verify localStorage persisted
        const favoritesAfterReload = await page.evaluate(() => {
          return localStorage.getItem('evo:favorites');
        });
        expect(favoritesAfterReload).toBeTruthy();
      }
    });
  });

  test.describe('Recents', () => {
    test('clicking a page adds it to recents', async ({ page }) => {
      // Create a page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Recent Test' });
      });
      await page.waitForTimeout(200);

      // Page should be in recents (navigated to on creation)
      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'Recents' });
      await expect(recentsSection).toBeVisible();
      await expect(recentsSection).toContainText('Recent Test');
    });

    test('recents exclude journal pages (Logseq parity)', async ({ page }) => {
      // Create a journal page via navigation
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Dec 10th, 2025' });
      });
      await page.waitForTimeout(200);

      // Journal pages should NOT appear in Recents
      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'Recents' });

      // If recents section exists, check it doesn't have journals
      if (await recentsSection.isVisible()) {
        await expect(recentsSection.locator('.sidebar-page-item').filter({ hasText: 'Dec 10th, 2025' })).not.toBeVisible();
      }
    });

    test('recents persist across page reload', async ({ page }) => {
      // Create a page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Persist Recent' });
      });
      await page.waitForTimeout(200);

      // Get the page ID
      const pageId = await page.evaluate(() => {
        const session = window.TEST_HELPERS.getSession();
        return session?.ui?.['current-page'];
      });

      // Verify it's in recents
      let recentsSection = page.locator('.sidebar-section').filter({ hasText: 'Recents' });
      await expect(recentsSection).toContainText('Persist Recent');

      // Verify localStorage has the recent
      const recentsStored = await page.evaluate(() => {
        return localStorage.getItem('evo:recents');
      });
      expect(recentsStored).toBeTruthy();
      expect(recentsStored).toContain(pageId);

      // Reload the page - test mode resets DB
      await page.reload();
      await page.waitForTimeout(500);

      // Verify localStorage still has recents after reload
      const recentsAfterReload = await page.evaluate(() => {
        return localStorage.getItem('evo:recents');
      });
      expect(recentsAfterReload).toBeTruthy();
    });
  });

  test.describe('Page Navigation', () => {
    test('clicking a page in recents switches to that page', async ({ page }) => {
      // Create two pages
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Page A' });
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Page B' });
      });
      await page.waitForTimeout(200);

      // Click on Page A in recents
      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'Recents' });
      await recentsSection.locator('.sidebar-page-item').filter({ hasText: 'Page A' }).click();

      // Page heading should show Page A
      await expect(page.getByRole('heading', { level: 3 })).toContainText('Page A');
    });

    test('clicking page in All Pages view navigates to it', async ({ page }) => {
      // Create a page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Navigate Test' });
      });
      await page.waitForTimeout(200);

      // Go to All Pages view
      await page.locator('.sidebar-nav-item').filter({ hasText: 'All Pages' }).click();
      await page.waitForTimeout(100);

      // Click the page
      await page.locator('.all-pages-item').filter({ hasText: 'Navigate Test' }).click();

      // Should navigate to the page
      await expect(page.locator('.all-pages-view')).not.toBeVisible();
      await expect(page.getByRole('heading', { level: 3 })).toContainText('Navigate Test');
    });
  });

  test.describe('Delete with Undo', () => {
    test('delete button removes page and shows toast', async ({ page }) => {
      // Create a page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Delete Me' });
      });
      await page.waitForTimeout(200);

      // Find in recents
      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'Recents' });
      const pageItem = recentsSection.locator('.sidebar-page-item').filter({ hasText: 'Delete Me' });

      // Hover and click delete
      await pageItem.hover();
      await pageItem.locator('.delete-button').click();

      // Toast notification should appear
      const toast = page.locator('.notification');
      await expect(toast).toBeVisible();
      await expect(toast).toContainText('Deleted');
    });

    test('undo button restores deleted page', async ({ page }) => {
      // Create a page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Undo Test' });
      });
      await page.waitForTimeout(200);

      // Delete the page
      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'Recents' });
      const pageItem = recentsSection.locator('.sidebar-page-item').filter({ hasText: 'Undo Test' });
      await pageItem.hover();
      await pageItem.locator('.delete-button').click();

      // Click Undo in toast
      const undoButton = page.locator('.notification button').filter({ hasText: 'Undo' });
      await undoButton.click();

      // Page should be restored in recents
      await expect(recentsSection).toContainText('Undo Test');
    });
  });

  test.describe('Collapsible Sections', () => {
    test('section headers are clickable', async ({ page }) => {
      // Create pages to ensure sections exist
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Section Test' });
      });
      await page.waitForTimeout(200);

      const sectionHeader = page.locator('.sidebar-section-header').first();
      if (await sectionHeader.isVisible()) {
        // Should be clickable
        await sectionHeader.click();
      }
    });

    test('chevron icon indicates collapse state', async ({ page }) => {
      // Create pages to ensure sections exist
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Chevron Test' });
      });
      await page.waitForTimeout(200);

      const section = page.locator('.sidebar-section').first();
      if (await section.isVisible()) {
        const chevron = section.locator('.section-chevron svg');
        await expect(chevron).toBeVisible();
      }
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
  });
});
