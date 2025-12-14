/**
 * Journals View E2E Tests
 *
 * Tests for the Logseq-style journals view (all-journals):
 * - Clicking Journals opens stacked journals view
 * - Journal pages sorted newest first
 * - Clicking journal title navigates to that page
 * - Back button exits journals view
 * - Journal count badge in sidebar
 *
 * LOGSEQ REFERENCE:
 * - Route: /all-journals
 * - Display: Multiple journals stacked on scrollable page
 * - Order: Newest first (uses rseq)
 * - Visual: Borders between journals
 */

import { test, expect } from '@playwright/test';

/**
 * Helper to create journal pages for testing.
 * By default, adds content so they appear in journals view
 * (empty journals are hidden except for today's date).
 */
async function createJournalPages(page, dates, { addContent = true } = {}) {
  for (const date of dates) {
    // Create the page (this navigates to it and focuses the first block)
    await page.evaluate((title) => {
      window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title });
    }, date);
    // Wait for page creation
    await page.waitForTimeout(100);

    // Add content to the first block so the journal shows in the list
    if (addContent) {
      await page.evaluate((date) => {
        // Get the current session to find the new page
        const session = window.TEST_HELPERS.getSession();
        const pageId = session?.ui?.['current-page'];
        if (pageId) {
          const db = window.TEST_HELPERS.getDb();
          // clj->js converts keywords to strings
          const children = db?.['children-by-parent']?.[pageId] || [];
          const firstBlockId = children[0];
          if (firstBlockId) {
            // Use dispatchIntent for proper DB update
            window.TEST_HELPERS.dispatchIntent({
              type: 'update-content',
              'block-id': firstBlockId,
              text: `Entry for ${date}`
            });
          }
        }
      }, date);
      await page.waitForTimeout(100);
    }
  }
  // Wait for UI to fully update
  await page.waitForTimeout(200);
}

