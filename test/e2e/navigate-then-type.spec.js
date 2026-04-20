// @ts-check
import { test, expect } from '@playwright/test';
import {
  waitForBlocks,
  getBlockText,
  getSelectionState,
} from './helpers/index.js';

/**
 * Navigate-then-type regression
 *
 * Claim: after any page navigation (switch-page, navigate-to-page,
 * create-page), the first block on the destination page is focused,
 * so pressing a printable key immediately lands that character in
 * that block via the global-keyboard `:enter-edit-with-char` path.
 *
 * History — this has regressed twice:
 *   - 74cb2c80 (Dec 5) first fixed it by setting focus on navigate.
 *   - e8630707 (Mar 24) then cleared selection on page/view switch to
 *     fix "ghost selection from previous page" and dropped the focus
 *     seed, silently un-fixing typing. The two goals (clear *stale*
 *     selection; seed *new* focus) are independent and must both hold.
 *
 * These tests assert the claim at the claim level (per
 * `.claude/rules/commit-claim-testing.md`): dispatch each navigation
 * intent shape, then press one printable key, then read the destination
 * block's text out of the DB. No clicking between navigate and keypress.
 */

// `clj->js` on the view-state emits `name`-form keys (`"current-page"`,
// `"editing-block-id"`), which JS can only reach via the bracket form
// when the name contains a dash. Helpers normalize both styles.
async function getCurrentPageId(page) {
  return page.evaluate(() => {
    const s = window.TEST_HELPERS.getSession();
    return s?.ui?.['current-page'] ?? s?.ui?.current_page ?? null;
  });
}

async function getFirstChildOfPage(page, pageTitle) {
  return page.evaluate((title) => {
    const db = window.TEST_HELPERS.getDb();
    const nodes = db.nodes || {};
    for (const [id, node] of Object.entries(nodes)) {
      if (node?.type === 'page' && node?.props?.title === title) {
        const children = db['children-by-parent']?.[id] ?? db.children_by_parent?.[id] ?? [];
        return { pageId: id, firstBlockId: children[0] ?? null };
      }
    }
    return null;
  }, pageTitle);
}

test.describe('Navigate then type — focus seeds on page switch', { tag: '@smoke' }, () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await waitForBlocks(page);
  });

  test('create-page: first block accepts immediate typing', async ({ page }) => {
    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Scratch' });
    });

    await page.waitForFunction(() => {
      const s = window.TEST_HELPERS?.getSession();
      return s?.selection?.focus != null;
    });

    const sel = await getSelectionState(page);
    expect(sel.focus).toBeTruthy();

    await page.keyboard.press('x');

    await expect.poll(() => getBlockText(page, sel.focus)).toBe('x');
  });

  test('navigate-to-page (existing): first block accepts typing', async ({ page }) => {
    // Seed two pages so "Existing" is not the current page at the start
    // of the navigation under test.
    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Existing' });
    });
    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'OtherPage' });
    });
    await page.waitForFunction((title) => {
      const s = window.TEST_HELPERS?.getSession();
      const pageId = s?.ui?.['current-page'] ?? s?.ui?.current_page;
      const db = window.TEST_HELPERS.getDb();
      return db.nodes?.[pageId]?.props?.title === title;
    }, 'OtherPage');

    // This is the exact branch e8630707 regressed: navigate to a page
    // that already exists, via its name.
    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({ type: 'navigate-to-page', 'page-name': 'Existing' });
    });
    await page.waitForFunction(() => {
      const s = window.TEST_HELPERS?.getSession();
      return s?.selection?.focus != null;
    });

    const sel = await getSelectionState(page);
    const expected = await getFirstChildOfPage(page, 'Existing');
    expect(sel.focus).toBe(expected.firstBlockId);

    await page.keyboard.press('y');
    await expect.poll(() => getBlockText(page, sel.focus)).toBe('y');
  });

  test('switch-page (by id): first block accepts typing', async ({ page }) => {
    // Sidebar / quick-switcher path dispatches :switch-page with a
    // concrete page id.
    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Target' });
    });
    const target = await getFirstChildOfPage(page, 'Target');
    expect(target?.pageId).toBeTruthy();

    // Navigate elsewhere so switch-page actually moves the current page.
    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'AwayPage' });
    });
    await page.waitForFunction((id) =>
      (window.TEST_HELPERS?.getSession()?.ui?.['current-page']
       ?? window.TEST_HELPERS?.getSession()?.ui?.current_page) !== id,
      target.pageId);

    await page.evaluate((id) => {
      window.TEST_HELPERS.dispatchIntent({ type: 'switch-page', 'page-id': id });
    }, target.pageId);

    await page.waitForFunction((id) => {
      const s = window.TEST_HELPERS?.getSession();
      const current = s?.ui?.['current-page'] ?? s?.ui?.current_page;
      return current === id && s?.selection?.focus != null;
    }, target.pageId);

    const sel = await getSelectionState(page);
    expect(sel.focus).toBe(target.firstBlockId);

    await page.keyboard.press('z');
    await expect.poll(() => getBlockText(page, sel.focus)).toBe('z');
  });
});
