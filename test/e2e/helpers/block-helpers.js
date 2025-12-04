/**
 * Block Helpers for E2E Testing
 *
 * Centralized helpers for interacting with blocks in tests.
 * These helpers bypass unreliable DOM interactions (click, keyboard)
 * and use the TEST_HELPERS API for reliable state manipulation.
 *
 * DESIGN PRINCIPLES:
 * 1. Use dispatchIntent for state changes (reliable, deterministic)
 * 2. Use page.keyboard only for typing text (not navigation/actions)
 * 3. Check selection via inline styles (app uses background-color, not .selected)
 * 4. Target specific elements (div.block, .content-view) to avoid ambiguity
 */

/**
 * Select a block via intent dispatch.
 *
 * Click is unreliable in tests - it doesn't always trigger selection handlers.
 * This helper uses TEST_HELPERS.dispatchIntent for deterministic selection.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} blockId - The block ID to select
 */
export async function selectBlock(page, blockId) {
  await page.evaluate((id) => {
    window.TEST_HELPERS?.dispatchIntent({ type: 'selection', mode: 'replace', ids: id });
  }, blockId);
  await page.waitForTimeout(100);
}

/**
 * Clear block selection.
 *
 * @param {import('@playwright/test').Page} page
 */
export async function clearSelection(page) {
  await page.evaluate(() => {
    window.TEST_HELPERS?.dispatchIntent({ type: 'selection', mode: 'clear' });
  });
  await page.waitForTimeout(100);
}

/**
 * Enter edit mode for a block.
 *
 * IMPORTANT: The state machine requires a block to be selected before entering edit mode.
 * This helper first selects the block, then enters edit mode.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} blockId - The block ID to edit
 * @param {string} [cursorAt='end'] - Cursor position: 'start' or 'end'
 */
export async function enterEditMode(page, blockId, cursorAt = 'end') {
  // First, select the block (state machine requires selection state)
  await page.evaluate((id) => {
    window.TEST_HELPERS?.dispatchIntent({ type: 'selection', mode: 'replace', ids: id });
  }, blockId);
  await page.waitForTimeout(50);

  // Then enter edit mode
  await page.evaluate(({ id, cursor }) => {
    window.TEST_HELPERS?.dispatchIntent({
      type: 'enter-edit',
      'block-id': id,
      'cursor-at': cursor === 'start' ? 'start' : 'end'
    });
  }, { id: blockId, cursor: cursorAt });

  // Wait for the contenteditable to appear (lifecycle hook handles focus)
  // Note: data-block-id is on parent div.block, not on the contenteditable span
  await page.waitForSelector(`div.block[data-block-id="${blockId}"] [contenteditable="true"]`, { timeout: 5000 });
  await page.waitForTimeout(50);
}

/**
 * Exit edit mode.
 *
 * Note: The Escape key now works correctly after the keymap fix,
 * but using dispatchIntent is still more reliable in tests.
 *
 * @param {import('@playwright/test').Page} page
 */
export async function exitEditMode(page) {
  await page.evaluate(() => {
    window.TEST_HELPERS?.dispatchIntent({ type: 'exit-edit' });
  });
  await page.waitForTimeout(100);
}

/**
 * Check if a block is selected.
 *
 * App uses inline background-color styles for selection, not CSS classes.
 * - #e6f2ff (rgb 230,242,255) = selected
 * - #b3d9ff (rgb 179,217,255) = focused
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} blockId
 * @returns {Promise<boolean>}
 */
export async function isBlockSelected(page, blockId) {
  const bgColor = await page.locator(`div.block[data-block-id="${blockId}"]`)
    .evaluate(el => getComputedStyle(el).backgroundColor);
  return bgColor === 'rgb(230, 242, 255)' || bgColor === 'rgb(179, 217, 255)';
}

/**
 * Count how many blocks are currently selected.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<number>}
 */
