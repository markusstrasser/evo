/**
 * E2E Test Helpers using window.DEBUG API
 *
 * These helpers provide a cleaner, more maintainable interface for E2E tests
 * by wrapping window.DEBUG utilities with guardrails and better error messages.
 */

/**
 * Enter edit mode on a block programmatically.
 * Waits for edit mode to be active before returning.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @param {string} blockId - Block ID to edit
 * @param {'start'|'end'|number} cursorAt - Cursor position (default: 'end')
 * @param {number} timeout - Timeout in ms (default: 2000)
 * @returns {Promise<void>}
 *
 * @example
 * await enterEditMode(page, 'block-123', 'end');
 */
export async function enterEditMode(page, blockId, cursorAt = 'end', timeout = 2000) {
  await page.evaluate(({ id, cursor }) => {
    window.DEBUG.enterEditMode(id, cursor);
  }, { id: blockId, cursor: cursorAt });

  // Wait for edit mode to be active
  await page.waitForFunction((id) => {
    return window.DEBUG.getEditingBlockId() === id;
  }, blockId, { timeout });

  // Also wait for contenteditable to be focused
  await page.waitForSelector('[contenteditable="true"]', { timeout });
}

/**
 * Exit edit mode programmatically.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @param {number} timeout - Timeout in ms (default: 2000)
 * @returns {Promise<void>}
 *
 * @example
 * await exitEditMode(page);
 */
export async function exitEditMode(page, timeout = 2000) {
  await page.evaluate(() => {
    window.DEBUG.exitEditMode();
  });

  // Wait for edit mode to be inactive
  await page.waitForFunction(() => {
    return !window.DEBUG.isEditMode();
  }, { timeout });
}

/**
 * Wait for a specific block to be in edit mode.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @param {string} blockId - Block ID to wait for
 * @param {number} timeout - Timeout in ms (default: 2000)
 * @returns {Promise<void>}
 *
 * @example
 * await waitForEditMode(page, 'block-123');
 */
export async function waitForEditMode(page, blockId, timeout = 2000) {
  await page.waitForFunction((id) => {
    return window.DEBUG.getEditingBlockId() === id;
  }, blockId, { timeout });
}

/**
 * Wait for edit mode to be inactive (no block being edited).
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @param {number} timeout - Timeout in ms (default: 2000)
 * @returns {Promise<void>}
 *
 * @example
 * await waitForNoEditMode(page);
 */
export async function waitForNoEditMode(page, timeout = 2000) {
  await page.waitForFunction(() => {
    return !window.DEBUG.isEditMode();
  }, { timeout });
}

/**
 * Get the current editing block ID.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @returns {Promise<string|null>} Block ID or null if not editing
 *
 * @example
 * const blockId = await getEditingBlockId(page);
 * expect(blockId).toBe('block-123');
 */
export async function getEditingBlockId(page) {
  return await page.evaluate(() => {
    return window.DEBUG.getEditingBlockId();
  });
}

/**
 * Check if any block is currently being edited.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @returns {Promise<boolean>}
 *
 * @example
 * const isEditing = await isEditMode(page);
 * expect(isEditing).toBe(true);
 */
export async function isEditMode(page) {
  return await page.evaluate(() => {
    return window.DEBUG.isEditMode();
  });
}

/**
 * Get block text content by ID.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @param {string} blockId - Block ID
 * @returns {Promise<string>} Block text content
 *
 * @example
 * const text = await getBlockText(page, 'block-123');
 * expect(text).toBe('Hello world');
 */
export async function getBlockText(page, blockId) {
  return await page.evaluate((id) => {
    return window.DEBUG.getBlockText(id);
  }, blockId);
}

/**
 * Log current edit mode state for debugging.
 * Useful when tests fail and you need to inspect state.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @returns {Promise<void>}
 *
 * @example
 * await logEditModeState(page); // Check console for output
 */
export async function logEditModeState(page) {
  await page.evaluate(() => {
    window.DEBUG.logState();
  });
}

/**
 * Dispatch an intent programmatically.
 * Validates intent structure before dispatch.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @param {object} intent - Intent object with at least a 'type' property
 * @returns {Promise<void>}
 *
 * @example
 * await dispatchIntent(page, { type: 'enter-edit', 'block-id': 'abc', 'cursor-at': 'end' });
 */
export async function dispatchIntent(page, intent) {
  if (!intent.type) {
    throw new Error('Intent must have a "type" property');
  }

  await page.evaluate((intentMap) => {
    window.DEBUG.dispatch(intentMap);
  }, intent);
}

/**
 * Wait for a predicate function to return true.
 * Uses window.DEBUG.waitFor internally.
 *
 * @param {import('@playwright/test').Page} page - Playwright page
 * @param {Function} predicateFn - Function that returns true when condition met
 * @param {number} timeout - Timeout in ms (default: 2000)
 * @returns {Promise<void>}
 *
 * @example
 * await waitFor(page, () => window.DEBUG.isEditMode(), 2000);
 */
export async function waitFor(page, predicateFn, timeout = 2000) {
  await page.waitForFunction(predicateFn, { timeout });
}
