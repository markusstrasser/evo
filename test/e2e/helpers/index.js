/**
 * Unified E2E Test Helpers
 *
 * Single import point for all test utilities.
 * Import from here instead of individual helper files.
 *
 * @example
 * import {
 *   selectPage, selectBlock, enterEditMode, exitEditMode,
 *   waitForState, getStateMachineState, debugIntent
 * } from './helpers/index.js';
 */

// ── Re-exports from existing helpers ─────────────────────────────────────────

export { selectPage } from './edit-mode.js';

export {
  selectBlock,
  clearSelection,
  enterEditMode,
  exitEditMode,
  isBlockSelected,
  countSelectedBlocks,
  getBlockText,
  getEditingBlockId,
  isEditing,
  getFirstBlockId,
  getBlockIdAt,
  countBlocks,
  createBlock,
  updateBlockText,
  waitForBlocks,
  getAllBlocks
} from './block-helpers.js';

export { pressKeyOnContentEditable } from './keyboard.js';

// ── Session State Helpers ────────────────────────────────────────────────────

/**
 * Get the current session state from the app.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<object>} Session state object
 */
export async function getSession(page) {
  return page.evaluate(() => window.TEST_HELPERS?.getSession());
}

/**
 * Get the current state machine state.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<'idle'|'selection'|'editing'>}
 */
export async function getStateMachineState(page) {
  return page.evaluate(() => {
    const session = window.TEST_HELPERS?.getSession();
    if (!session) return 'unknown';
    if (session.ui?.editing_block_id) return 'editing';
    if (session.selection?.nodes && Object.keys(session.selection.nodes).length > 0) return 'selection';
    return 'idle';
  });
}

/**
 * Get a snapshot of the current app state for debugging.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<object>} State snapshot
 */
export async function getStateSnapshot(page) {
  return page.evaluate(() => window.TEST_HELPERS?.snapshot?.() || null);
}

/**
 * Debug an intent - check if it would be allowed and why.
 *
 * @param {import('@playwright/test').Page} page
 * @param {object} intent - Intent to debug
 * @returns {Promise<{allowed: boolean, currentState: string, reason?: string}>}
 */
export async function debugIntent(page, intent) {
  return page.evaluate((i) => window.TEST_HELPERS?.debugIntent?.(i) || null, intent);
}

// ── Wait Helpers (replace waitForTimeout) ────────────────────────────────────

/**
 * Wait for the app to reach a specific state machine state.
 *
 * @param {import('@playwright/test').Page} page
 * @param {'idle'|'selection'|'editing'} expectedState
 * @param {number} [timeout=5000]
 */
export async function waitForState(page, expectedState, timeout = 5000) {
  await page.waitForFunction(
    (state) => {
      const session = window.TEST_HELPERS?.getSession();
      if (!session) return false;
      const editing = session.ui?.editing_block_id;
      const hasSelection = session.selection?.nodes && Object.keys(session.selection.nodes).length > 0;
      const current = editing ? 'editing' : (hasSelection ? 'selection' : 'idle');
      return current === state;
    },
    expectedState,
    { timeout }
  );
}

/**
 * Wait for a specific block to be in edit mode.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} blockId
 * @param {number} [timeout=5000]
 */
export async function waitForEditing(page, blockId, timeout = 5000) {
  await page.waitForFunction(
    (id) => {
      const session = window.TEST_HELPERS?.getSession();
      return session?.ui?.editing_block_id === id;
    },
    blockId,
    { timeout }
  );
}

/**
 * Wait for selection to include specific block(s).
 *
 * @param {import('@playwright/test').Page} page
 * @param {string|string[]} blockIds
 * @param {number} [timeout=5000]
 */
export async function waitForSelection(page, blockIds, timeout = 5000) {
  const ids = Array.isArray(blockIds) ? blockIds : [blockIds];
  await page.waitForFunction(
    (expectedIds) => {
      const session = window.TEST_HELPERS?.getSession();
      if (!session?.selection?.nodes) return false;
      const selected = Object.keys(session.selection.nodes);
      return expectedIds.every(id => selected.includes(id));
    },
    ids,
    { timeout }
  );
}

/**
 * Wait for idle state (no selection, no editing).
 *
 * @param {import('@playwright/test').Page} page
 * @param {number} [timeout=5000]
 */
export async function waitForIdle(page, timeout = 5000) {
  await waitForState(page, 'idle', timeout);
}

// ── Assertion Helpers ────────────────────────────────────────────────────────

/**
 * Assert that an intent would be allowed in current state.
 * Throws with helpful message if not.
 *
 * @param {import('@playwright/test').Page} page
 * @param {object} intent
 */
export async function assertIntentAllowed(page, intent) {
  const debug = await debugIntent(page, intent);
  if (!debug?.allowed) {
    const reason = debug?.reason || `Intent ${intent.type} not allowed in state ${debug?.currentState}`;
    throw new Error(`Intent blocked: ${reason}`);
  }
}

/**
 * Get selected block IDs from session (more reliable than DOM checks).
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<string[]>}
 */
export async function getSelectedBlockIds(page) {
  return page.evaluate(() => {
    const session = window.TEST_HELPERS?.getSession();
    if (!session?.selection?.nodes) return [];
    return Object.keys(session.selection.nodes);
  });
}
