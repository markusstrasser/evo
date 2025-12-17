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

export { selectPage, enterEditModeAndClick } from './edit-mode.js';

export { getCursorPosition, setCursorPosition, typeAndVerifyCursor, expectCursorAt } from './cursor.js';

export {
  pressKeyOnContentEditable,
  pressKeyCombo,
  // Cross-platform helpers
  isMac,
  modKey,
  pressHome,
  pressEnd,
  pressWordLeft,
  pressWordRight,
  pressSelectToStart,
  pressSelectToEnd
} from './keyboard.js';

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

// ── Selection State Assertion Helpers ────────────────────────────────────────

/**
 * Get full selection state from session.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<{nodes: string[], anchor: string|null, focus: string|null, direction: string|null}>}
 */
export async function getSelectionState(page) {
  return page.evaluate(() => {
    const session = window.TEST_HELPERS?.getSession();
    if (!session?.selection) return { nodes: [], anchor: null, focus: null, direction: null };

    const nodes = session.selection.nodes;
    // Handle both array and set representations
    const nodeList = Array.isArray(nodes)
      ? nodes
      : (nodes instanceof Set ? Array.from(nodes) : Object.keys(nodes || {}));

    return {
      nodes: nodeList,
      anchor: session.selection.anchor || null,
      focus: session.selection.focus || null,
      direction: session.selection.direction || null
    };
  });
}

/**
 * Assert selection state matches expected values.
 *
 * @param {import('@playwright/test').Page} page
 * @param {object} expected - Expected selection state
 * @param {string[]} [expected.nodes] - Expected selected block IDs
 * @param {string} [expected.anchor] - Expected anchor block ID
 * @param {string} [expected.focus] - Expected focus block ID
 * @param {'forward'|'backward'|null} [expected.direction] - Expected selection direction
 * @returns {Promise<{pass: boolean, actual: object, message: string}>}
 */
export async function assertSelectionState(page, expected) {
  const actual = await getSelectionState(page);
  const errors = [];

  if (expected.nodes !== undefined) {
    const expectedSet = new Set(expected.nodes);
    const actualSet = new Set(actual.nodes);
    const missing = expected.nodes.filter(id => !actualSet.has(id));
    const extra = actual.nodes.filter(id => !expectedSet.has(id));
    if (missing.length > 0) errors.push(`Missing blocks: ${missing.join(', ')}`);
    if (extra.length > 0) errors.push(`Extra blocks: ${extra.join(', ')}`);
  }

  if (expected.anchor !== undefined && actual.anchor !== expected.anchor) {
    errors.push(`Anchor: expected '${expected.anchor}', got '${actual.anchor}'`);
  }

  if (expected.focus !== undefined && actual.focus !== expected.focus) {
    errors.push(`Focus: expected '${expected.focus}', got '${actual.focus}'`);
  }

  if (expected.direction !== undefined && actual.direction !== expected.direction) {
    errors.push(`Direction: expected '${expected.direction}', got '${actual.direction}'`);
  }

  return {
    pass: errors.length === 0,
    actual,
    message: errors.length > 0 ? errors.join('; ') : 'Selection state matches'
  };
}

/**
 * Wait for selection to match expected state.
 *
 * @param {import('@playwright/test').Page} page
 * @param {object} expected - Expected selection state
 * @param {number} [timeout=5000]
 */
export async function waitForSelectionState(page, expected, timeout = 5000) {
  const expectedNodes = expected.nodes ? new Set(expected.nodes) : null;

  await page.waitForFunction(
    ({ nodes, anchor, focus, direction }) => {
      const session = window.TEST_HELPERS?.getSession();
      if (!session?.selection) return false;

      const currentNodes = session.selection.nodes;
      const nodeList = Array.isArray(currentNodes)
        ? currentNodes
        : (currentNodes instanceof Set ? Array.from(currentNodes) : Object.keys(currentNodes || {}));
      const currentSet = new Set(nodeList);

      // Check nodes if specified
      if (nodes !== null) {
        const expectedSet = new Set(nodes);
        if (currentSet.size !== expectedSet.size) return false;
        for (const id of expectedSet) {
          if (!currentSet.has(id)) return false;
        }
      }

      // Check anchor if specified
      if (anchor !== undefined && session.selection.anchor !== anchor) return false;

      // Check focus if specified
      if (focus !== undefined && session.selection.focus !== focus) return false;

      // Check direction if specified
      if (direction !== undefined && session.selection.direction !== direction) return false;

      return true;
    },
    {
      nodes: expected.nodes || null,
      anchor: expected.anchor,
      focus: expected.focus,
      direction: expected.direction
    },
    { timeout }
  );
}

/**
 * Get cursor state including position and editing block.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<{editingBlockId: string|null, cursorPosition: number|null}>}
 */
export async function getCursorState(page) {
  return page.evaluate(() => {
    const session = window.TEST_HELPERS?.getSession();
    return {
      editingBlockId: session?.ui?.editing_block_id || session?.ui?.['editing-block-id'] || null,
      cursorPosition: session?.ui?.cursor_position || session?.ui?.['cursor-position'] || null
    };
  });
}

/**
 * Assert cursor state after an operation.
 *
 * @param {import('@playwright/test').Page} page
 * @param {object} expected
 * @param {string} [expected.editingBlockId] - Expected block being edited
 * @param {number} [expected.cursorPosition] - Expected cursor position
 * @returns {Promise<{pass: boolean, actual: object, message: string}>}
 */
export async function assertCursorState(page, expected) {
  const actual = await getCursorState(page);
  const errors = [];

  if (expected.editingBlockId !== undefined && actual.editingBlockId !== expected.editingBlockId) {
    errors.push(`Editing block: expected '${expected.editingBlockId}', got '${actual.editingBlockId}'`);
  }

  if (expected.cursorPosition !== undefined && actual.cursorPosition !== expected.cursorPosition) {
    errors.push(`Cursor position: expected ${expected.cursorPosition}, got ${actual.cursorPosition}`);
  }

  return {
    pass: errors.length === 0,
    actual,
    message: errors.length > 0 ? errors.join('; ') : 'Cursor state matches'
  };
}
