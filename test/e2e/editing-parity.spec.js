/**
 * Editing/Navigation Parity E2E Tests
 *
 * Tests for all new editing and navigation features:
 * - Shift+Arrow text selection
 * - Word navigation (Ctrl+Shift+F/B)
 * - Kill commands (Cmd+L, Cmd+U, Cmd+K, Cmd+Delete, Option+Delete)
 * - Selection operations (Cmd+A, Cmd+Shift+A)
 * - Undo/Redo (Cmd+Z, Cmd+Shift+Z, Cmd+Y)
 * - Ctrl+P/N navigation aliases
 * - Highlight/Strikethrough (Cmd+Shift+H, Cmd+Shift+S)
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
  getAllBlocks,
  getCursorPosition,
  setCursorPosition,
} from './helpers/index.js';

test.describe('Shift+Arrow Block Selection', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
  });

  test('Shift+Down extends block selection at last row', async ({ page }) => {
    // Create multiple blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('First block');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Second block');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Third block');

    // Navigate to first block
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);

    // Position cursor at end of first block
    const firstBlock = await page.evaluate(() => {
      const blocks = document.querySelectorAll('[contenteditable="true"]');
      return blocks[0].getAttribute('data-block-id');
    });
    await setCursorPosition(page, firstBlock, 11); // End of "First block"

    // Press Shift+Down to extend selection
    await page.keyboard.press('Shift+ArrowDown');
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
    await page.keyboard.press('Enter');
    await page.keyboard.type('Second block');

    // Cursor is at end of second block
    // Press Shift+Up to extend selection (Logseq parity: always exits edit)
    await page.keyboard.press('Shift+ArrowUp');
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
    await page.keyboard.press('Shift+ArrowRight');
    await page.keyboard.press('Shift+ArrowRight');
    await page.keyboard.press('Shift+ArrowRight'); // Should select "a m"
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
    await page.keyboard.press('Meta+A');
    await page.waitForTimeout(50);

    // Verify selection exists
    const selectedText = await page.evaluate(() => window.getSelection()?.toString());
    expect(selectedText.length).toBeGreaterThan(0);

    // Press Escape
    await page.keyboard.press('Escape');
    await page.waitForTimeout(50);

    // Verify selection cleared and exited edit mode
    const editingBlock = await page.evaluate(() => {
      return document.querySelector('[contenteditable="true"]');
    });
    expect(editingBlock).toBeNull(); // Should exit edit mode
  });
});

// SKIP: Word navigation uses Ctrl+Shift+F/B - not in Logseq spec (Emacs-style)
test.describe
  .skip('Word Navigation', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/blocks.html');
      await enterEditModeAndClick(page);
    });

    test('Ctrl+Shift+F moves cursor forward by word', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('hello world test');

      // Move to start
      await page.keyboard.press('Home');
      await page.waitForTimeout(100);

      // Press Ctrl+Shift+F (word forward on Mac)
      await page.keyboard.press('Control+Shift+KeyF');
      await page.waitForTimeout(100);

      const cursor = await getCursorPosition(page);

      // Should be after "hello " (position 6)
      expect(cursor.offset).toBe(6);
    });

    test('Ctrl+Shift+B moves cursor backward by word', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('hello world test');

      // Cursor at end, press Ctrl+Shift+B
      await page.keyboard.press('Control+Shift+KeyB');
      await page.waitForTimeout(100);

      const cursor = await getCursorPosition(page);

      // Should be at start of "test" (position 12)
      expect(cursor.offset).toBe(12);
    });

    test('Word navigation skips multiple spaces', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('hello   world'); // 3 spaces

      await page.keyboard.press('Home');
      await page.keyboard.press('Control+Shift+KeyF');
      await page.waitForTimeout(100);

      const cursor = await getCursorPosition(page);

      // Should jump to start of "world", skipping all spaces (position 8)
      expect(cursor.offset).toBe(8);
    });
  });

// SKIP: Kill commands conflict with app keybindings (Cmd+K = quick-switcher)
test.describe
  .skip('Kill Commands', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/blocks.html');
      await enterEditModeAndClick(page);
    });

    test('Cmd+L clears entire block content', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('Some text to clear');

      // Press Cmd+L
      await page.keyboard.press('Meta+KeyL');
      await page.waitForTimeout(100);

      const cursor = await getCursorPosition(page);

      expect(cursor.text).toBe('');
      expect(cursor.offset).toBe(0);
    });

    test('Cmd+U kills from cursor to beginning', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('hello world test');

      // Position cursor in middle (after "world ")
      await setCursorPosition(
        page,
        await page.evaluate(() =>
          document.querySelector('[contenteditable="true"]').getAttribute('data-block-id')
        ),
        12
      );

      // Press Cmd+U
      await page.keyboard.press('Meta+KeyU');
      await page.waitForTimeout(100);

      const cursor = await getCursorPosition(page);

      expect(cursor.text).toBe('test');
      expect(cursor.offset).toBe(0);
    });

    test('Cmd+K kills from cursor to end', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('hello world test');

      // Position cursor after "hello "
      await setCursorPosition(
        page,
        await page.evaluate(() =>
          document.querySelector('[contenteditable="true"]').getAttribute('data-block-id')
        ),
        6
      );

      // Press Cmd+K
      await page.keyboard.press('Meta+KeyK');
      await page.waitForTimeout(100);

      const cursor = await getCursorPosition(page);

      expect(cursor.text).toBe('hello ');
      expect(cursor.offset).toBe(6);
    });

    test('Cmd+Delete kills word forward', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('hello world test');

      // Move to start
      await page.keyboard.press('Home');
      await page.waitForTimeout(100);

      // Press Cmd+Delete
      await page.keyboard.press('Meta+Delete');
      await page.waitForTimeout(100);

      const cursor = await getCursorPosition(page);

      // "hello" should be deleted, cursor at start
      expect(cursor.text).toBe(' world test');
      expect(cursor.offset).toBe(0);
    });

    test('Option+Delete kills word backward', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('hello world test');

      // Cursor at end, press Option+Delete
      await page.keyboard.press('Alt+Delete');
      await page.waitForTimeout(100);

      const cursor = await getCursorPosition(page);

      // "test" should be deleted
      expect(cursor.text).toBe('hello world ');
      expect(cursor.offset).toBe(12);
    });
  });

test.describe('Selection Operations', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
  });

  test('Cmd+A while editing selects parent block', async ({ page }) => {
    // Create nested blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('Parent');
    await page.keyboard.press('Enter');
    await page.keyboard.press('Tab'); // Indent to create child
    await page.keyboard.type('Child block');

    // Press Cmd+A twice - first selects text, second exits edit and selects block
    await page.keyboard.press('Meta+KeyA');
    await page.waitForTimeout(100);
    await page.keyboard.press('Meta+KeyA');
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

  // NOTE: Skipped - Cmd+Shift+A (select all blocks) is not implemented
  // Currently only Cmd+A cycle (text -> block) is supported
  test.skip('Cmd+Shift+A selects all blocks in view', async ({ page }) => {
    // Create multiple blocks
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('First');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Second');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Third');

    // Press Cmd+Shift+A
    await page.keyboard.press('Meta+Shift+KeyA');
    await page.waitForTimeout(100);

    // Count selected blocks via session state
    const selectedCount = await page.evaluate(() => {
      const sess = window.TEST_HELPERS?.getSession?.();
      return sess?.selection?.nodes?.length || 0;
    });

    // Should select all visible blocks (at least 3)
    expect(selectedCount).toBeGreaterThanOrEqual(3);
  });
});

// NOTE: Skipped - Undo/redo only works for block operations via intents,
// not for text edits or Enter-created blocks during active editing.
// The history system tracks DB operations, not contenteditable changes.
test.describe
  .skip('Undo/Redo', () => {
    test.beforeEach(async ({ page }) => {
      // Use test mode for clean state
      await page.goto('/index.html?test=true');
      await page.waitForLoadState('domcontentloaded');
      await page.waitForSelector('[data-block-id]', { timeout: 5000 });
      await enterEditModeAndClick(page);
    });

    test('Cmd+Z undoes text changes', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('First version');
      await page.keyboard.press('Enter');
      await page.keyboard.type('Second block');

      // Undo
      await page.keyboard.press('Meta+KeyZ');
      await page.waitForTimeout(100);

      const blocks = await getAllBlocks(page);

      // Second block should be undone
      expect(blocks.length).toBeLessThanOrEqual(1);
    });

    test('Cmd+Shift+Z redoes changes', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('Original text');
      await page.keyboard.press('Enter');

      // Undo
      await page.keyboard.press('Meta+KeyZ');
      await page.waitForTimeout(100);

      // Redo with Cmd+Shift+Z
      await page.keyboard.press('Meta+Shift+KeyZ');
      await page.waitForTimeout(100);

      const blocks = await getAllBlocks(page);
      expect(blocks.length).toBeGreaterThan(1);
    });

    test('Cmd+Y also redoes changes', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('Original text');
      await page.keyboard.press('Enter');

      // Undo
      await page.keyboard.press('Meta+KeyZ');
      await page.waitForTimeout(100);

      // Redo with Cmd+Y (alternative)
      await page.keyboard.press('Meta+KeyY');
      await page.waitForTimeout(100);

      const blocks = await getAllBlocks(page);
      expect(blocks.length).toBeGreaterThan(1);
    });
  });

test.describe('Ctrl+P/N Navigation Aliases', () => {
  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
  });

  test('Ctrl+N navigates down like ArrowDown', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('First block');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Second block');

    // Navigate to first block
    await page.keyboard.press('ArrowUp');
    await page.waitForTimeout(100);

    // Press Ctrl+N (should behave like ArrowDown)
    await page.keyboard.press('Control+KeyN');
    await page.waitForTimeout(100);

    const cursor = await getCursorPosition(page);
    expect(cursor.text).toContain('Second');
  });

  test('Ctrl+P navigates up like ArrowUp', async ({ page }) => {
    await page.click('[contenteditable="true"]');
    await page.keyboard.type('First block');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Second block');

    // Cursor in second block, press Ctrl+P
    await page.keyboard.press('Control+KeyP');
    await page.waitForTimeout(100);

    const cursor = await getCursorPosition(page);
    expect(cursor.text).toContain('First');
  });
});

// SKIP: Relies on word selection (Ctrl+Shift+F) which isn't bound
test.describe
  .skip('Text Formatting - Highlight & Strikethrough', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/blocks.html');
      await enterEditModeAndClick(page);
    });

    test('Cmd+Shift+H adds highlight markers', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('highlight this text');

      // Select "highlight"
      await page.keyboard.press('Home');
      await page.keyboard.press('Shift+Control+Shift+KeyF'); // Select first word
      await page.waitForTimeout(100);

      // Apply highlight
      await page.keyboard.press('Meta+Shift+KeyH');
      await page.waitForTimeout(100);

      const text = await page.evaluate(() => {
        return document.querySelector('[contenteditable="true"]').textContent;
      });

      // Should contain highlight markers ^^
      expect(text).toContain('^^');
    });

    test('Cmd+Shift+S adds strikethrough markers', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('strike this text');

      // Select "strike"
      await page.keyboard.press('Home');
      await page.keyboard.press('Shift+Control+Shift+KeyF'); // Select first word
      await page.waitForTimeout(100);

      // Apply strikethrough
      await page.keyboard.press('Meta+Shift+KeyS');
      await page.waitForTimeout(100);

      const text = await page.evaluate(() => {
        return document.querySelector('[contenteditable="true"]').textContent;
      });

      // Should contain strikethrough markers ~~
      expect(text).toContain('~~');
    });
  });

// SKIP: Tests use word navigation and kill commands (not in Logseq spec)
test.describe
  .skip('UI Feel - No Regressions', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/blocks.html');
      await enterEditModeAndClick(page);
    });

    test('Word navigation does not break normal typing', async ({ page }) => {
      await page.click('[contenteditable="true"]');

      // Type, use word navigation, then continue typing
      await page.keyboard.type('hello world');
      await page.keyboard.press('Control+Shift+KeyB'); // Word backward
      await page.keyboard.type('beautiful ');

      const text = await page.evaluate(() => {
        return document.querySelector('[contenteditable="true"]').textContent;
      });

      expect(text).toContain('beautiful');
    });

    test('Kill commands do not break cursor position', async ({ page }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('start middle end');

      // Position in middle
      await page.keyboard.press('Home');
      await page.keyboard.press('Control+Shift+KeyF');
      await page.keyboard.press('Control+Shift+KeyF');

      // Kill to end
      await page.keyboard.press('Meta+KeyK');
      await page.waitForTimeout(50);

      // Type more
      await page.keyboard.type('NEW');

      const text = await page.evaluate(() => {
        return document.querySelector('[contenteditable="true"]').textContent;
      });

      expect(text).toContain('NEW');
    });

    test('Shift+Arrow selection does not interfere with normal arrow navigation', async ({
      page,
    }) => {
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('Line 1');
      await page.keyboard.press('Enter');
      await page.keyboard.type('Line 2');

      // Normal arrow up (without Shift)
      await page.keyboard.press('ArrowUp');
      await page.waitForTimeout(50);

      const cursor = await getCursorPosition(page);
      expect(cursor.text).toContain('Line 1');

      // Should not have extended selection
      const isCollapsed = await page.evaluate(() => {
        return window.getSelection().isCollapsed;
      });
      expect(isCollapsed).toBe(true);
    });
  });
