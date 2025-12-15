// @ts-check
import { test, expect } from '@playwright/test';
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
    await page.evaluate(({ bid, txt }) => {
      window.TEST_HELPERS.dispatchIntent({
        type: 'update-content',
        'block-id': bid,
        text: txt
      });
    }, { bid: blockId, txt: text });
    await wait(page);

    // Format the selection
    await page.evaluate(({ bid, start, end, mkr }) => {
      window.TEST_HELPERS.dispatchIntent({
        type: 'format-selection',
        'block-id': bid,
        start: start,
        end: end,
        marker: mkr
      });
    }, { bid: blockId, start: startOffset, end: endOffset, mkr: marker });
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
    test('wraps selected text with __ markers', async ({ page }) => {
      const result = await setTextSelectAndFormat(page, 'hello world', 0, 5, '__');
      expect(result).toBe('__hello__ world');
    });

    test('italic also trims whitespace', async ({ page }) => {
      // Select "  something  " with surrounding whitespace
      const result = await setTextSelectAndFormat(page, 'say  something  here', 3, 15, '__');
      expect(result).toBe('say  __something__  here');
    });
  });

  test.describe('Keyboard Shortcut Integration', () => {
    test('Cmd+B with DOM selection formats text', async ({ page }) => {
      // Type text directly into contenteditable (so DOM and DB are in sync)
      await page.click('[contenteditable="true"]');
      await page.keyboard.type('keyboard test');
      await wait(page, 100);

      // Commit text to DB explicitly
      await page.evaluate((bid) => {
        const editable = document.querySelector('[contenteditable="true"]');
        if (editable) {
          window.TEST_HELPERS.dispatchIntent({
            type: 'update-content',
            'block-id': bid,
            text: editable.textContent
          });
        }
      }, blockId);
      await wait(page);

      // Create a selection for "keyboard"
      await page.evaluate(() => {
        const editable = document.querySelector('[contenteditable="true"]');
        if (editable && editable.firstChild) {
          editable.focus();
          const range = document.createRange();
          range.setStart(editable.firstChild, 0);
          range.setEnd(editable.firstChild, 8); // "keyboard"
          const selection = window.getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
        }
      });
      await wait(page);

      // Press Cmd+B
      await page.keyboard.press('Meta+b');
      await wait(page, 200);

      // Check result
      const result = await page.evaluate((bid) => {
        const db = window.DEBUG.getDb();
        return db?.nodes?.[bid]?.props?.text;
      }, blockId);

      expect(result).toBe('**keyboard** test');
    });
  });
});
