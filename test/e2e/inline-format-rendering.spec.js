// @ts-check
import { test, expect } from '@playwright/test';
import {
  waitForBlocks,
  getFirstBlockId,
  updateBlockText,
  exitEditMode
} from './helpers/index.js';

/**
 * Inline Format Rendering E2E
 *
 * Guards against the intraword-emphasis regression: pasting code or
 * JS identifiers like `cljs_core_key`, `cljs$core$key`, `a*b*c` used to
 * render part of the string as italic/math because the parser paired any
 * two matching single-char markers. These tests verify realistic inputs
 * stay literal in the DOM (no stray <em>/<strong>/<mark>/<del>/.math).
 */

async function getBlockFormatCounts(page, blockId) {
  return page.evaluate((id) => {
    const root = document.querySelector(`[data-block-id="${id}"]`);
    if (!root) return null;
    const content = root.querySelector('.block-content, [data-block-content], .block-text') || root;
    return {
      text: content.textContent || '',
      em: content.querySelectorAll('em').length,
      strong: content.querySelectorAll('strong').length,
      mark: content.querySelectorAll('mark').length,
      del: content.querySelectorAll('del').length,
      math: content.querySelectorAll('.math').length
    };
  }, blockId);
}

async function setTextAndRead(page, blockId, text) {
  await updateBlockText(page, blockId, text);
  await page.waitForTimeout(80);
  return getBlockFormatCounts(page, blockId);
}

test.describe('Inline format rendering — intraword guard', () => {
  let blockId;

  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await waitForBlocks(page);
    blockId = await getFirstBlockId(page);
    // Ensure view mode so the formatted renderer runs (edit mode is raw).
    await exitEditMode(page);
  });

  test('underscores inside identifiers do not italicize', async ({ page }) => {
    const r = await setTextAndRead(page, blockId, 'cljs._key(map_entry)');
    expect(r).not.toBeNull();
    expect(r.text).toBe('cljs._key(map_entry)');
    expect(r.em).toBe(0);
    expect(r.strong).toBe(0);
  });

  test('JS-style identifier with multiple underscores renders literally', async ({ page }) => {
    const r = await setTextAndRead(
      page,
      blockId,
      'function cljs$core$key(map_entry){return cljs.core._key(map_entry)}'
    );
    expect(r.text).toBe('function cljs$core$key(map_entry){return cljs.core._key(map_entry)}');
    expect(r.em).toBe(0);
    expect(r.math).toBe(0);
    expect(r.strong).toBe(0);
  });

  test('intraword stars (a*b*c) do not italicize', async ({ page }) => {
    const r = await setTextAndRead(page, blockId, 'x = a*b*c');
    expect(r.text).toBe('x = a*b*c');
    expect(r.em).toBe(0);
  });

  test('intraword dollars do not become inline math', async ({ page }) => {
    const r = await setTextAndRead(page, blockId, 'price$100$total');
    expect(r.text).toBe('price$100$total');
    expect(r.math).toBe(0);
  });

  test('bounded markers still format (positive regression)', async ({ page }) => {
    const r = await setTextAndRead(page, blockId, 'hello **world** and *it* now');
    // Replicant strips markers when rendering formatted segments, so textContent
    // shows the inner value; what we care about is the tag counts.
    expect(r.strong).toBe(1);
    expect(r.em).toBe(1);
  });

  test('bounded inline math still renders as math span', async ({ page }) => {
    const r = await setTextAndRead(page, blockId, 'see $x+y$ there');
    expect(r.math).toBe(1);
  });

  test('prose with multiple $ runs does not math-typeset (MathJax regex pin)', async ({ page }) => {
    // This would fail if processHtmlClass matched "math" as a substring —
    // the view container class includes "math-ignore" which contains "math".
    // With \bmath\b anchoring, only the explicit .math class re-enables.
    const r = await setTextAndRead(page, blockId, '$100 then $200 then $300 — three prices');
    expect(r.text).toBe('$100 then $200 then $300 — three prices');
    expect(r.math).toBe(0);
  });
});
