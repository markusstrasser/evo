/**
 * Helper to enter edit mode on a block
 */

/**
 * Select a page from the sidebar to load blocks.
 * The app requires a page to be selected before blocks appear.
 * @param {import('@playwright/test').Page} page
 * @param {string} [pageName='Projects'] - Name of the page to select
 * @returns {Promise<void>}
 */
export async function selectPage(page, pageName = 'Projects') {
  // Use create-page so the helper works in both demo data and ?test=true mode.
  await page.evaluate((name) => {
    if (window.TEST_HELPERS?.dispatchIntent) {
      window.TEST_HELPERS.dispatchIntent({type: 'create-page', title: name});
    } else {
      console.error('TEST_HELPERS.dispatchIntent not found - is the app loaded?');
    }
  }, pageName);

  // Wait for blocks to appear - Playwright auto-waits (no sleep needed)
  await page.waitForSelector('[data-block-id]', { timeout: 5000 });
}

/**
 * Enter edit mode on the first available block
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<void>}
 */
export async function enterEditMode(page) {
  // Ensure a page is selected and blocks are loaded
  const hasBlocks = await page.locator('[data-block-id]').count();
  if (hasBlocks === 0) {
    await selectPage(page);
  }

  // The two-click system requires state to update between clicks:
  // 1. First click selects the block (sets focus)
  // 2. Second click (when focused) enters edit mode
  // dblclick happens too fast - state doesn't update between clicks
  // Instead, use TEST_HELPERS.dispatchIntent to enter edit mode directly

  // Get the first block's ID and dispatch enter-edit directly
  const blockId = await page.locator('[data-block-id]').first().getAttribute('data-block-id');
  await page.evaluate((id) => {
    if (window.TEST_HELPERS?.dispatchIntent) {
      // First select the block, then enter edit mode
      window.TEST_HELPERS.dispatchIntent({type: 'selection', mode: 'replace', ids: id});
      window.TEST_HELPERS.dispatchIntent({type: 'enter-edit', 'block-id': id});
    }
  }, blockId);

  // Wait for contenteditable to appear
  await page.waitForSelector('[contenteditable="true"]', { timeout: 2000 });
}

/**
 * Enter edit mode and click into the contenteditable element
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<void>}
 */
export async function enterEditModeAndClick(page) {
  await enterEditMode(page);
  await page.click('[contenteditable="true"]');
}
