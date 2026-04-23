// @ts-check
import { expect, test } from '@playwright/test';
import { exitEditMode, getFirstBlockId, waitForBlocks } from './helpers/index.js';

/**
 * Write-side Tripwire E2E
 *
 * Proves the kernel.text-validation tripwire actually rejects corrupt
 * text at the transaction boundary — the single architectural guarantee
 * that no matter WHICH intent path writes text (blur commit, paste,
 * autocomplete expansion, future code), MathJax glyph bytes, embedded
 * scanner markup, and control chars cannot persist into the DB.
 *
 * These tests dispatch `:update-content` directly rather than typing in
 * the contenteditable, because the current parser + math-ignore contract
 * already prevents a user-typing-based repro. The tripwire is here for
 * the class of bugs where the contract regresses — a missing math-ignore
 * class, a new DOM scanner added without the opt-out, a paste handler
 * reading textContent after MathJax has mutated it.
 */

test.describe('Write-side tripwire — transaction layer', () => {
  let blockId;

  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await waitForBlocks(page);
    blockId = await getFirstBlockId(page);
    await exitEditMode(page);
    // Establish a known-clean baseline.
    await page.evaluate((id) => {
      window.TEST_HELPERS?.dispatchIntent({
        type: 'update-content',
        'block-id': id,
        text: 'baseline',
      });
    }, blockId);
    await page.waitForTimeout(80);
  });

  // Dispatch an :update-content intent whose text is built inside the
  // page context — passing corrupt bytes through Playwright's
  // page.evaluate argument serialization strips or replaces certain
  // control chars before they reach the kernel. Building the string in
  // the browser guarantees the tripwire sees the raw bytes.
  async function dispatchCorruptText(page, id, codepoint, wrap) {
    return page.evaluate(
      ({ id, codepoint, wrap }) => {
        const corrupt = wrap
          ? wrap.replace('__CH__', String.fromCodePoint(codepoint))
          : String.fromCodePoint(codepoint);
        window.TEST_HELPERS?.dispatchIntent({
          type: 'update-content',
          'block-id': id,
          text: corrupt,
        });
        return corrupt;
      },
      { id, codepoint, wrap }
    );
  }

  async function dispatchLiteralText(page, id, text) {
    return page.evaluate(
      ({ id, text }) => {
        window.TEST_HELPERS?.dispatchIntent({
          type: 'update-content',
          'block-id': id,
          text,
        });
      },
      { id, text }
    );
  }

  test('private-use-area glyph is rejected; DB text unchanged', async ({ page }) => {
    const sent = await dispatchCorruptText(page, blockId, 0xe001, 'hello __CH__ world');
    await page.waitForTimeout(100);
    const dbText = await page.evaluate((id) => window.TEST_HELPERS?.getBlockText(id), blockId);
    // Sanity: the browser did build the corrupt string — if this fails
    // the test itself is broken, not the tripwire.
    expect(sent).toContain('\uE001');
    expect(dbText, 'tripwire must have blocked the private-use-char write').toBe('baseline');
  });

  test('<mjx-container> markup in text is rejected', async ({ page }) => {
    await dispatchLiteralText(page, blockId, 'hello <mjx-container>stuff</mjx-container>');
    await page.waitForTimeout(100);
    const dbText = await page.evaluate((id) => window.TEST_HELPERS?.getBlockText(id), blockId);
    expect(dbText).toBe('baseline');
  });

  test('control character (NUL) is rejected', async ({ page }) => {
    const sent = await dispatchCorruptText(page, blockId, 0x00, 'x__CH__y');
    await page.waitForTimeout(100);
    const dbText = await page.evaluate((id) => window.TEST_HELPERS?.getBlockText(id), blockId);
    expect(sent).toContain('\u0000');
    expect(dbText).toBe('baseline');
  });

  test('legitimate math, unicode, code-like text still writes cleanly', async ({ page }) => {
    const clean = 'cljs$core$key(map_entry) — $x^2$ — привет_мир';
    await page.evaluate(
      ({ id, text }) => {
        window.TEST_HELPERS?.dispatchIntent({
          type: 'update-content',
          'block-id': id,
          text,
        });
      },
      { id: blockId, text: clean }
    );
    await page.waitForTimeout(100);

    const dbText = await page.evaluate((id) => window.TEST_HELPERS?.getBlockText(id), blockId);
    expect(dbText).toBe(clean);
  });
});
