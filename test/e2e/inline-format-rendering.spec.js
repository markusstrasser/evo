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

/**
 * Round-trip property: any input the parser rejects as formatting MUST
 * render with `textContent === input` (modulo hidden marker spans on
 * genuinely-formatted runs). Catches the whole class of "parser says X,
 * downstream renderer mutates DOM, textContent no longer equals DB text"
 * bugs — including MathJax typesetting code-like `$...$` spans, CSS
 * content: hacks eating punctuation, and any future global DOM scanner
 * added without respecting `math-ignore`.
 *
 * When adding new inline syntax (backticks, autolinks, emoji, etc.),
 * append a row rather than writing a new spec file.
 */

// textContent must match input byte-for-byte after render. Covers both
// inputs the parser leaves as :text AND inputs that format (the marker-
// span machinery preserves the exact marker char the user typed, so
// `*italic*` round-trips as `*italic*`, not `_italic_`).
const ROUND_TRIP_CORPUS = [
  // ── CLJS/JS identifiers with special chars ─────────────────────────
  'cljs.core._key(map_entry)',
  'cljs$core$key(map_entry)',
  'function cljs$core$key(map_entry){return cljs.core._key(map_entry);}',
  'asdadwad $key(map_entry){return cljs.core._key(map_entry);}$',
  'foo_bar_baz and foo__bar__baz',
  '__init__ and __main__',
  '**kwargs, *args',
  // ── Formatted prose (exercises marker-char preservation) ──────────
  'this is *italic* text',
  'this is _italic_ text',
  'this is **bold** text',
  'this is __bold__ text',
  'über_cool und auch *nicht* kursiv hier',
  // ── Shell / code snippets ─────────────────────────────────────────
  'echo "hello $USER" > /tmp/x',
  'for (let i=0; i<10; i++) { sum += arr[i]; }',
  'SELECT * FROM users WHERE id = $1;',
  'grep -E "\\bfoo\\b" file.txt',
  // ── Currency and numeric prose ────────────────────────────────────
  '$100 then $200 then $300 — three prices',
  'costs $5.99 or £4.80',
  'revenue of $1.2B',
  'price$100$total',
  // ── Arithmetic / equality in prose ────────────────────────────────
  'x = a * b * c',
  '2*3*4 = 24',
  'a==b==c',
  'assert x == y == z',
  // ── Mixed marker fragments that should stay literal ───────────────
  'unbalanced *star here',
  'unbalanced _underscore',
  'trailing $ sign alone',
  // ── Code-like $...$ content (the core bug this spec guards) ───────
  '$see [[Some Page]]$',
  '$let x = 1; return x;$',
  '$$function f(x){return 2*x}$$',
  // ── Unicode identifiers ───────────────────────────────────────────
  'привет_мир_тест',
  '価格$合計$円',
  // ── Realistic user prose ──────────────────────────────────────────
  'TODO: refactor the parser — too many edge cases!',
  'Meeting @ 3pm w/ team re: Q2 goals',
  'See also: commit a2557236 [ui] Mark view block-content as math-ignore',
];

test.describe('Inline format rendering — round-trip property', () => {
  let blockId;

  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await waitForBlocks(page);
    blockId = await getFirstBlockId(page);
    await exitEditMode(page);
  });

  for (const input of ROUND_TRIP_CORPUS) {
    test(`textContent round-trips: ${JSON.stringify(input).slice(0, 60)}`, async ({ page }) => {
      const r = await setTextAndRead(page, blockId, input);
      expect(r).not.toBeNull();
      // MathJax must not have produced .math spans for any of these inputs.
      expect(r.math, `input produced unexpected math span: ${input}`).toBe(0);
      // textContent must match the input verbatim — the whole point of the
      // invariant. Hidden marker spans contribute their chars to textContent,
      // so for the formatted cases we'd see marker text; this corpus is
      // intentionally restricted to inputs the parser must leave literal.
      expect(r.text, `DOM textContent diverged from DB text for: ${input}`).toBe(input);
    });
  }
});
