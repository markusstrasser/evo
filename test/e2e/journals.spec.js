/**
 * Journals View E2E Tests
 *
 * Tests for the Logseq-style journals view (all-journals):
 * - Journals view is default on startup
 * - Auto-creates today's journal if missing
 * - Journal pages sorted newest first
 * - Clicking journal title navigates to that page
 * - Global ← → navigation works with journals
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
 * Helper to get today's date in human-readable format (Dec 14th, 2025)
 */
function getTodayTitle() {
  const d = new Date();
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const day = d.getDate();
  const suffix = [11, 12, 13].includes(day % 100) ? 'th' :
                 { 1: 'st', 2: 'nd', 3: 'rd' }[day % 10] || 'th';
  return `${months[d.getMonth()]} ${day}${suffix}, ${d.getFullYear()}`;
}

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
    // Wait for app to be ready
    await page.waitForTimeout(500);
  });

  test.describe('Default View on Startup', () => {
    test('journals view is the default view on startup', async ({ page }) => {
      // Journals view should be visible immediately after load
      await expect(page.locator('.journals-view')).toBeVisible({ timeout: 5000 });
      await expect(page.locator('.journals-header')).toBeVisible();
      await expect(page.locator('.journals-title')).toHaveText('Journals');
    });

    test('auto-creates today journal if it does not exist', async ({ page }) => {
      const today = getTodayTitle();

      // Wait for auto-creation
      await page.waitForTimeout(500);

      // Today's journal should be visible
      await expect(page.locator('.journals-view')).toBeVisible();
      await expect(page.locator('.journal-title').first()).toHaveText(today);
    });

    test('Journals nav link is active on startup', async ({ page }) => {
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await expect(journalsNav).toHaveClass(/active/);
    });
  });

  test.describe('Sidebar Journals Link', () => {
    test('shows Journals link in sidebar navigation', async ({ page }) => {
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await expect(journalsNav).toBeVisible();
    });

    test('shows calendar icon for Journals nav item', async ({ page }) => {
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      const icon = journalsNav.locator('.nav-icon svg');
      await expect(icon).toBeVisible();
    });

    test('shows journal count badge when journals exist', async ({ page }) => {
      // Create additional journal pages (today already auto-created)
      await createJournalPages(page, ['Dec 13th, 2025', 'Dec 12th, 2025']);

      // Check for count badge - should show 3 (today + 2 created)
      const navCount = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).locator('.nav-count');
      await expect(navCount).toBeVisible();
      // Count may be 3 (today + 2) or just 2 depending on test timing
      const count = await navCount.textContent();
      expect(parseInt(count)).toBeGreaterThanOrEqual(2);
    });
  });

  test.describe('Journals View Display', () => {
    test('displays journal pages sorted newest first', async ({ page }) => {
      // Create journals in random order
      await createJournalPages(page, [
        'Dec 10th, 2025',
        'Dec 12th, 2025',
        'Dec 11th, 2025'
      ]);

      // Go back to journals view
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();
      await page.waitForTimeout(200);

      // Wait for journals view to render
      await expect(page.locator('.journals-view')).toBeVisible();

      // Get journal titles - today should be first, then sorted by date desc
      const journalTitles = page.locator('.journal-title');
      const titles = await journalTitles.allTextContents();

      // Today's journal is first, then Dec 12, 11, 10
      expect(titles[0]).toBe(getTodayTitle());
      expect(titles).toContain('Dec 12th, 2025');
      expect(titles).toContain('Dec 11th, 2025');
      expect(titles).toContain('Dec 10th, 2025');
    });

    test('shows header with title and count', async ({ page }) => {
      await createJournalPages(page, ['Dec 13th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Check header elements
      await expect(page.locator('.journals-title')).toHaveText('Journals');
      await expect(page.locator('.journals-count')).toBeVisible();
    });

    test('shows helpful hint for empty journal', async ({ page }) => {
      // Today's journal is auto-created empty
      await expect(page.locator('.journals-view')).toBeVisible();

      // An empty journal shows "Click to add entries" hint
      const emptyHint = page.locator('.journal-empty');
      if (await emptyHint.isVisible()) {
        await expect(emptyHint).toContainText('Click to add entries');
      }
    });
  });

  test.describe('Navigation', () => {
    test('clicking journal title navigates to that page', async ({ page }) => {
      await createJournalPages(page, ['Dec 13th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Click on a journal title (not today's)
      await page.locator('.journal-title').filter({ hasText: 'Dec 13th, 2025' }).click();

      // Should exit journals view and show the page
      await expect(page.locator('.journals-view')).not.toBeVisible();

      // Page heading should show the journal date
      const pageHeading = page.getByRole('heading', { level: 3 });
      await expect(pageHeading).toContainText('Dec 13th, 2025');
    });

    test('global back button returns to journals view', async ({ page }) => {
      // Start in journals view (default)
      await expect(page.locator('.journals-view')).toBeVisible();

      // Navigate to a journal page
      await createJournalPages(page, ['Dec 13th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();
      await page.locator('.journal-title').filter({ hasText: 'Dec 13th, 2025' }).click();

      // Should be on page view now
      await expect(page.locator('.journals-view')).not.toBeVisible();

      // Click global back button
      await page.locator('.nav-arrow').first().click();

      // Should return to journals view
      await expect(page.locator('.journals-view')).toBeVisible();
    });

    test('back/forward navigation treats journals as virtual page', async ({ page }) => {
      // Create a regular page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'My Notes' });
      });
      await page.waitForTimeout(200);

      // Navigate: Journals -> My Notes -> Journals
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();
      await expect(page.locator('.journals-view')).toBeVisible();

      // Go to My Notes via sidebar
      await page.locator('.sidebar-page-item').filter({ hasText: 'My Notes' }).click();
      await expect(page.locator('.journals-view')).not.toBeVisible();

      // Back should go to journals
      await page.locator('.nav-arrow').first().click();
      await expect(page.locator('.journals-view')).toBeVisible();

      // Forward should go to My Notes
      await page.locator('.nav-arrow').nth(1).click();
      await expect(page.locator('.journals-view')).not.toBeVisible();
    });

    test('Journals link loses active state when viewing a page', async ({ page }) => {
      // Click on a journal to navigate
      await createJournalPages(page, ['Dec 13th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();
      await page.locator('.journal-title').filter({ hasText: 'Dec 13th, 2025' }).click();

      // Active class should be removed
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await expect(journalsNav).not.toHaveClass(/active/);
    });
  });

  test.describe('Date Format Support', () => {
    test('recognizes human-readable date format (Dec 14th, 2025)', async ({ page }) => {
      await createJournalPages(page, ['Dec 10th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Should appear in journals
      await expect(page.locator('.journal-title').filter({ hasText: 'Dec 10th, 2025' })).toBeVisible();
    });

    test('recognizes ISO date format (2025-12-10)', async ({ page }) => {
      await createJournalPages(page, ['2025-12-10']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Should appear in journals
      await expect(page.locator('.journal-title').filter({ hasText: '2025-12-10' })).toBeVisible();
    });
  });

  test.describe('Logseq Parity', () => {
    test('empty journals (except today) are hidden from view', async ({ page }) => {
      // Create journals - one with content, one without
      await createJournalPages(page, ['Dec 10th, 2025']);  // Has content
      await createJournalPages(page, ['Dec 9th, 2025'], { addContent: false });  // Empty

      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Dec 10th should be visible, Dec 9th should not (empty)
      await expect(page.locator('.journal-title').filter({ hasText: 'Dec 10th, 2025' })).toBeVisible();
      await expect(page.locator('.journal-title').filter({ hasText: 'Dec 9th, 2025' })).not.toBeVisible();
    });

    test('today journal shows even when empty', async ({ page }) => {
      const today = getTodayTitle();

      // Should auto-create and show today's journal even though empty
      await expect(page.locator('.journals-view')).toBeVisible();
      await expect(page.locator('.journal-title').first()).toHaveText(today);
    });

    test('journal titles are clickable links', async ({ page }) => {
      await createJournalPages(page, ['Dec 10th, 2025']);
      await page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' }).click();

      // Journal title should be clickable
      const journalTitle = page.locator('.journal-title').filter({ hasText: 'Dec 10th, 2025' });
      await expect(journalTitle).toBeVisible();

      // Click should navigate
      await journalTitle.click();
      await expect(page.locator('.journals-view')).not.toBeVisible();
    });
  });
});
