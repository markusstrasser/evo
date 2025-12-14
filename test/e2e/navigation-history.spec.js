/**
 * Navigation History E2E Tests
 *
 * Tests browser-style back/forward navigation (Cmd+[ / Cmd+]).
 * This is separate from undo/redo (Cmd+Z) which tracks content changes.
 */

import { test, expect } from '@playwright/test';
import { modKey } from './helpers/keyboard.js';

test.describe('Navigation History (Cmd+[/])', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  /**
   * Helper to get current page ID from view state
   */
  async function getCurrentPageId(page) {
    return await page.evaluate(() => {
      // getSession returns clj->js of view state
      // Keys become strings like "current-page" (kebab-case preserved)
      const session = window.TEST_HELPERS?.getSession?.();
      return session?.ui?.['current-page'];
    });
  }

  /**
   * Helper to create a new page and return its ID
   */
  async function createNewPage(page, title) {
    const pageId = `page-${Date.now()}`;
    await page.evaluate(({ id, title }) => {
      window.TEST_HELPERS?.dispatchIntent?.({
        type: 'create-page',
        title: title
      });
    }, { id: pageId, title });
    // Wait for page switch
    await page.waitForTimeout(100);
    return getCurrentPageId(page);
  }

  /**
   * Helper to switch to a page by ID
   */
  async function switchToPage(page, pageId) {
    await page.evaluate((id) => {
      window.TEST_HELPERS?.dispatchIntent?.({
        type: 'switch-page',
        'page-id': id
      });
    }, pageId);
    await page.waitForTimeout(100);
  }

  test('Cmd+[ goes back to previous page after navigating', async ({ page }) => {
    // Get initial page ID
    const page1Id = await getCurrentPageId(page);
    expect(page1Id).toBeTruthy();

    // Create and navigate to a new page
    const page2Id = await createNewPage(page, `Test Page ${Date.now()}`);
    expect(page2Id).toBeTruthy();
    expect(page2Id).not.toBe(page1Id);

    // Verify we're on page 2
    expect(await getCurrentPageId(page)).toBe(page2Id);

    // Press Cmd+[ to go back
    await page.keyboard.press(`${modKey}+[`);
    await page.waitForTimeout(200);

    // Should be back on page 1
    const currentPage = await getCurrentPageId(page);
    expect(currentPage).toBe(page1Id);
  });

  test('Cmd+] goes forward after going back', async ({ page }) => {
    const page1Id = await getCurrentPageId(page);

    // Create page 2
    const page2Id = await createNewPage(page, `Test Page ${Date.now()}`);
    expect(page2Id).not.toBe(page1Id);

    // Go back to page 1
    await page.keyboard.press(`${modKey}+[`);
    await page.waitForTimeout(200);
    expect(await getCurrentPageId(page)).toBe(page1Id);

    // Go forward to page 2
    await page.keyboard.press(`${modKey}+]`);
    await page.waitForTimeout(200);
    expect(await getCurrentPageId(page)).toBe(page2Id);
  });

  test('Cmd+[ at start of history is no-op', async ({ page }) => {
    const currentPageId = await getCurrentPageId(page);

    // Press back multiple times - should stay on same page
    await page.keyboard.press(`${modKey}+[`);
    await page.keyboard.press(`${modKey}+[`);
    await page.keyboard.press(`${modKey}+[`);
    await page.waitForTimeout(100);

    expect(await getCurrentPageId(page)).toBe(currentPageId);
  });

  test('Cmd+] at end of history is no-op', async ({ page }) => {
    const currentPageId = await getCurrentPageId(page);

    // Press forward - should do nothing (no forward history)
    await page.keyboard.press(`${modKey}+]`);
    await page.keyboard.press(`${modKey}+]`);
    await page.waitForTimeout(100);

    expect(await getCurrentPageId(page)).toBe(currentPageId);
  });

  test('navigating to new page truncates forward history', async ({ page }) => {
    const page1Id = await getCurrentPageId(page);

    // Create page 2
    const page2Id = await createNewPage(page, `Page Two ${Date.now()}`);

    // Create page 3
    const page3Id = await createNewPage(page, `Page Three ${Date.now()}`);
    expect(page3Id).not.toBe(page2Id);

    // Go back to page 2
    await page.keyboard.press(`${modKey}+[`);
    await page.waitForTimeout(200);
    expect(await getCurrentPageId(page)).toBe(page2Id);

    // Now create page 4 (this should truncate forward history to page 3)
    const page4Id = await createNewPage(page, `Page Four ${Date.now()}`);
    expect(page4Id).not.toBe(page3Id);

    // Forward should do nothing (page 3 is gone from history)
    await page.keyboard.press(`${modKey}+]`);
    await page.waitForTimeout(100);
    expect(await getCurrentPageId(page)).toBe(page4Id);
  });

  test('switching pages adds to history', async ({ page }) => {
    const page1Id = await getCurrentPageId(page);

    // Create page 2
    const page2Id = await createNewPage(page, `Test Page ${Date.now()}`);

    // Switch back to page 1 using intent (not keyboard)
    await switchToPage(page, page1Id);
    expect(await getCurrentPageId(page)).toBe(page1Id);

    // Now we should be able to go back to page 2
    await page.keyboard.press(`${modKey}+[`);
    await page.waitForTimeout(200);
    expect(await getCurrentPageId(page)).toBe(page2Id);
  });

  test('history supports multiple back/forward jumps', async ({ page }) => {
    const page1Id = await getCurrentPageId(page);

    // Create 3 more pages
    const page2Id = await createNewPage(page, `Page 2 ${Date.now()}`);
    const page3Id = await createNewPage(page, `Page 3 ${Date.now()}`);
    const page4Id = await createNewPage(page, `Page 4 ${Date.now()}`);

    // Currently on page 4
    expect(await getCurrentPageId(page)).toBe(page4Id);

    // Go back 3 times to page 1
    await page.keyboard.press(`${modKey}+[`);
    await page.waitForTimeout(100);
    expect(await getCurrentPageId(page)).toBe(page3Id);

    await page.keyboard.press(`${modKey}+[`);
    await page.waitForTimeout(100);
    expect(await getCurrentPageId(page)).toBe(page2Id);

    await page.keyboard.press(`${modKey}+[`);
    await page.waitForTimeout(100);
    expect(await getCurrentPageId(page)).toBe(page1Id);

    // Go forward 2 times to page 3
    await page.keyboard.press(`${modKey}+]`);
    await page.waitForTimeout(100);
    expect(await getCurrentPageId(page)).toBe(page2Id);

    await page.keyboard.press(`${modKey}+]`);
    await page.waitForTimeout(100);
    expect(await getCurrentPageId(page)).toBe(page3Id);
  });
});
