// @ts-check
import { expect, test } from '@playwright/test';
import {
  enterEditMode,
  getBlockText,
  getFirstBlockId,
  updateBlockText,
  waitForBlocks,
} from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * Paste URL Wrap E2E
 *
 * When the clipboard holds a bare URL and the user has a non-empty
 * selection, paste wraps the selection as a markdown link:
 * `[selected](url)` instead of replacing the selection with the URL.
 *
 * Matches the plan §5 Tier 0.1 guarantee.
 */

async function dispatchPaste(page, text) {
  await page.evaluate((clip) => {
    const el = document.querySelector('[contenteditable="true"]');
    if (!el) throw new Error('no contenteditable');
    const evt = new ClipboardEvent('paste', {
      bubbles: true,
      cancelable: true,
      clipboardData: new DataTransfer(),
    });
    evt.clipboardData.setData('text/plain', clip);
    el.dispatchEvent(evt);
  }, text);
}

async function selectRange(page, start, end) {
  await page.evaluate(
    ({ s, e }) => {
      const el = document.querySelector('[contenteditable="true"]');
      if (!el) throw new Error('no contenteditable');
      // Walk to the first text descendant
      const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
      const textNode = walker.nextNode();
      if (!textNode) throw new Error('no text node');
      const sel = window.getSelection();
      const range = document.createRange();
      const maxLen = textNode.data.length;
      range.setStart(textNode, Math.min(s, maxLen));
      range.setEnd(textNode, Math.min(e, maxLen));
      sel.removeAllRanges();
      sel.addRange(range);
    },
    { s: start, e: end }
  );
}

async function placeCursorAtEnd(page) {
  await page.evaluate(() => {
    const el = document.querySelector('[contenteditable="true"]');
    if (!el) throw new Error('no contenteditable');
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    const textNode = walker.nextNode();
    if (!textNode) throw new Error('no text node');
    const sel = window.getSelection();
    const range = document.createRange();
    range.setStart(textNode, textNode.data.length);
    range.setEnd(textNode, textNode.data.length);
    sel.removeAllRanges();
    sel.addRange(range);
  });
}

test.describe('Paste URL wrap', () => {
  let blockId;

  test.beforeEach(async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await waitForBlocks(page);
    blockId = await getFirstBlockId(page);
  });

  test('wraps selection as [label](url) when clipboard is a bare URL', async ({ page }) => {
    await updateBlockText(page, blockId, 'hello world');
    await enterEditMode(page, blockId);
    await selectRange(page, 6, 11); // "world"

    await dispatchPaste(page, 'https://example.com');
    await wait(page, 200);

    const text = await getBlockText(page, blockId);
    expect(text).toBe('hello [world](https://example.com)');
  });

  test('collapsed selection → URL replaces (simple inline paste)', async ({ page }) => {
    await updateBlockText(page, blockId, 'hi ');
    await enterEditMode(page, blockId);
    await placeCursorAtEnd(page);

    await dispatchPaste(page, 'https://example.com');
    await wait(page, 200);

    const text = await getBlockText(page, blockId);
    expect(text).toBe('hi https://example.com');
  });

  test('non-URL clipboard with selection → replace, no wrap', async ({ page }) => {
    await updateBlockText(page, blockId, 'hello world');
    await enterEditMode(page, blockId);
    await selectRange(page, 6, 11); // "world"

    await dispatchPaste(page, 'friends');
    await wait(page, 200);

    const text = await getBlockText(page, blockId);
    expect(text).toBe('hello friends');
  });
});
