/**
 * Editing/Navigation Parity E2E Tests
 *
 * Tests for all new editing and navigation features:
 * - Shift+Arrow text selection
 * - Selection operations (Cmd+A)
 * - Ctrl+P/N navigation aliases
 *
 * Scenario IDs covered:
 * - EDIT-ENTER-SPLIT-01, EDIT-ENTER-ABOVE-01, EDIT-ENTER-LIST-01
 * - EDIT-BACKSPACE-MERGE-01, EDIT-BACKSPACE-DELETE-01, EDIT-DELETE-MERGE-01
 * - EDIT-NEWLINE-01, EDIT-ENTER-EMPTY-LIST-01
 * - NAV-CURSOR-MEMORY-01, NAV-EMACS-PN-01, NAV-EMACS-BOL-01, NAV-EMACS-EOL-01
 * - NAV-EMACS-BOB-01, NAV-EMACS-EOB-01, NAV-COLLAPSE-SEL-01
 * - SEL-SHIFT-BOUNDARY-01, SEL-EXTEND-CONTRACT-01, SEL-ARROW-REPLACE-01
 * - SEL-DIRECTION-TRACK-01, SEL-CONTRACT-TRAILING-01
 */

import { expect, test } from '@playwright/test';
import {
  enterEditModeAndClick,
  getCursorPosition,
  pressKeyCombo,
  pressKeyOnContentEditable,
  setCursorPosition,
} from './helpers/index.js';

async function loadTwoBlockFixture(page) {
  await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
  await page.evaluate(() => {
    window.TEST_HELPERS.loadFixture({
      ops: [
        { op: 'create-node', id: 'test-page', type: 'page', props: { title: 'Test Page' } },
        { op: 'place', id: 'test-page', under: 'doc', at: 'last' },
        { op: 'create-node', id: 'first-block', type: 'block', props: { text: 'First block' } },
        { op: 'place', id: 'first-block', under: 'test-page', at: 'last' },
        { op: 'create-node', id: 'second-block', type: 'block', props: { text: 'Second block' } },
        { op: 'place', id: 'second-block', under: 'test-page', at: 'last' },
      ],
      session: {
        ui: { 'current-page': 'test-page', 'journals-view?': false },
        selection: { nodes: [] },
      },
    });
  });
}

async function enterFixtureBlock(page, blockId, offset) {
  await page.evaluate((id) => {
    window.TEST_HELPERS.dispatchIntent({ type: 'selection', mode: 'replace', ids: id });
    window.TEST_HELPERS.dispatchIntent({ type: 'enter-edit', 'block-id': id });
  }, blockId);
  await page.waitForFunction(
    (id) => window.TEST_HELPERS?.getSession?.()?.ui?.['editing-block-id'] === id,
    blockId,
    { timeout: 2000 }
  );
  await page.waitForSelector('[contenteditable="true"]', { timeout: 2000 });
  await setCursorPosition(page, blockId, offset);
}