export async function countSelectedBlocks(page) {
  const blocks = await page.locator('div.block[data-block-id]').all();
  let count = 0;
  for (const block of blocks) {
    const bgColor = await block.evaluate(el => getComputedStyle(el).backgroundColor);
    if (bgColor === 'rgb(230, 242, 255)' || bgColor === 'rgb(179, 217, 255)') {
      count++;
    }
  }
  return count;
}

/**
 * Get the text content of a specific block (not including children).
 *
 * Uses .content-view span which contains only the block's own text.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} blockId
 * @returns {Promise<string>}
 */
export async function getBlockText(page, blockId) {
  return await page.evaluate((id) => {
    const viewSpan = document.querySelector(`[data-block-id="${id}"] .content-view`);
    return viewSpan?.textContent || '';
  }, blockId);
}

/**
 * Get the block ID that is currently being edited.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<string|null>}
 */
export async function getEditingBlockId(page) {
  return await page.evaluate(() => {
    const editable = document.querySelector('[contenteditable="true"]');
    return editable?.closest('[data-block-id]')?.getAttribute('data-block-id') || null;
  });
}

/**
 * Check if any block is currently being edited.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<boolean>}
 */
export async function isEditing(page) {
  const count = await page.locator('[contenteditable="true"]').count();
  return count > 0;
}

/**
 * Get the first block's ID.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<string>}
 */
export async function getFirstBlockId(page) {
  return await page.locator('[data-block-id]').first().getAttribute('data-block-id');
}

/**
 * Get block ID at a specific index.
 *
 * @param {import('@playwright/test').Page} page
 * @param {number} index
 * @returns {Promise<string>}
 */
export async function getBlockIdAt(page, index) {
  return await page.locator('div.block[data-block-id]').nth(index).getAttribute('data-block-id');
}

/**
 * Count total blocks on the page.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<number>}
 */
export async function countBlocks(page) {
  return await page.locator('div.block[data-block-id]').count();
}

/**
 * Create a new block with specified text.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} text - Text for the new block
 * @param {string} [parentId] - Parent block ID (optional, defaults to doc root)
 * @returns {Promise<string>} - The new block's ID
 */
export async function createBlock(page, text, parentId = null) {
  const newId = `test-block-${Date.now()}`;
  await page.evaluate(({ id, content, parent }) => {
    window.TEST_HELPERS?.dispatchIntent({
      type: 'create-block',
      id: id,
      text: content,
      parent: parent || ':doc'
    });
  }, { id: newId, content: text, parent: parentId });
  await page.waitForTimeout(100);
  return newId;
}

/**
 * Update block text content.
 *
 * Uses direct DB manipulation for test fixture setup.
 * This bypasses the state machine (which requires editing mode for content updates)
 * and is appropriate for setting initial test state.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} blockId
 * @param {string} text
 */
export async function updateBlockText(page, blockId, text) {
  await page.evaluate(({ id, content }) => {
    window.TEST_HELPERS?.setBlockText(id, content);
  }, { id: blockId, content: text });
  await page.waitForTimeout(100);
}

/**
 * Wait for blocks to be rendered.
 *
 * @param {import('@playwright/test').Page} page
 * @param {number} [timeout=5000]
 */
export async function waitForBlocks(page, timeout = 5000) {
  await page.waitForSelector('[data-block-id]', { timeout });
}

/**
 * Get all blocks on page with their text content.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<Array<{index: number, id: string, text: string, isFocused: boolean}>>}
 */
export async function getAllBlocks(page) {
  return await page.evaluate(() => {
    const blocks = document.querySelectorAll('[data-block-id]');
    return Array.from(blocks).map((block, idx) => {
      const editable = block.querySelector('[contenteditable="true"]') ||
                       (block.getAttribute('contenteditable') === 'true' ? block : null);
      return {
        index: idx,
        id: block.getAttribute('data-block-id'),
        text: editable?.textContent || block.textContent?.replace(/^•\s*/, ''),
        isFocused: editable === document.activeElement || block.contains(document.activeElement)
      };
    });
  });
}
