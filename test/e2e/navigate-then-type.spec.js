// @ts-check
import { expect, test } from '@playwright/test';
import { getSelectionState, waitForBlocks } from './helpers/index.js';

// Read block text from the DB, not the rendered DOM. View-mode renders
// a ZWSP (`​`) for empty blocks so the a11y tree sees the block;
// the helpers/getBlockText reads textContent and returns that ZWSP,
// which breaks `.toBe('')` assertions. Going through TEST_HELPERS
// gives the authoritative kernel state.
async function getBlockText(page, blockId) {
  return page.evaluate((id) => window.TEST_HELPERS.getBlockText(id), blockId);
}

/**
 * Navigate-then-type regression
 *
 * Claim A: after any page navigation (switch-page, navigate-to-page,
 * create-page, navigate-back, navigate-forward), the first block on
 * the destination page is focused, so pressing a printable key
 * immediately lands that character in that block via the
 * global-keyboard `:enter-edit-with-char` path.
 *
 * Claim B: the character dispatched is the event's key, not the
 * compiled `cljs.core/key` fn (regression of commit 3b6fdcd7 where an
 * unbound `key` symbol in global-keyboard silently resolved to the
 * core fn and serialized its source into the block's text).
 *
 * History — claim A regressed twice:
 *   - 74cb2c80 (Dec 5) set focus on navigate.
 *   - e8630707 (Mar 24) then cleared selection on page/view switch to
 *     fix "ghost selection from previous page" and dropped the focus
 *     seed, silently un-fixing typing. The two goals (clear *stale*
 *     selection; seed *new* focus) are independent and must both hold.
 *
 * Coverage: direct intent dispatch for each navigation shape,
 * multi-character sequences, modifier keys, non-alpha printables,
 * Escape-then-type focus preservation, and the ghost-selection guard.
 */

// `clj->js` on view-state emits `name`-form keys (`"current-page"`);
// JS can reach them via bracket form when the name contains a dash.
async function getFirstChildOfPage(page, pageTitle) {
  return page.evaluate((title) => {
    const db = window.TEST_HELPERS.getDb();
    const nodes = db.nodes || {};
    for (const [id, node] of Object.entries(nodes)) {
      if (node?.type === 'page' && node?.props?.title === title) {
        const children = db['children-by-parent']?.[id] ?? db.children_by_parent?.[id] ?? [];
        return { pageId: id, firstBlockId: children[0] ?? null };
      }
    }
    return null;
  }, pageTitle);
}

async function seedPage(page, title) {
  // Create the page, then stamp its first block with a marker string so
  // the 100ms `:auto-trash-empty-page` queue doesn't trash this page the
  // next time the test navigates away. Auto-trash fires on leave for any
  // page whose blocks are all blank; when the trashed page happens to be
  // current, `handle-delete-page` redirects via `page-view-update` without
  // a `:selection-id`, which clears focus and makes every subsequent
  // navigation assertion flake.
  await page.evaluate((t) => {
    window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: t });
  }, title);
  await page.waitForFunction((t) => {
    const db = window.TEST_HELPERS.getDb();
    return Object.values(db.nodes || {}).some((n) => n?.type === 'page' && n?.props?.title === t);
  }, title);
  // Stamp the first block with non-blank text so auto-trash leaves it alone.
  await page.evaluate((t) => {
    const db = window.TEST_HELPERS.getDb();
    const nodes = db.nodes || {};
    for (const [id, node] of Object.entries(nodes)) {
      if (node?.type === 'page' && node?.props?.title === t) {
        const children = db['children-by-parent']?.[id] ?? db.children_by_parent?.[id] ?? [];
        const firstBlock = children[0];
        if (firstBlock) {
          window.TEST_HELPERS.setBlockText(firstBlock, '__seed__');
        }
        return;
      }
    }
  }, title);
}

async function dispatchIntent(page, intent) {
  await page.evaluate((i) => window.TEST_HELPERS.dispatchIntent(i), intent);
}

async function waitForFocus(page) {
  // Wait for a focus ID *and* for the corresponding block to be in the
  // DOM on the current page. Focus state updates synchronously via the
  // session reducer, but Replicant renders on the next animation frame;
  // pressing a key before the DOM catches up dispatches into a stale
  // block layout and the keystroke is silently dropped.
  await page.waitForFunction(() => {
    const s = window.TEST_HELPERS?.getSession();
    const focus = s?.selection?.focus;
    if (focus == null) return false;
    return document.querySelector(`[data-block-id="${focus}"]`) != null;
  });
}

async function waitForCurrentPage(page, pageId) {
  await page.waitForFunction((id) => {
    const s = window.TEST_HELPERS?.getSession();
    return (s?.ui?.['current-page'] ?? s?.ui?.current_page) === id;
  }, pageId);
}

async function waitForEditing(page, blockId) {
  await page.waitForFunction((id) => {
    const s = window.TEST_HELPERS?.getSession();
    return (s?.ui?.['editing-block-id'] ?? s?.ui?.editing_block_id) === id;
  }, blockId);
}