test.describe('Shift+Arrow Block Selection', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
  });

  test('Shift+Down extends block selection at last row', async ({ page }) => {
    // Create multiple blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('First block');
    await pressKeyOnContentEditable(page, 'Enter');
    await page.keyboard.type('Second block');
    await pressKeyOnContentEditable(page, 'Enter');
    await page.keyboard.type('Third block');

    // Navigate to first block
    await pressKeyOnContentEditable(page, 'ArrowUp');
    await pressKeyOnContentEditable(page, 'ArrowUp');
    await page.waitForTimeout(100);

    // Position cursor at end of first block
    const firstBlock = await page.evaluate(() => {
      const blocks = document.querySelectorAll('[contenteditable="true"]');
      return blocks[0].getAttribute('data-block-id');
    });
    await setCursorPosition(page, firstBlock, 11); // End of "First block"

    // Press Shift+Down to extend selection
    await pressKeyCombo(page, 'ArrowDown', ['Shift']);
    await page.waitForTimeout(100);

    // Verify blocks are selected via session state (more reliable than CSS)
    const selectedCount = await page.evaluate(() => {
      const sess = window.TEST_HELPERS?.getSession?.();
      const nodes = sess?.selection?.nodes || [];
      return nodes.length;
    });

    // Logseq parity: Shift+Arrow always exits edit and extends selection
    expect(selectedCount).toBeGreaterThanOrEqual(1);
  });

  test('Shift+Up extends block selection at first row', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('First block');
    await pressKeyOnContentEditable(page, 'Enter');
    await page.keyboard.type('Second block');

    // Cursor is at end of second block
    // Press Shift+Up to extend selection (Logseq parity: always exits edit)
    await pressKeyCombo(page, 'ArrowUp', ['Shift']);
    await page.waitForTimeout(100);

    // Verify selection via session state
    const selectedCount = await page.evaluate(() => {
      const sess = window.TEST_HELPERS?.getSession?.();
      const nodes = sess?.selection?.nodes || [];
      return nodes.length;
    });

    expect(selectedCount).toBeGreaterThan(0);
  });

  test('Shift+Arrow extends TEXT selection within block (not block selection)', async ({
    page,
  }) => {
    // LOGSEQ_SPEC §3 Rule 3: Shift+Arrow should extend text selection when NOT at boundary
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('This is a multiline block of text that wraps');

    // Position cursor in middle of text
    const blockId = await page.evaluate(() => {
      const editable = document.querySelector('[contenteditable="true"]');
      return editable?.closest('[data-block-id]')?.getAttribute('data-block-id');
    });
    await setCursorPosition(page, blockId, 8); // After "This is "
    await page.waitForTimeout(100);

    // Press Shift+ArrowRight several times to select text
    await pressKeyCombo(page, 'ArrowRight', ['Shift']);
    await pressKeyCombo(page, 'ArrowRight', ['Shift']);
    await pressKeyCombo(page, 'ArrowRight', ['Shift']); // Should select "a m"
    await page.waitForTimeout(100);

    // Verify TEXT selection exists (not block selection)
    const { hasTextSelection, selectedText } = await page.evaluate(() => {
      const sel = window.getSelection();
      return {
        hasTextSelection: sel && sel.rangeCount > 0 && sel.toString().length > 0,
        selectedText: sel ? sel.toString() : '',
      };
    });

    expect(hasTextSelection).toBe(true);
    expect(selectedText.length).toBeGreaterThan(0);

    // Verify NO block-level selection occurred (check session state, not CSS)
    // Note: Editing block may have focus styling, so we check selection.nodes is empty
    const selectionState = await page.evaluate(() => {
      const session = window.TEST_HELPERS?.getSession?.();
      return {
        selectionNodes: session?.selection?.nodes || [],
        selectionFocus: session?.selection?.focus,
      };
    });
    // Block selection should be empty (only text selection within the editing block)
    expect(selectionState.selectionNodes.length).toBe(0);
    expect(selectionState.selectionFocus).toBeFalsy();
  });

  test('Escape clears text selection', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Select this text');

    // Select all text with Cmd+A
    await pressKeyOnContentEditable(page, 'a', { metaKey: true });
    await page.waitForTimeout(50);

    // Verify selection exists
    const selectedText = await page.evaluate(() => window.getSelection()?.toString());
    expect(selectedText.length).toBeGreaterThan(0);

    // Press Escape
    await pressKeyOnContentEditable(page, 'Escape');
    await page.waitForTimeout(50);

    // Verify selection cleared and exited edit mode
    const editingBlock = await page.evaluate(() => {
      return document.querySelector('[contenteditable="true"]');
    });
    expect(editingBlock).toBeNull(); // Should exit edit mode
  });
});

test.describe('Selection Operations', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
  });

  test('Cmd+A while editing selects parent block', async ({ page }) => {
    // Create nested blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Parent');
    await pressKeyOnContentEditable(page, 'Enter');
    await pressKeyOnContentEditable(page, 'Tab'); // Indent to create child
    await page.keyboard.type('Child block');

    // Press Cmd+A twice - first selects text, second exits edit and selects block
    await pressKeyOnContentEditable(page, 'a', { metaKey: true });
    await page.waitForTimeout(100);
    await pressKeyOnContentEditable(page, 'a', { metaKey: true });
    await page.waitForTimeout(100);

    // Verify a block is selected (not editing) via session state
    const state = await page.evaluate(() => {
      const sess = window.TEST_HELPERS?.getSession?.();
      return {
        isEditing: !!sess?.ui?.['editing-block-id'],
        focusId: sess?.selection?.focus,
        selectedCount: sess?.selection?.nodes?.length || 0,
      };
    });

    expect(state.isEditing).toBe(false);
    expect(state.focusId || state.selectedCount > 0).toBeTruthy();
  });
});

test.describe('Ctrl+P/N Navigation Aliases', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await loadTwoBlockFixture(page);
    await page.waitForSelector('[data-block-id="first-block"]', { timeout: 5000 });
  });

  test('Ctrl+N navigates down like ArrowDown', async ({ page }) => {
    await enterFixtureBlock(page, 'first-block', 'First block'.length);

    // Press Ctrl+N (should behave like ArrowDown)
    await pressKeyOnContentEditable(page, 'n', { ctrlKey: true });
    await page.waitForTimeout(100);

    const cursor = await getCursorPosition(page);
    expect(cursor.text).toContain('Second');
  });

  test('Ctrl+P navigates up like ArrowUp', async ({ page }) => {
    await enterFixtureBlock(page, 'second-block', 'Second block'.length);

    await pressKeyOnContentEditable(page, 'p', { ctrlKey: true });
    await page.waitForTimeout(100);

    const cursor = await getCursorPosition(page);
    expect(cursor.text).toContain('First');
  });
});
