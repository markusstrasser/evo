// @ts-check
import { expect, test } from '@playwright/test';
import {
  enterEditMode,
  modKey,
  pressKeyCombo,
  pressKeyOnContentEditable,
  setCursorPosition,
} from './helpers/index.js';

const blockId = 'kill-block';

async function loadKillFixture(page, text = '') {
  await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('domcontentloaded');
  await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
  await page.evaluate((initialText) => {
    window.TEST_HELPERS.loadFixture({
      ops: [
        { op: 'create-node', id: 'kill-page', type: 'page', props: { title: 'Kill Ops' } },
        { op: 'place', id: 'kill-page', under: 'doc', at: 'last' },
        { op: 'create-node', id: 'kill-block', type: 'block', props: { text: initialText } },
        { op: 'place', id: 'kill-block', under: 'kill-page', at: 'last' },
      ],
      session: {
        ui: { 'current-page': 'kill-page', 'journals-view?': false },
        selection: { nodes: [] },
      },
    });
    window.DEBUG?.clearClipboardLog?.();
  }, text);
  await page.waitForSelector(`div.block[data-block-id="${blockId}"]`, { timeout: 5000 });
}

async function dispatchKill(page, type, cursorPos) {
  await page.evaluate(
    ({ intentType, pos }) => {
      window.TEST_HELPERS.dispatchIntent({
        type: intentType,
        'block-id': 'kill-block',
        'cursor-pos': pos,
      });
    },
    { intentType: type, pos: cursorPos }
  );
}

async function dbBlockText(page) {
  return page.evaluate((id) => window.TEST_HELPERS?.getBlockText?.(id), blockId);
}

async function lastCopy(page) {
  return page.evaluate(() => window.DEBUG?.lastCopy?.());
}

test.describe('Kill Operations', () => {
  test.beforeEach(async ({ context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  });

  test.describe('Kill to End (Cmd+K)', () => {
    test('kills text from cursor to end and copies to clipboard', async ({ page }) => {
      await loadKillFixture(page, 'Hello World');
      await enterEditMode(page, blockId);

      await dispatchKill(page, 'kill-to-end', 6);

      await expect.poll(() => dbBlockText(page)).toBe('Hello ');
      const clip = await lastCopy(page);
      expect(clip?.text).toBe('World');
      expect(clip?.type).toBe('kill');
    });

    test('killing empty suffix copies empty string', async ({ page }) => {
      await loadKillFixture(page, 'Hello');
      await enterEditMode(page, blockId);

      await dispatchKill(page, 'kill-to-end', 5);

      await expect.poll(() => dbBlockText(page)).toBe('Hello');
      expect((await lastCopy(page))?.text).toBe('');
    });
  });

  test.describe('Kill to Beginning (Cmd+U)', () => {
    test('kills text from beginning to cursor and copies to clipboard', async ({ page }) => {
      await loadKillFixture(page, 'Hello World');
      await enterEditMode(page, blockId);

      await dispatchKill(page, 'kill-to-beginning', 6);

      await expect.poll(() => dbBlockText(page)).toBe('World');
      expect((await lastCopy(page))?.text).toBe('Hello ');
    });

    test('keyboard shortcut uses the live DOM cursor position', async ({ page }) => {
      await loadKillFixture(page, 'Hello World');
      await enterEditMode(page, blockId);
      await setCursorPosition(page, blockId, 6);

      await pressKeyCombo(page, 'u', [modKey]);

      await expect.poll(() => dbBlockText(page)).toBe('World');
      expect((await lastCopy(page))?.text).toBe('Hello ');
    });
  });

  test.describe('Kill Word Forward (Cmd+Delete)', () => {
    test('kills next word and copies to clipboard', async ({ page }) => {
      await loadKillFixture(page, 'Hello World Test');
      await enterEditMode(page, blockId);

      await dispatchKill(page, 'kill-word-forward', 0);

      await expect.poll(() => dbBlockText(page)).toBe(' World Test');
      expect((await lastCopy(page))?.text).toBe('Hello');
    });
  });

  test.describe('Kill Word Backward (Alt+Delete)', () => {
    test('kills previous word and copies to clipboard', async ({ page }) => {
      await loadKillFixture(page, 'Hello World Test');
      await enterEditMode(page, blockId);

      await dispatchKill(page, 'kill-word-backward', 16);

      await expect.poll(() => dbBlockText(page)).toBe('Hello World ');
      expect((await lastCopy(page))?.text).toBe('Test');
    });
  });
});

test.describe('DEBUG API Verification', () => {
  test.beforeEach(async ({ page }) => {
    await loadKillFixture(page);
  });

  test('DEBUG.snapshot() returns comprehensive state', async ({ page }) => {
    const snapshot = await page.evaluate(() => window.DEBUG?.snapshot?.());

    expect(snapshot).toBeTruthy();
    expect(snapshot).toHaveProperty('timestamp');
    expect(snapshot).toHaveProperty('db');
    expect(snapshot).toHaveProperty('session');
    expect(snapshot).toHaveProperty('history');
    expect(snapshot).toHaveProperty('log');
    expect(snapshot).toHaveProperty('clipboard');

    expect(snapshot.db).toHaveProperty('node-count');
    expect(snapshot.session).toHaveProperty('editing');
    expect(snapshot.history).toHaveProperty('undo-count');
  });

  test('DEBUG.assertBlockText() validates block content', async ({ page }) => {
    await page.evaluate(() => {
      window.TEST_HELPERS.transact([
        { op: 'update-node', id: 'kill-block', props: { text: 'Test content' } },
      ]);
    });

    const result = await page.evaluate(
      (id) => window.DEBUG?.assertBlockText?.(id, 'Test content'),
      blockId
    );
    expect(result?.ok).toBe(true);

    const failResult = await page.evaluate(
      (id) => window.DEBUG?.assertBlockText?.(id, 'Wrong content'),
      blockId
    );
    expect(failResult?.ok).toBe(false);
    expect(failResult?.actual).toBe('Test content');
  });

  test('DEBUG.undoCount() and canUndo() work correctly', async ({ page }) => {
    expect(await page.evaluate(() => window.DEBUG?.undoCount?.())).toBe(0);
    expect(await page.evaluate(() => window.DEBUG?.canUndo?.())).toBe(false);

    await enterEditMode(page, blockId);
    await page.keyboard.type('New content');
    await pressKeyOnContentEditable(page, 'Enter');

    expect(await page.evaluate(() => window.DEBUG?.canUndo?.())).toBe(true);
  });

  test('DEBUG.clipboardLog() tracks clipboard operations', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.evaluate(() => window.DEBUG?.clearClipboardLog?.());

    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({ type: 'selection', mode: 'replace', ids: 'kill-block' });
      window.TEST_HELPERS.dispatchIntent({ type: 'copy-selected' });
    });

    await expect.poll(() => page.evaluate(() => window.DEBUG?.clipboardLog?.()?.length)).toBe(1);
    const clipLog = await page.evaluate(() => window.DEBUG?.clipboardLog?.());
    expect(clipLog[0]).toHaveProperty('type');
    expect(clipLog[0]).toHaveProperty('timestamp');
  });

  test('DEBUG.lastIntent() returns most recent intent', async ({ page }) => {
    await page.evaluate(() => window.DEBUG?.clearLog?.());

    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({
        type: 'enter-edit',
        'block-id': 'kill-block',
      });
    });

    await expect
      .poll(() => page.evaluate(() => window.DEBUG?.lastIntent?.()?.type))
      .toBe('enter-edit');
  });
});
