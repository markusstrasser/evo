import { test, expect } from '@playwright/test';
import { selectPage } from './helpers/index.js';

/**
 * Logseq Parity: Idle State Guard (FR-Idle-01..03)
 *
 * Spec: dev/specs/LOGSEQ_SPEC.md §1.1 (Global Interaction Model)
 * Overlay: docs/specs/LOGSEQ_PARITY_EVO.md §3 (G-Idle-01)
 * Gap: LOGSEQ_PARITY.md G-Idle-01
 *
 * In fully idle state (no block selected, no block editing):
 * - FR-Idle-01: Enter/Backspace/Tab/Shift+Enter/Shift+Arrow/Cmd+Enter are no-ops
 * - FR-Idle-02: ArrowDown selects first visible block; ArrowUp selects last
 * - FR-Idle-03: Typing printable character enters edit mode and appends character
 */

test.describe('Idle State Guard (FR-Idle-01..03)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/blocks.html');
    await selectPage(page); // Close overlays
    await page.waitForSelector('[data-block-id]');
  });

  /**
   * Helper: Ensure we're in true idle state
   * - Press Escape to exit any editing
   * - Click background to clear selection
   * - Verify no contenteditable is focused
   * - Verify no selection state in session
   */
  async function ensureIdleState(page) {
    // Press Escape first to exit any editing
    await page.keyboard.press('Escape');
    await page.waitForTimeout(50);

    // Click empty background area (far left, high up) to clear selection
    await page.mouse.click(10, 100);
    await page.waitForTimeout(50);

    // Verify idle state via TEST_HELPERS.getSession() (returns plain JS)
    const state = await page.evaluate(() => {
      const sess = window.TEST_HELPERS?.getSession?.();
      if (!sess) return { idle: false };

      const editingId = sess.ui?.['editing-block-id'];
      // getSession() converts ClojureScript set to JS array
      const selectionNodes = sess.selection?.nodes || [];
      const focus = sess.selection?.focus;

      return {
        editing: !!editingId,
        hasSelection: selectionNodes.length > 0 || !!focus,
        idle: !editingId && selectionNodes.length === 0 && !focus
      };
    });

    expect(state.idle).toBe(true);
  }

  /**
   * Helper: Count total blocks on the page
   */
  async function countBlocks(page) {
    return await page.evaluate(() =>
      document.querySelectorAll('[data-block-id]').length
    );
  }

  test.describe('FR-Idle-01: Idle guard - no accidental edits', () => {
    test('Enter in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      const beforeCount = await countBlocks(page);
      await page.keyboard.press('Enter');
      await page.waitForTimeout(100); // Wait for any async effects
      const afterCount = await countBlocks(page);

      // Should NOT create new block
      expect(afterCount).toBe(beforeCount);

      // Should still be in idle state
      const stillIdle = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        return sess && !sess.ui?.['editing-block-id'];
      });
      expect(stillIdle).toBe(true);
    });

    test('Backspace in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      const beforeCount = await countBlocks(page);
      await page.keyboard.press('Backspace');
      await page.waitForTimeout(100);
      const afterCount = await countBlocks(page);

      // Should NOT delete any blocks
      expect(afterCount).toBe(beforeCount);
    });

    test('Delete in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      const beforeCount = await countBlocks(page);
      await page.keyboard.press('Delete');
      await page.waitForTimeout(100);
      const afterCount = await countBlocks(page);

      // Should NOT delete any blocks
      expect(afterCount).toBe(beforeCount);
    });

    test('Tab in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      // Get DB snapshot before Tab
      const beforeDB = await page.evaluate(() => {
        return JSON.stringify(window.TEST_HELPERS?.getDb?.() || {});
      });

      await page.keyboard.press('Tab');
      await page.waitForTimeout(100);

      const afterDB = await page.evaluate(() => {
        return JSON.stringify(window.TEST_HELPERS?.getDb?.() || {});
      });

      // DB should be unchanged (no indenting happened)
      expect(afterDB).toBe(beforeDB);
    });

    test('Shift+Tab in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      const beforeDB = await page.evaluate(() => {
        return JSON.stringify(window.TEST_HELPERS?.getDb?.() || {});
      });

      await page.keyboard.press('Shift+Tab');
      await page.waitForTimeout(100);

      const afterDB = await page.evaluate(() => {
        return JSON.stringify(window.TEST_HELPERS?.getDb?.() || {});
      });

      // DB should be unchanged
      expect(afterDB).toBe(beforeDB);
    });

    test('Shift+Enter in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      const beforeCount = await countBlocks(page);
      await page.keyboard.press('Shift+Enter');
      await page.waitForTimeout(100);
      const afterCount = await countBlocks(page);

      // Should NOT create new block or modify anything
      expect(afterCount).toBe(beforeCount);
    });

    test('Cmd+Enter in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      const beforeCount = await countBlocks(page);
      await page.keyboard.press('Meta+Enter');
      await page.waitForTimeout(100);
      const afterCount = await countBlocks(page);

      // Should NOT toggle checkbox or create blocks
      expect(afterCount).toBe(beforeCount);
    });

    test('Shift+ArrowUp in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      await page.keyboard.press('Shift+ArrowUp');
      await page.waitForTimeout(100);

      // Should NOT select any blocks
      const hasSelection = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        if (!sess) return false;
        const nodes = sess.selection?.nodes || [];
        return nodes.length > 0;
      });
      expect(hasSelection).toBe(false);
    });

    test('Shift+ArrowDown in idle state is a no-op', async ({ page }) => {
      await ensureIdleState(page);

      await page.keyboard.press('Shift+ArrowDown');
      await page.waitForTimeout(100);

      // Should NOT select any blocks
      const hasSelection = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        if (!sess) return false;
        const nodes = sess.selection?.nodes || [];
        return nodes.length > 0;
      });
      expect(hasSelection).toBe(false);
    });
  });

  test.describe('FR-Idle-02: ArrowDown/Up select first/last', () => {
    test('ArrowDown in idle state selects first visible block', async ({ page }) => {
      await ensureIdleState(page);

      await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(50);

      // Should select exactly one block (the first one)
      const state = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        const db = window.TEST_HELPERS?.getDb?.();
        if (!sess || !db) return { selectedCount: 0, isFirst: false };

        const focus = sess.selection?.focus;
        const currentPage = sess.ui?.['current-page'];
        const allBlocks = db['children-by-parent']?.[currentPage] || [];

        return {
          selectedCount: focus ? 1 : 0,
          isFirst: focus === allBlocks[0]
        };
      });

      expect(state.selectedCount).toBe(1);
      expect(state.isFirst).toBe(true);
    });

    test('ArrowUp in idle state selects last visible block', async ({ page }) => {
      await ensureIdleState(page);

      await page.keyboard.press('ArrowUp');
      await page.waitForTimeout(50);

      // Should select exactly one block (the last one)
      const state = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        const db = window.TEST_HELPERS?.getDb?.();
        if (!sess || !db) return { selectedCount: 0, isLast: false };

        const focus = sess.selection?.focus;
        const currentPage = sess.ui?.['current-page'];
        const allBlocks = db['children-by-parent']?.[currentPage] || [];

        return {
          selectedCount: focus ? 1 : 0,
          isLast: focus === allBlocks[allBlocks.length - 1]
        };
      });

      expect(state.selectedCount).toBe(1);
      expect(state.isLast).toBe(true);
    });
  });

  test.describe('FR-Idle-03: Type-to-edit behavior', () => {
    test('typing printable character in idle (no selection) does nothing', async ({ page }) => {
      await ensureIdleState(page);

      // Type a character when nothing is selected
      await page.keyboard.type('x');
      await page.waitForTimeout(100);

      // Should NOT enter edit mode (nothing was focused)
      const isEditing = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        return sess && !!sess.ui?.['editing-block-id'];
      });

      expect(isEditing).toBe(false);
    });

    test('typing printable character when block is focused enters edit mode', async ({ page }) => {
      // First, select a block (ArrowDown from idle)
      await ensureIdleState(page);
      await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(50);

      // Verify we're in view mode (focused but not editing)
      const beforeState = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        if (!sess) return { editing: false, hasFocus: false };
        return {
          editing: !!sess.ui?.['editing-block-id'],
          hasFocus: !!sess.selection?.focus
        };
      });

      expect(beforeState.editing).toBe(false);
      expect(beforeState.hasFocus).toBe(true);

      // Type a printable character
      await page.keyboard.type('x');
      await page.waitForTimeout(100);

      // Should now be editing
      const afterState = await page.evaluate(() => {
        const sess = window.TEST_HELPERS?.getSession?.();
        const db = window.TEST_HELPERS?.getDb?.();
        if (!sess) return { editing: false, text: '' };
        const editingId = sess.ui?.['editing-block-id'];
        const text = editingId && db ? (db.nodes?.[editingId]?.props?.text || '') : '';
        return {
          editing: !!editingId,
          text
        };
      });

      expect(afterState.editing).toBe(true);
      // Character should have been appended
      expect(afterState.text).toContain('x');
    });
  });
});
