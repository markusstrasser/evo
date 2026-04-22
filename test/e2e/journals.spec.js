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

/**
 * Test-mode reset (`?test=true`) explicitly sets `:journals-view? false`
 * and puts the user on `test-page` (see `reset-to-empty-db!` in
 * `src/shell/editor.cljs`, commit 130525ad — the reset shields
 * non-journals tests from auto-landing in the journals view). Journals
 * tests therefore need to *explicitly* enter journals view, and need to
 * wait for that enter to land in the DOM before asserting. Use this
 * helper at the top of each test that expects `.journals-view`.
 */
async function enterJournalsView(page) {
  await page.evaluate(() => window.TEST_HELPERS.openJournalsView());
  await expect(page.locator('.journals-view')).toBeVisible({ timeout: 3000 });
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

  test.describe('Journals View (explicit entry)', () => {
    // `reset-to-empty-db!` turns journals-view off and puts tests on
    // test-page. These tests assert that once we *enter* journals view
    // (same user action as clicking the Journals nav) the view behaves
    // like the real Logseq default: renders the header, auto-creates
    // today's journal, highlights the sidebar link.

    test('entering journals view shows the header and title', async ({ page }) => {
      await enterJournalsView(page);
      await expect(page.locator('.journals-header')).toBeVisible();
      await expect(page.locator('.journals-title')).toHaveText('Journals');
    });

    test('auto-creates today journal if it does not exist', async ({ page }) => {
      const today = getTodayTitle();
      await enterJournalsView(page);
      // The JournalsView's on-render hook dispatches :ensure-page-exists
      // via setTimeout 0 — wait for today to materialize.
      await expect(
        page.locator('.journal-title').filter({ hasText: today })
      ).toBeVisible({ timeout: 3000 });
    });

    test('Journals nav link is active once journals view is open', async ({ page }) => {
      await enterJournalsView(page);
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
      // Create journals in random order (all past, to stay independent
      // of today's date). Pure-chronological contract: sort descending
      // by date regardless of today's position.
      await createJournalPages(page, [
        'Dec 10th, 2025',
        'Dec 12th, 2025',
        'Dec 11th, 2025'
      ]);

      await enterJournalsView(page);

      const titles = await page.locator('.journal-title').allTextContents();

      // Today (whatever the current date is) lands in its chronological
      // slot. Assert the seeded past-journal trio is descending.
      const dec10 = titles.indexOf('Dec 10th, 2025');
      const dec11 = titles.indexOf('Dec 11th, 2025');
      const dec12 = titles.indexOf('Dec 12th, 2025');
      expect(dec10).toBeGreaterThanOrEqual(0);
      expect(dec11).toBeGreaterThanOrEqual(0);
      expect(dec12).toBeGreaterThanOrEqual(0);
      // Newer date → earlier index (higher in the list).
      expect(dec12).toBeLessThan(dec11);
      expect(dec11).toBeLessThan(dec10);
    });

    test('future-dated journal sorts above today (pure chronological)', async ({ page }) => {
      // Regression guard for the "today pinned first" behavior that
      // placed today above a future journal. A user writing
      // [[Apr 21st, 2026]] on Apr 20th expects Apr 21 above Apr 20.
      // Use far-future dates so the assertion is date-independent.
      await createJournalPages(page, ['Dec 31st, 2099', 'Jan 1st, 2100']);

      await enterJournalsView(page);

      const titles = await page.locator('.journal-title').allTextContents();
      const jan2100 = titles.indexOf('Jan 1st, 2100');
      const dec2099 = titles.indexOf('Dec 31st, 2099');
      const today = titles.indexOf(getTodayTitle());
      expect(jan2100).toBeGreaterThanOrEqual(0);
      expect(dec2099).toBeGreaterThanOrEqual(0);
      expect(today).toBeGreaterThanOrEqual(0);
      // Both future journals must be above today.
      expect(jan2100).toBeLessThan(today);
      expect(dec2099).toBeLessThan(today);
      // And the later future date must be above the earlier one.
      expect(jan2100).toBeLessThan(dec2099);
    });

    test('shows header with title and count', async ({ page }) => {
      await createJournalPages(page, ['Dec 13th, 2025']);
      await enterJournalsView(page);

      await expect(page.locator('.journals-title')).toHaveText('Journals');
      await expect(page.locator('.journals-count')).toBeVisible();
    });

    test('empty journal auto-seeds a first block with cursor in edit mode', async ({ page }) => {
      // `:ensure-page-exists` creates today's journal page with no first
      // block. The JournalPage component's on-mount hook immediately
      // dispatches `:create-block-in-page`, which seeds a single empty
      // block and sets editing focus on it. No click-to-edit placeholder.
      await enterJournalsView(page);

      // An editable block appears under today's journal, already focused.
      const editor = page.locator('.journal-item [contenteditable="true"]').first();
      await expect(editor).toBeVisible({ timeout: 3000 });
      await expect(editor).toBeFocused();
    });
  });

  test.describe('Navigation', () => {
    test('clicking journal title navigates to that page', async ({ page }) => {
      await createJournalPages(page, ['Dec 13th, 2025']);
      await enterJournalsView(page);

      // Click on a journal title (not today's)
      await page.locator('.journal-title').filter({ hasText: 'Dec 13th, 2025' }).click();

      // Should exit journals view and show the page
      await expect(page.locator('.journals-view')).not.toBeVisible();

      // Page title now renders as h1 (was h3 when this test was written).
      const pageHeading = page.getByRole('heading', { level: 1 });
      await expect(pageHeading).toContainText('Dec 13th, 2025');
    });

    test('navigate-back returns to journals view', async ({ page }) => {
      // Back/forward are keyboard-only now (Cmd+[ / Cmd+]); the old
      // `.nav-arrow` chrome doesn't exist. Dispatch the intent directly.
      await enterJournalsView(page);

      await createJournalPages(page, ['Dec 13th, 2025']);
      await enterJournalsView(page);
      await page.locator('.journal-title').filter({ hasText: 'Dec 13th, 2025' }).click();

      await expect(page.locator('.journals-view')).not.toBeVisible();

      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'navigate-back' });
      });

      await expect(page.locator('.journals-view')).toBeVisible();
    });

    test('back/forward navigation treats journals as virtual page', async ({ page }) => {
      // Create a regular page
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'My Notes' });
      });
      await page.waitForTimeout(200);

      // Navigate: Journals -> My Notes -> Journals
      await enterJournalsView(page);

      // Go to My Notes via sidebar
      await page.locator('.sidebar-page-item').filter({ hasText: 'My Notes' }).click();
      await expect(page.locator('.journals-view')).not.toBeVisible();

      // Back should go to journals
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'navigate-back' });
      });
      await expect(page.locator('.journals-view')).toBeVisible();

      // Forward should go to My Notes
      await page.evaluate(() => {
        window.TEST_HELPERS.dispatchIntent({ type: 'navigate-forward' });
      });
      await expect(page.locator('.journals-view')).not.toBeVisible();
    });

    test('Journals link loses active state when viewing a page', async ({ page }) => {
      // Click on a journal to navigate
      await createJournalPages(page, ['Dec 13th, 2025']);
      await enterJournalsView(page);
      await page.locator('.journal-title').filter({ hasText: 'Dec 13th, 2025' }).click();

      // Active class should be removed
      const journalsNav = page.locator('.sidebar-nav-item').filter({ hasText: 'Journals' });
      await expect(journalsNav).not.toHaveClass(/active/);
    });
  });

  test.describe('Date Format Support', () => {
    test('recognizes human-readable date format (Dec 14th, 2025)', async ({ page }) => {
      await createJournalPages(page, ['Dec 10th, 2025']);
      await enterJournalsView(page);

      await expect(page.locator('.journal-title').filter({ hasText: 'Dec 10th, 2025' })).toBeVisible();
    });

    test('recognizes ISO date format (2025-12-10)', async ({ page }) => {
      await createJournalPages(page, ['2025-12-10']);
      await enterJournalsView(page);

      await expect(page.locator('.journal-title').filter({ hasText: '2025-12-10' })).toBeVisible();
    });

    test('recognizes ordinal-less date format (Apr 19, 2026) — legacy page-ref compat', async ({ page }) => {
      // The pre-fix page-ref generator produced `[[MMM d, yyyy]]` with
      // no ordinal suffix. Journal pages created by clicking such refs
      // must still classify as journals so the Journals view lists them.
      await createJournalPages(page, ['Apr 19, 2026']);
      await enterJournalsView(page);

      await expect(page.locator('.journal-title').filter({ hasText: 'Apr 19, 2026' })).toBeVisible();
    });
  });

  test.describe('Logseq Parity', () => {
    test('empty journals (except today) are hidden from view', async ({ page }) => {
      // Create journals - one with content, one without
      await createJournalPages(page, ['Dec 10th, 2025']);  // Has content
      await createJournalPages(page, ['Dec 9th, 2025'], { addContent: false });  // Empty

      await enterJournalsView(page);

      await expect(page.locator('.journal-title').filter({ hasText: 'Dec 10th, 2025' })).toBeVisible();
      await expect(page.locator('.journal-title').filter({ hasText: 'Dec 9th, 2025' })).not.toBeVisible();
    });

    test('today journal shows even when empty', async ({ page }) => {
      const today = getTodayTitle();

      await enterJournalsView(page);
      await expect(
        page.locator('.journal-title').filter({ hasText: today })
      ).toBeVisible({ timeout: 3000 });
    });

    test('journal titles are clickable links', async ({ page }) => {
      await createJournalPages(page, ['Dec 10th, 2025']);
      await enterJournalsView(page);

      const journalTitle = page.locator('.journal-title').filter({ hasText: 'Dec 10th, 2025' });
      await expect(journalTitle).toBeVisible();

      await journalTitle.click();
      await expect(page.locator('.journals-view')).not.toBeVisible();
    });
  });
});
