// @ts-check
import { expect, test } from '@playwright/test';
import { enterEditModeAndClick } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * Text Formatting E2E Tests
 *
 * Tests inline text formatting (bold, italic) with focus on:
 * - Basic formatting toggle
 * - Whitespace trimming (Logseq parity)
 * - Selection handling after format
 *
 * NOTE: Text must be committed to DB before format-selection can read it.
 * We use dispatchIntent to commit text, then format.
 */

test.describe('Inline Text Formatting', () => {
  let blockId;

  test.beforeEach(async ({ page }) => {
    // Use test mode for clean state
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);

    // Get the block ID for later use
    blockId = await page.evaluate(() => {
      return window.TEST_HELPERS?.getSession()?.ui?.['editing-block-id'];
    });
  });

  /**
   * Helper to set text and create a selection, then format
   */
  async function setTextSelectAndFormat(page, text, startOffset, endOffset, marker) {
    // Commit text to DB first (format-selection reads from DB)
    await page.evaluate(
      ({ bid, txt }) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-content',
          'block-id': bid,
          text: txt,
        });
      },
      { bid: blockId, txt: text }
    );
    await wait(page);

    // Format the selection
    await page.evaluate(
      ({ bid, start, end, mkr }) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'format-selection',
          'block-id': bid,
          start: start,
          end: end,
          marker: mkr,
        });
      },
      { bid: blockId, start: startOffset, end: endOffset, mkr: marker }
    );
    await wait(page);

    // Get the result from DB
    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    return result;
  }

  test.describe('Bold Formatting (Cmd+B)', () => {
    test('wraps selected text with ** markers', async ({ page }) => {
      const result = await setTextSelectAndFormat(page, 'hello world', 0, 5, '**');
      expect(result).toBe('**hello** world');
    });

    test('unwraps already-bolded text', async ({ page }) => {
      const result = await setTextSelectAndFormat(page, '**hello** world', 0, 9, '**');
      expect(result).toBe('hello world');
    });
  });

  test.describe('Whitespace Trimming (Logseq Parity)', () => {
    test('trims leading whitespace before formatting', async ({ page }) => {
      // Select "  hello" (including leading spaces)
      const result = await setTextSelectAndFormat(page, '  hello world', 0, 7, '**');
      // Should format "hello" not "  hello" - spaces preserved outside markers
      expect(result).toBe('  **hello** world');
    });

    test('trims trailing whitespace before formatting', async ({ page }) => {
      // Select "hello  " (including trailing spaces)
      const result = await setTextSelectAndFormat(page, 'hello  world', 0, 7, '**');
      // Should format "hello" not "hello  " - spaces preserved outside markers
      expect(result).toBe('**hello**  world');
    });

    test('trims both leading and trailing whitespace', async ({ page }) => {
      // Select "  bold text  " (with spaces on both sides)
      const result = await setTextSelectAndFormat(page, 'x  bold text  y', 1, 14, '**');
      // Should format "bold text" with spaces outside
      expect(result).toBe('x  **bold text**  y');
    });
  });

  test.describe('Italic Formatting (Cmd+I)', () => {
    test('wraps selected text with * markers (Logseq parity)', async ({ page }) => {
      const result = await setTextSelectAndFormat(page, 'hello world', 0, 5, '*');
      expect(result).toBe('*hello* world');
    });

    test('italic also trims whitespace', async ({ page }) => {
      // Select "  something  " with surrounding whitespace
      const result = await setTextSelectAndFormat(page, 'say  something  here', 3, 15, '*');
      expect(result).toBe('say  *something*  here');
    });
  });

  test.describe('Highlight Formatting (Cmd+Shift+H)', () => {
    test('wraps selected text with == markers (Logseq parity)', async ({ page }) => {
      const result = await setTextSelectAndFormat(page, 'highlight this', 0, 9, '==');
      expect(result).toBe('==highlight== this');
    });
  });

  test.describe('Strikethrough Formatting (Cmd+Shift+S)', () => {
    test('wraps selected text with ~~ markers', async ({ page }) => {
      const result = await setTextSelectAndFormat(page, 'strike this', 0, 6, '~~');
      expect(result).toBe('~~strike~~ this');
    });
  });

  test.describe('Keyboard Shortcut Integration', () => {
    /**
     * Helper to type text, select a range, and press a key combo
     */
    async function typeSelectAndPress(page, text, selectStart, selectEnd, keyCombo) {
      // Type text directly into contenteditable
      await page.click('[contenteditable="true"]');
      await page.keyboard.type(text);
      await wait(page, 100);

      // Commit text to DB explicitly
      await page.evaluate((bid) => {
        const editable = document.querySelector('[contenteditable="true"]');
        if (editable) {
          window.TEST_HELPERS.dispatchIntent({
            type: 'update-content',
            'block-id': bid,
            text: editable.textContent,
          });
        }
      }, blockId);
      await wait(page);

      // Create selection
      await page.evaluate(
        ({ start, end }) => {
          const editable = document.querySelector('[contenteditable="true"]');
          if (editable?.firstChild) {
            editable.focus();
            const range = document.createRange();
            range.setStart(editable.firstChild, start);
            range.setEnd(editable.firstChild, end);
            const selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
          }
        },
        { start: selectStart, end: selectEnd }
      );
      await wait(page);

      // Press the key combo
      await page.keyboard.press(keyCombo);
      await wait(page, 200);

      // Get result from DB
      return await page.evaluate((bid) => {
        const db = window.DEBUG.getDb();
        return db?.nodes?.[bid]?.props?.text;
      }, blockId);
    }

    test('Cmd+B with DOM selection formats text as bold', async ({ page }) => {
      const result = await typeSelectAndPress(page, 'keyboard test', 0, 8, 'Meta+b');
      expect(result).toBe('**keyboard** test');
    });

    test('Cmd+I with DOM selection formats text as italic', async ({ page }) => {
      const result = await typeSelectAndPress(page, 'italic word', 0, 6, 'Meta+i');
      expect(result).toBe('*italic* word');
    });

    test('Cmd+Shift+H with DOM selection formats text as highlight', async ({ page }) => {
      const result = await typeSelectAndPress(page, 'highlight me', 0, 9, 'Meta+Shift+h');
      expect(result).toBe('==highlight== me');
    });

    test('Cmd+Shift+S with DOM selection formats text as strikethrough', async ({ page }) => {
      const result = await typeSelectAndPress(page, 'strike out', 0, 6, 'Meta+Shift+s');
      expect(result).toBe('~~strike~~ out');
    });
  });
});
