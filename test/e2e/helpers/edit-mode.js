/**
 * Helper to enter edit mode on a block
 */

/**
 * Enter edit mode on the first available block
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<void>}
 */
export async function enterEditMode(page) {
  // Wait for blocks to load
  await page.waitForSelector('.block', { timeout: 5000 });

  // IMPORTANT: Two-click system (see src/components/block.cljs:751-756)
  // First click selects the block, second click enters edit mode
  // Using dblclick instead of two separate clicks
  await page.dblclick('.block');

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
