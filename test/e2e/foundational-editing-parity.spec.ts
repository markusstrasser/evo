import { test, expect } from '@playwright/test';
import { enterEditModeAndClick, selectPage } from './helpers/edit-mode.js';

/**
 * Logseq Parity: Foundational Editing/Navigation/Selection
 *
 * Tests CORE behaviors that must match Logseq exactly for muscle memory.
 * Every edge case documented here was found in actual Logseq testing.
 */

// SKIP: Tests rely on demo data text ("Tech Stack") that conflicts with selectPage helper
// Many tests are also for Emacs-style bindings not in Logseq spec
test.describe.skip('Foundational Editing Parity', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/blocks.html');
    await selectPage(page); // Closes overlays, loads blocks
    await enterEditModeAndClick(page); // Enters edit mode on first block
  });

  test.describe('Enter Key Behaviors', () => {
    test('plain Enter creates block below with cursor at start', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End'); // Go to end
      await page.keyboard.type('hello');
      await page.keyboard.press('Enter');

      // Should create new block, cursor at position 0
      const focused = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]') as HTMLElement;
        const sel = window.getSelection()!;
        return {
          text: el.textContent,
          cursorPos: sel.getRangeAt(0).startOffset,
          isEmpty: el.textContent === ''
        };
      });

      expect(focused.isEmpty).toBe(true);
      expect(focused.cursorPos).toBe(0);
    });

    test('Enter at position 0 creates block ABOVE', async ({ page }) => {
      await page.getByText('Tech Stack').click();

      // Get initial text
      const beforeText = await page.evaluate(() =>
        document.querySelector('[contenteditable]')!.textContent
      );

      await page.keyboard.press('Home'); // Position 0
      await page.keyboard.press('Enter');

      // Should create block above, original block still has same text
      await page.keyboard.press('ArrowDown'); // Go back to original
      const afterText = await page.evaluate(() =>
        document.querySelector('[contenteditable]')!.textContent
      );

      expect(afterText).toBe(beforeText);
    });

    test('Shift+Enter inserts literal newline in block', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('Line 1');
      await page.keyboard.press('Shift+Enter'); // CRITICAL: Must insert \n
      await page.keyboard.type('Line 2');

      const text = await page.evaluate(() =>
        document.querySelector('[contenteditable]')!.textContent
      );

      // Should have newline character, NOT create new block
      expect(text).toContain('Line 1\nLine 2');

      // Count blocks - should still be just 1 block
      const blockCount = await page.evaluate(() =>
        document.querySelectorAll('[contenteditable]').length
      );
      expect(blockCount).toBe(1);
    });

    test('Enter in list continues list pattern', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.press('Enter');
      await page.keyboard.type('- Item 1');
      await page.keyboard.press('Enter');

      const text = await page.evaluate(() =>
        document.querySelector('[contenteditable]')!.textContent
      );

      expect(text).toMatch(/^-\s/); // Should start with "- "
    });

    test('Enter in empty list unformats AND creates peer block', async ({ page }) => {
      // Create nested list item
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.press('Enter');
      await page.keyboard.type('- Parent');
      await page.keyboard.press('Enter');
      await page.keyboard.press('Tab'); // Indent to create child
      await page.keyboard.type('- '); // Empty list marker

      // Press Enter on empty list item
      await page.keyboard.press('Enter');

      // Should: 1) Remove marker, 2) Create peer after parent
      const state = await page.evaluate(() => {
        const editables = Array.from(document.querySelectorAll('[contenteditable]'));
        return editables.map(el => ({
          text: el.textContent,
          focused: document.activeElement === el
        }));
      });

      // Find the block we just unformatted
      const unformatted = state.find(b => b.text === '' && !b.focused);
      expect(unformatted).toBeTruthy();

      // New block should be focused and at peer level
      const focused = state.find(b => b.focused);
      expect(focused?.text).toBe('');
    });
  });

  test.describe('Emacs Navigation (Line/Block Boundaries)', () => {
    test('Ctrl+A moves to beginning of line', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('\nSecond line with text');
      await page.keyboard.press('Control+a'); // Beginning of LINE (not block)

      const cursorPos = await page.evaluate(() =>
        window.getSelection()!.getRangeAt(0).startOffset
      );

      // Should be at start of "Second line", not start of block
      const text = await page.evaluate(() =>
        document.querySelector('[contenteditable]')!.textContent || ''
      );
      const secondLineStart = text.indexOf('Second');

      expect(cursorPos).toBe(secondLineStart);
    });

    test('Ctrl+E moves to end of line', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('\nSecond line');
      await page.keyboard.press('Control+a'); // Go to line start
      await page.keyboard.press('Control+e'); // Go to line end

      const state = await page.evaluate(() => {
        const text = document.querySelector('[contenteditable]')!.textContent || '';
        const cursorPos = window.getSelection()!.getRangeAt(0).startOffset;
        const lineText = text.split('\n')[1]; // Second line
        return {
          cursorPos,
          lineEndPos: text.indexOf('Second line') + lineText.length
        };
      });

      expect(state.cursorPos).toBe(state.lineEndPos);
    });

    test('Alt+A moves to beginning of block', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('\nLine 2\nLine 3');
      await page.keyboard.press('Alt+a'); // Beginning of BLOCK

      const cursorPos = await page.evaluate(() =>
        window.getSelection()!.getRangeAt(0).startOffset
      );

      expect(cursorPos).toBe(0); // Start of entire block
    });

    test('Alt+E moves to end of block', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('\nLine 2\nLine 3');
      await page.keyboard.press('Home'); // Go to start
      await page.keyboard.press('Alt+e'); // End of BLOCK

      const state = await page.evaluate(() => {
        const text = document.querySelector('[contenteditable]')!.textContent || '';
        const cursorPos = window.getSelection()!.getRangeAt(0).startOffset;
        return { cursorPos, textLength: text.length };
      });

      expect(state.cursorPos).toBe(state.textLength);
    });
  });

  test.describe('Arrow Navigation Edge Cases', () => {
    test('arrow keys collapse selection to start/end', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('select this');

      // Select "this"
      await page.keyboard.press('Shift+Control+ArrowLeft');

      // Left arrow should collapse to START
      await page.keyboard.press('ArrowLeft');
      let pos = await page.evaluate(() =>
        window.getSelection()!.getRangeAt(0).startOffset
      );
      expect(pos).toBeLessThan(10); // At start of "this"

      // Select again
      await page.keyboard.press('Shift+Control+ArrowRight');

      // Right arrow should collapse to END
      await page.keyboard.press('ArrowRight');
      pos = await page.evaluate(() =>
        window.getSelection()!.getRangeAt(0).startOffset
      );
      expect(pos).toBeGreaterThan(10); // After "this"
    });

    test('left arrow at start navigates to previous block at END', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.press('Enter');
      await page.keyboard.type('second block');
      await page.keyboard.press('Home'); // Start of second block
      await page.keyboard.press('ArrowLeft'); // Navigate to previous

      const state = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]') as HTMLElement;
        const text = el.textContent || '';
        const cursorPos = window.getSelection()!.getRangeAt(0).startOffset;
        return { text, cursorPos, atEnd: cursorPos === text.length };
      });

      expect(state.atEnd).toBe(true); // Should be at END of previous block
    });

    test('right arrow at end navigates to next block at START', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.press('Enter');
      await page.keyboard.type('second block');
      await page.keyboard.press('ArrowUp'); // Back to first
      await page.keyboard.press('End');
      await page.keyboard.press('ArrowRight'); // Navigate to next

      const cursorPos = await page.evaluate(() =>
        window.getSelection()!.getRangeAt(0).startOffset
      );

      expect(cursorPos).toBe(0); // Should be at START of next block
    });
  });

  test.describe('Selection Direction Tracking', () => {
    test('Shift+Down extends selection downward incrementally', async ({ page }) => {
      // Select first block
      await page.getByText('Tech Stack').click();

      // Extend down 2 blocks
      await page.keyboard.press('Shift+ArrowDown');
      await page.keyboard.press('Shift+ArrowDown');

      const selected = await page.evaluate(() =>
        Array.from(document.querySelectorAll('[data-block-id]'))
          .filter(el => {
            const style = window.getComputedStyle(el);
            return style.backgroundColor !== 'transparent';
          }).length
      );

      expect(selected).toBeGreaterThanOrEqual(2); // Should have extended
    });

    test('Shift+Up contracts selection when going backward', async ({ page }) => {
      // Select first block
      await page.getByText('Tech Stack').click();

      // Extend down 3 blocks
      await page.keyboard.press('Shift+ArrowDown');
      await page.keyboard.press('Shift+ArrowDown');
      await page.keyboard.press('Shift+ArrowDown');

      // Contract by going up 1
      await page.keyboard.press('Shift+ArrowUp');

      const selected = await page.evaluate(() =>
        Array.from(document.querySelectorAll('[data-block-id]'))
          .filter(el => {
            const style = window.getComputedStyle(el);
            return style.backgroundColor !== 'transparent';
          }).length
      );

      expect(selected).toBeLessThan(4); // Should have contracted
    });

    test('plain arrow with selection replaces with adjacent block', async ({ page }) => {
      // Select first block
      await page.getByText('Tech Stack').click();

      // Extend down 2 blocks
      await page.keyboard.press('Shift+ArrowDown');
      await page.keyboard.press('Shift+ArrowDown');

      // Plain down arrow should REPLACE selection
      await page.keyboard.press('ArrowDown');

      const selected = await page.evaluate(() =>
        Array.from(document.querySelectorAll('[data-block-id]'))
          .filter(el => {
            const style = window.getComputedStyle(el);
            return style.backgroundColor !== 'transparent';
          }).length
      );

      expect(selected).toBe(1); // Should only have 1 selected now
    });
  });

  test.describe('Unicode and Edge Cases', () => {
    test('emoji cursor positioning works correctly', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('🔥🚀💡');

      // Navigate through emoji
      await page.keyboard.press('ArrowLeft');
      await page.keyboard.press('ArrowLeft');

      const pos = await page.evaluate(() =>
        window.getSelection()!.getRangeAt(0).startOffset
      );

      // Emoji are multi-byte, cursor should handle correctly
      expect(pos).toBeGreaterThan(0);
    });

    test('RTL text navigation', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('العربية');

      // Arrow keys should work
      await page.keyboard.press('ArrowLeft');
      await page.keyboard.press('ArrowLeft');

      const text = await page.evaluate(() =>
        document.querySelector('[contenteditable]')!.textContent
      );

      expect(text).toContain('العربية');
    });

    test('very long text block navigation', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');

      const longText = 'x'.repeat(1000);
      await page.evaluate((text) => {
        const el = document.querySelector('[contenteditable]') as HTMLElement;
        el.textContent = text;
      }, longText);

      // Navigate to end
      await page.keyboard.press('End');
      const pos = await page.evaluate(() =>
        window.getSelection()!.getRangeAt(0).startOffset
      );

      expect(pos).toBe(1000);
    });

    test('empty block edge cases', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.press('Enter');

      // Empty block should be editable
      await page.keyboard.type('x');
      const text = await page.evaluate(() =>
        document.querySelector('[contenteditable]')!.textContent
      );

      expect(text).toBe('x');
    });

    test('multi-line block with Shift+Enter', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');

      // Create 3-line block
      await page.keyboard.type('Line 1');
      await page.keyboard.press('Shift+Enter');
      await page.keyboard.type('Line 2');
      await page.keyboard.press('Shift+Enter');
      await page.keyboard.type('Line 3');

      // Navigate up/down should detect rows correctly
      await page.keyboard.press('End');
      await page.keyboard.press('ArrowUp'); // Should stay in block

      const stillInBlock = await page.evaluate(() => {
        const el = document.querySelector('[contenteditable]') as HTMLElement;
        return document.activeElement === el;
      });

      expect(stillInBlock).toBe(true);
    });
  });

  test.describe('Boundary Behavior Edge Cases', () => {
    test('Shift+Arrow at editing boundary extends block selection', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('Line 1');
      await page.keyboard.press('Shift+Enter');
      await page.keyboard.type('Line 2');

      // Cursor at end of multi-line block
      await page.keyboard.press('End');

      // Shift+Down should extend block selection
      await page.keyboard.press('Shift+ArrowDown');

      const selectedCount = await page.evaluate(() =>
        Array.from(document.querySelectorAll('[data-block-id]'))
          .filter(el => {
            const style = window.getComputedStyle(el);
            return style.backgroundColor !== 'transparent';
          }).length
      );

      expect(selectedCount).toBeGreaterThan(0);
    });

    test('Backspace at start of empty block deletes and navigates', async ({ page }) => {
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.press('Enter');

      // Now in empty block, backspace should delete it
      await page.keyboard.press('Backspace');

      const editableCount = await page.evaluate(() =>
        document.querySelectorAll('[contenteditable]').length
      );

      // Should have navigated back to previous block
      expect(editableCount).toBeGreaterThan(0);
    });

    test('Delete at end with children merges child-first', async ({ page }) => {
      // Create parent with child
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('End');
      await page.keyboard.type('Parent');
      await page.keyboard.press('Enter');
      await page.keyboard.press('Tab'); // Create child
      await page.keyboard.type('Child');

      // Go back to parent, to end
      await page.keyboard.press('ArrowUp');
      await page.keyboard.press('End');

      // Delete should merge with child
      await page.keyboard.press('Delete');

      const text = await page.evaluate(() =>
        document.querySelector('[contenteditable]')!.textContent
      );

      expect(text).toContain('Parent');
      expect(text).toContain('Child');
    });
  });
});
