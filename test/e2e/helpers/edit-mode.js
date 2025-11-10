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

  // Click to focus the block
  await page.click('.block');

  // Type a printable character to enter edit mode (Logseq-style "start typing")
  // According to blocks_ui.cljs:186, typing a printable character while focused enters edit mode
  await page.keyboard.press('a');

  // Wait for contenteditable to appear
  await page.waitForSelector('[contenteditable="true"]', { timeout: 2000 });

  // Clear the 'a' we just typed
  await page.keyboard.press('Backspace');
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