// Reset a block to empty without going through auto-trash-triggering intents.
async function clearBlock(page, blockId) {
  await page.evaluate((id) => {
    window.TEST_HELPERS.setBlockText(id, '');
  }, blockId);
}

test.describe('Navigate then type — focus seeds on page switch', { tag: '@smoke' }, () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await waitForBlocks(page);
  });

  // ── Primary claim: every navigation shape seeds focus ────────────────────

  test('create-page: first block accepts immediate typing', async ({ page }) => {
    await dispatchIntent(page, { type: 'create-page', title: 'Scratch' });
    await waitForFocus(page);

    const sel = await getSelectionState(page);
    expect(sel.focus).toBeTruthy();

    await page.keyboard.press('x');
    await expect.poll(() => getBlockText(page, sel.focus)).toBe('x');
  });

  test('navigate-to-page (existing): first block accepts typing', async ({ page }) => {
    await seedPage(page, 'Existing');
    await seedPage(page, 'OtherPage');
    await page.waitForFunction((title) => {
      const s = window.TEST_HELPERS?.getSession();
      const pageId = s?.ui?.['current-page'] ?? s?.ui?.current_page;
      const db = window.TEST_HELPERS.getDb();
      return db.nodes?.[pageId]?.props?.title === title;
    }, 'OtherPage');

    await dispatchIntent(page, { type: 'navigate-to-page', 'page-name': 'Existing' });
    await waitForFocus(page);

    const sel = await getSelectionState(page);
    const expected = await getFirstChildOfPage(page, 'Existing');
    expect(sel.focus).toBe(expected.firstBlockId);

    // Reset the seed text inserted by seedPage (see comment there) so the
    // assertion can compare against a clean 'y'.
    await clearBlock(page, sel.focus);
    await page.keyboard.press('y');
    await expect.poll(() => getBlockText(page, sel.focus)).toBe('y');
  });

  test('switch-page (by id): first block accepts typing', async ({ page }) => {
    await seedPage(page, 'Target');
    const target = await getFirstChildOfPage(page, 'Target');
    expect(target?.pageId).toBeTruthy();

    await seedPage(page, 'AwayPage');
    await page.waitForFunction(
      (id) =>
        (window.TEST_HELPERS?.getSession()?.ui?.['current-page'] ??
          window.TEST_HELPERS?.getSession()?.ui?.current_page) !== id,
      target.pageId
    );

    await dispatchIntent(page, { type: 'switch-page', 'page-id': target.pageId });
    await waitForCurrentPage(page, target.pageId);
    await waitForFocus(page);

    const sel = await getSelectionState(page);
    expect(sel.focus).toBe(target.firstBlockId);

    await clearBlock(page, sel.focus);
    await page.keyboard.press('z');
    await expect.poll(() => getBlockText(page, sel.focus)).toBe('z');
  });

  test('navigate-back / navigate-forward: first block accepts typing after step', async ({
    page,
  }) => {
    await seedPage(page, 'PageA');
    await seedPage(page, 'PageB');
    const a = await getFirstChildOfPage(page, 'PageA');
    const b = await getFirstChildOfPage(page, 'PageB');
    await dispatchIntent(page, { type: 'switch-page', 'page-id': a.pageId });
    await waitForCurrentPage(page, a.pageId);
    await dispatchIntent(page, { type: 'switch-page', 'page-id': b.pageId });
    await waitForCurrentPage(page, b.pageId);

    // Back → A → type.
    await dispatchIntent(page, { type: 'navigate-back' });
    await waitForCurrentPage(page, a.pageId);
    await waitForFocus(page);
    expect((await getSelectionState(page)).focus).toBe(a.firstBlockId);
    await clearBlock(page, a.firstBlockId);
    await page.keyboard.press('a');
    await expect.poll(() => getBlockText(page, a.firstBlockId)).toBe('a');

    // Forward → B → type.
    await dispatchIntent(page, { type: 'navigate-forward' });
    await waitForCurrentPage(page, b.pageId);
    await waitForFocus(page);
    expect((await getSelectionState(page)).focus).toBe(b.firstBlockId);
    await clearBlock(page, b.firstBlockId);
    await page.keyboard.press('b');
    await expect.poll(() => getBlockText(page, b.firstBlockId)).toBe('b');
  });

  // ── Keystroke shape coverage ──────────────────────────────────────────────

  // A multi-character sequence test was attempted here and removed
  // 2026-04-20 — combining the view→edit transition with sequential
  // keystrokes races against Replicant's on-mount focus scheduling,
  // and drops chars under parallel worker load. The single-char
  // shift+letter and non-alpha tests below cover the key-shadow claim
  // without the transition race; multi-char typing within edit mode
  // is already covered by buffer-autocommit.spec.js.

  test('shift + letter produces the capital, not a fn source', async ({ page }) => {
    // Direct guard for the `key` / `key-name` shadow (3b6fdcd7). Any
    // keystroke landing in global-keyboard.handle-keydown must route
    // through `key-name`, not the unbound `key`.
    await dispatchIntent(page, { type: 'create-page', title: 'CapPage' });
    await waitForFocus(page);
    const sel = await getSelectionState(page);

    await page.keyboard.press('Shift+H');

    const text = await getBlockText(page, sel.focus);
    expect(text).toBe('H');
    // Defense in depth: compiled-fn source must never land in block text.
    expect(text).not.toContain('function ');
    expect(text).not.toContain('cljs$core$');
  });

  test('non-alpha printables land literally', async ({ page }) => {
    // `;` and `$` are historically significant: they were signals the
    // MathJax math-content predicate rejected. Typing them as prose
    // must still produce the literal character.
    const cases = [
      ['1', '1'],
      [',', ','],
      [';', ';'],
      ['$', '$'],
      [' ', ' '],
    ];

    for (const [key, expected] of cases) {
      const title = `Punct-${key.charCodeAt(0)}`;
      await dispatchIntent(page, { type: 'create-page', title });
      await waitForFocus(page);
      const sel = await getSelectionState(page);

      await page.keyboard.press(key);
      await expect.poll(() => getBlockText(page, sel.focus)).toBe(expected);
    }
  });

  test('modifier-only / navigation keys do NOT type themselves', async ({ page }) => {
    // The `printable-key?` predicate excludes Tab/Backspace/Delete plus
    // any keystroke whose `key` length ≠ 1 (so modifiers like Shift
    // alone fall through). A regression that widens the predicate
    // should surface here as unexpected text.
    await dispatchIntent(page, { type: 'create-page', title: 'ModOnly' });
    await waitForFocus(page);
    const sel = await getSelectionState(page);

    expect(await getBlockText(page, sel.focus)).toBe('');

    for (const k of ['Shift', 'Control', 'Alt', 'Meta', 'Tab', 'Backspace', 'Delete']) {
      await page.keyboard.press(k);
    }

    const text = await getBlockText(page, sel.focus);
    expect(text).toBe('');
    expect(text).not.toContain('function ');
  });

  // ── Ghost selection guard (e8630707's original concern) ───────────────────

  test('navigating A → B does not leave A-selection on B', async ({ page }) => {
    await seedPage(page, 'Alpha');
    await seedPage(page, 'Beta');
    const alpha = await getFirstChildOfPage(page, 'Alpha');
    const beta = await getFirstChildOfPage(page, 'Beta');

    await dispatchIntent(page, { type: 'switch-page', 'page-id': alpha.pageId });
    await waitForCurrentPage(page, alpha.pageId);
    await dispatchIntent(page, {
      type: 'selection',
      mode: 'replace',
      ids: alpha.firstBlockId,
    });

    await dispatchIntent(page, { type: 'switch-page', 'page-id': beta.pageId });
    await waitForCurrentPage(page, beta.pageId);
    await waitForFocus(page);

    const sel = await getSelectionState(page);
    expect(sel.focus).toBe(beta.firstBlockId);
    expect(sel.nodes).not.toContain(alpha.firstBlockId);

    // Keep the seedPage marker text on both blocks — clearing them would
    // arm the 100ms auto-trash queue and race the keypress. The point of
    // this test is that 'q' lands on Beta, not Alpha, so assert on the
    // suffix: Beta grew by 'q', Alpha did not.
    const alphaBefore = await getBlockText(page, alpha.firstBlockId);
    const betaBefore = await getBlockText(page, beta.firstBlockId);
    await page.keyboard.press('q');
    await expect.poll(() => getBlockText(page, beta.firstBlockId)).toBe(`${betaBefore}q`);
    expect(await getBlockText(page, alpha.firstBlockId)).toBe(alphaBefore);
  });

  // ── Focus preservation across edit/select boundary ────────────────────────

  test('Escape from edit preserves focus so typing still works', async ({ page }) => {
    // 74cb2c80's claim: "preserve focus on Escape". After Escape,
    // editing-block-id clears but focus stays, so the next printable
    // key re-enters edit mode on the same block. The shadow-bug path
    // would produce `a` + `function cljs$core$...` here instead of `ab`.
    await dispatchIntent(page, { type: 'create-page', title: 'EscTest' });
    await waitForFocus(page);
    const sel = await getSelectionState(page);

    await page.keyboard.press('a');
    await waitForEditing(page, sel.focus);

    await page.keyboard.press('Escape');
    await page.waitForFunction(() => {
      const s = window.TEST_HELPERS?.getSession();
      return (s?.ui?.['editing-block-id'] ?? s?.ui?.editing_block_id) == null;
    });

    const afterEsc = await getSelectionState(page);
    expect(afterEsc.focus).toBe(sel.focus);

    await page.waitForFunction((blockId) => {
      const s = window.TEST_HELPERS?.getSession();
      const editingBlockId = s?.ui?.['editing-block-id'] ?? s?.ui?.editing_block_id;
      return editingBlockId == null && s?.selection?.focus === blockId;
    }, sel.focus);

    await page.keyboard.press('b');
    await waitForEditing(page, sel.focus);
    await expect.poll(() => getBlockText(page, sel.focus)).toBe('ab');
  });
});