test.describe('Journals View', () => {
  test.beforeEach(async ({ page }) => {
    // Clear localStorage and load fresh
    await page.goto('/index.html');
    await page.evaluate(() => {
      localStorage.removeItem('evo:favorites');
      localStorage.removeItem('evo:recents');
    });
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test.describe('Sidebar Journals Link', () => {
    test('shows Journals link in sidebar navigation', async ({ page }) => {
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await expect(journalsNav).toBeVisible();
    });

    test('shows journal count badge when journals exist', async ({ page }) => {
      // Create journal pages
      await createJournalPages(page, ['Dec 14th, 2025', 'Dec 13th, 2025']);

      // Check for count badge
      const navCount = page.locator('.sidebar-nav-item .nav-count');
      await expect(navCount).toBeVisible();
      await expect(navCount).toHaveText('2');
    });

    test('hides journal count badge when no journals exist', async ({ page }) => {
      // Without creating journals, count should not be visible
      const navCount = page.locator('.sidebar-nav-item .nav-count');
      await expect(navCount).not.toBeVisible();
    });

    test('highlights Journals link when journals view is active', async ({ page }) => {
      // Create journals first
      await createJournalPages(page, ['Dec 14th, 2025']);

      // Click Journals to activate
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Should have active class
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await expect(journalsNav).toHaveClass(/active/);
    });
  });

  test.describe('Journals View Display', () => {
    test('clicking Journals opens journals view', async ({ page }) => {
      // Create journals
      await createJournalPages(page, ['Dec 14th, 2025', 'Dec 13th, 2025']);

      // Click Journals
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Journals view should be visible
      await expect(page.locator('.journals-view')).toBeVisible();
      await expect(page.locator('.journals-header')).toBeVisible();
    });

    test('shows header with Back button, title, and count', async ({ page }) => {
      await createJournalPages(page, ['Dec 14th, 2025', 'Dec 13th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Check header elements
      await expect(page.locator('.journals-back')).toBeVisible();
      await expect(page.locator('.journals-back')).toContainText('Back');
      await expect(page.locator('.journals-title')).toHaveText('Journals');
      await expect(page.locator('.journals-count')).toContainText('2 entries');
    });

    test('displays journal pages sorted newest first', async ({ page }) => {
      // Create journals in random order
      await createJournalPages(page, [
        'Dec 12th, 2025',
        'Dec 14th, 2025',
        'Dec 13th, 2025'
      ]);

      // Verify count badge shows 3 before clicking
      const navCount = page.locator('.sidebar-nav-item .nav-count');
      await expect(navCount).toHaveText('3');

      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Wait for journals view to render
      await expect(page.locator('.journals-view')).toBeVisible();
      await expect(page.locator('.journal-title').first()).toBeVisible();

      // Get journal titles in order - should be 3
      const journalTitles = page.locator('.journal-title');
      await expect(journalTitles).toHaveCount(3);

      // Should be sorted newest first
      await expect(journalTitles.nth(0)).toHaveText('Dec 14th, 2025');
      await expect(journalTitles.nth(1)).toHaveText('Dec 13th, 2025');
      await expect(journalTitles.nth(2)).toHaveText('Dec 12th, 2025');
    });

    test('shows helpful hint for journals without content', async ({ page }) => {
      // Get today's date in Logseq format to ensure it shows even when empty
      const today = await page.evaluate(() => {
        const d = new Date();
        const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        const day = d.getDate();
        const suffix = [11, 12, 13].includes(day % 100) ? 'th' :
                       { 1: 'st', 2: 'nd', 3: 'rd' }[day % 10] || 'th';
        return `${months[d.getMonth()]} ${day}${suffix}, ${d.getFullYear()}`;
      });

      // Create today's journal WITHOUT content (only today shows when empty)
      await createJournalPages(page, [today], { addContent: false });
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // A journal without content shows "Click to add entries" hint
      const journalItem = page.locator('.journal-item').first();
      await expect(journalItem.locator('.journal-empty')).toBeVisible();
      await expect(journalItem).toContainText('Click to add entries');
    });

    test('shows empty message when no journal pages exist', async ({ page }) => {
      // Click Journals without any journal pages
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Should show empty state
      await expect(page.locator('.journals-empty')).toBeVisible();
      await expect(page.locator('.journals-empty')).toContainText('No journal pages yet');
    });
  });

  test.describe('Navigation', () => {
    test('clicking journal title navigates to that page', async ({ page }) => {
      await createJournalPages(page, ['Dec 14th, 2025', 'Dec 13th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Click on the first journal title
      await page.locator('.journal-title').first().click();

      // Should exit journals view and show the page
      await expect(page.locator('.journals-view')).not.toBeVisible();

      // Page heading should show the journal date
      const pageHeading = page.getByRole('heading', { level: 3 });
      await expect(pageHeading).toContainText('Dec 14th, 2025');
    });

    test('Back button exits journals view', async ({ page }) => {
      await createJournalPages(page, ['Dec 14th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Verify we're in journals view
      await expect(page.locator('.journals-view')).toBeVisible();

      // Click Back
      await page.locator('.journals-back').click();

      // Should exit journals view
      await expect(page.locator('.journals-view')).not.toBeVisible();
    });

    test('clicking Journals again toggles view off', async ({ page }) => {
      await createJournalPages(page, ['Dec 14th, 2025']);

      // Open journals view
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();
      await expect(page.locator('.journals-view')).toBeVisible();

      // Click again to close
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();
      await expect(page.locator('.journals-view')).not.toBeVisible();
    });

    test('Journals link loses active state when exiting view', async ({ page }) => {
      await createJournalPages(page, ['Dec 14th, 2025']);

      // Open journals view
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await journalsNav.click();
      await expect(journalsNav).toHaveClass(/active/);

      // Click Back to exit
      await page.locator('.journals-back').click();

      // Active class should be removed
      await expect(journalsNav).not.toHaveClass(/active/);
    });
  });

  test.describe('Date Format Support', () => {
    test('recognizes human-readable date format (Dec 14th, 2025)', async ({ page }) => {
      await createJournalPages(page, ['Dec 14th, 2025']);

      // Should appear in journals
      const navCount = page.locator('.sidebar-nav-item .nav-count');
      await expect(navCount).toHaveText('1');
    });

    test('recognizes ISO date format (2025-12-14)', async ({ page }) => {
      await createJournalPages(page, ['2025-12-14']);

      // Should appear in journals
      const navCount = page.locator('.sidebar-nav-item .nav-count');
      await expect(navCount).toHaveText('1');
    });

    test('mixes date formats correctly sorted', async ({ page }) => {
      await createJournalPages(page, [
        '2025-12-12',
        'Dec 14th, 2025',
        '2025-12-13'
      ]);

      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Should all be sorted newest first
      const journalTitles = page.locator('.journal-title');
      await expect(journalTitles.nth(0)).toHaveText('Dec 14th, 2025');
      await expect(journalTitles.nth(1)).toHaveText('2025-12-13');
      await expect(journalTitles.nth(2)).toHaveText('2025-12-12');
    });

    test('regular pages do not appear as journals', async ({ page }) => {
      // Create a mix of pages
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Dec 14th, 2025' });
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'My Notes' });
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Project Ideas' });
      });
      await page.waitForTimeout(100);

      // Only 1 journal should be counted
      const navCount = page.locator('.sidebar-nav-item .nav-count');
      await expect(navCount).toHaveText('1');

      // Regular pages should be in Pages section
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      await expect(pagesSection).toContainText('My Notes');
      await expect(pagesSection).toContainText('Project Ideas');
    });
  });

  test.describe('Logseq Parity', () => {
    test('journals excluded from Recents (Logseq behavior)', async ({ page }) => {
      // Create a journal and a regular page
      await createJournalPages(page, ['Dec 14th, 2025']);
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Regular Page' });
      });
      await page.waitForTimeout(100);

      // Click journal to visit it
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();
      await page.locator('.journal-title').first().click();

      // Click regular page
      const pagesSection = page.locator('.sidebar-section').filter({ hasText: 'PAGES' });
      await pagesSection.locator('.sidebar-page-item').filter({ hasText: 'Regular Page' }).click();

      // Check Recents section - should only have regular page
      const recentsSection = page.locator('.sidebar-section').filter({ hasText: 'RECENTS' });
      if (await recentsSection.isVisible()) {
        await expect(recentsSection).toContainText('Regular Page');
        await expect(recentsSection).not.toContainText('Dec 14th, 2025');
      }
    });

    test('journal titles are clickable links', async ({ page }) => {
      await createJournalPages(page, ['Dec 14th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Journal title should have pointer cursor (implicit via click working)
      const journalTitle = page.locator('.journal-title').first();
      await expect(journalTitle).toBeVisible();

      // Click should work
      await journalTitle.click();
      await expect(page.locator('.journals-view')).not.toBeVisible();
    });
  });
});
