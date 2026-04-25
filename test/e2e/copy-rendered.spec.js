// @ts-check
import { expect, test } from '@playwright/test';
import { exitEditMode, getFirstBlockId, updateBlockText, waitForBlocks } from './helpers/index.js';

/**
 * Phase 4 probe: when rendered **bold** / *italic* / $math$ is partially
 * selected in view mode and Cmd/Ctrl+C is pressed, does the browser-
 * native clipboard preserve the markdown markers, or does it capture
 * only the visible textContent?
 *
 * The question matters because the app's :copy-block intent uses DB
 * text (markers preserved), but native copy of a substring inside a
 * rendered block falls through to the browser.
 *
 * Result determines whether Phase 4 builds hidden-marker render spans
 * or ships a "preview copy is intentionally lossy" note instead.
 */

test.describe('Phase 4 probe: native copy of rendered formatting', () => {
  let blockId;

  test.beforeEach(async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await waitForBlocks(page);
    blockId = await getFirstBlockId(page);
    await exitEditMode(page);
  });

  async function selectFullBlockAndCopy(page) {
    return page.evaluate(
      ({ id }) => {
        const root = document.querySelector(`[data-block-id="${id}"] .block-content`);
        if (!root) return { err: 'no-block-content', textContent: null, selection: null };
        const textContent = root.textContent;
        const range = document.createRange();
        range.selectNodeContents(root);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        return { err: null, textContent, selection: sel.toString() };
      },
      { id: blockId }
    );
  }

  test('rendered **bold** preserves ** markers in textContent for copy', async ({ page }) => {
    await updateBlockText(page, blockId, 'say **hello** friend');
    await page.waitForTimeout(120);
    const r = await selectFullBlockAndCopy(page);
    expect(r.err).toBeNull();
    expect(r.textContent).toBe('say **hello** friend');
  });

  test('rendered *italic* preserves _ markers in textContent for copy', async ({ page }) => {
    await updateBlockText(page, blockId, 'hello _italic_ world');
    await page.waitForTimeout(120);
    const r = await selectFullBlockAndCopy(page);
    expect(r.err).toBeNull();
    expect(r.textContent).toBe('hello _italic_ world');
  });

  test('rendered ==highlight== preserves markers', async ({ page }) => {
    await updateBlockText(page, blockId, 'some ==marked== text');
    await page.waitForTimeout(120);
    const r = await selectFullBlockAndCopy(page);
    expect(r.err).toBeNull();
    expect(r.textContent).toBe('some ==marked== text');
  });

  test('rendered ~~strike~~ preserves markers', async ({ page }) => {
    await updateBlockText(page, blockId, 'this ~~removed~~ here');
    await page.waitForTimeout(120);
    const r = await selectFullBlockAndCopy(page);
    expect(r.err).toBeNull();
    expect(r.textContent).toBe('this ~~removed~~ here');
  });

  test('rendered math intentionally does not carry marker spans', async ({ page }) => {
    // Math is typeset by MathJax into glyph nodes; we document that preview
    // copy of math is lossy (architectural choice, see Phase 4 plan).
    await updateBlockText(page, blockId, 'see $x+y$ there');
    await page.waitForTimeout(400);
    const r = await selectFullBlockAndCopy(page);
    expect(r.err).toBeNull();
    // textContent may or may not include $ depending on MathJax state;
    // we only assert the surrounding prose survived.
    expect(r.textContent).toContain('see ');
    expect(r.textContent).toContain('there');
  });
});
