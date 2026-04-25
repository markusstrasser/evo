// @ts-check
import { expect, test } from '@playwright/test';
import { pressGlobalKey, pressKeyOnContentEditable, setCursorPosition } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

async function loadClipboardFixture(page, text = '') {
  await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
  await page.evaluate((blockText) => {
    window.TEST_HELPERS.loadFixture({
      ops: [
        { op: 'create-node', id: 'clip-page', type: 'page', props: { title: 'Clipboard' } },
        { op: 'place', id: 'clip-page', under: 'doc', at: 'last' },
        { op: 'create-node', id: 'clip-block', type: 'block', props: { text: blockText } },
        { op: 'place', id: 'clip-block', under: 'clip-page', at: 'last' },
      ],
      session: {
        ui: { 'current-page': 'clip-page', 'journals-view?': false },
        selection: { nodes: [] },
      },
    });
  }, text);
  await page.waitForSelector('[data-block-id="clip-block"]', { timeout: 5000 });
}

async function enterClipBlock(page, offset = 0) {
  await page.evaluate(() => {
    window.TEST_HELPERS.dispatchIntent({
      type: 'selection',
      mode: 'replace',
      ids: 'clip-block',
    });
    window.TEST_HELPERS.dispatchIntent({ type: 'enter-edit', 'block-id': 'clip-block' });
  });
  await page.waitForFunction(
    () => window.TEST_HELPERS?.getSession?.()?.ui?.['editing-block-id'] === 'clip-block'
  );
  await page.locator('[contenteditable="true"]').click();
  await setCursorPosition(page, 'clip-block', offset);
}

async function dispatchPaste(page, text) {
  await page.evaluate((pastedText) => {
    const el = document.querySelector('[contenteditable="true"]');
    if (!el) throw new Error('No contenteditable element for paste');

    const pasteEvent = new ClipboardEvent('paste', {
      bubbles: true,
      cancelable: true,
      clipboardData: new DataTransfer(),
    });
    pasteEvent.clipboardData.setData('text/plain', pastedText);
    el.dispatchEvent(pasteEvent);
  }, text);
}

async function selectClipBlock(page) {
  await page.evaluate(() => {
    window.TEST_HELPERS.dispatchIntent({
      type: 'selection',
      mode: 'replace',
      ids: 'clip-block',
    });
  });
  await page.waitForFunction(
    () => window.TEST_HELPERS?.getSession?.()?.selection?.focus === 'clip-block'
  );
}

async function clipboardText(page) {
  return page.evaluate(() => window.TEST_HELPERS?.getSession?.()?.ui?.['clipboard-text']);
}

test.describe('Clipboard Operations', () => {
  test.beforeEach(async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await loadClipboardFixture(page);
  });

  test.describe('Paste via Event', () => {
    test('paste simple text via dispatch event', async ({ page }) => {
      await enterClipBlock(page);
      await page.keyboard.type('Start ');

      await dispatchPaste(page, 'pasted text');
      await wait(page, 200);

      await expect(page.locator('[contenteditable="true"]')).toBeVisible();
    });

    test('paste with blank lines creates multiple blocks', async ({ page }) => {
      await enterClipBlock(page);
      await page.keyboard.type('First');

      await dispatchPaste(page, '\n\nSecond\n\nThird');
      await wait(page, 300);

      await pressKeyOnContentEditable(page, 'Escape');

      const blocks = page.locator('[data-block-id] .block-content');
      await expect(blocks).toHaveCount(3);
    });
  });

  test.describe('Copy Operations', () => {
    test('selected block copy stores clipboard text', async ({ page }) => {
      await loadClipboardFixture(page, 'Text to copy');
      await selectClipBlock(page);

      await pressGlobalKey(page, 'Meta+c');

      await expect.poll(() => clipboardText(page)).toBe('- Text to copy');
    });
  });

  test.describe('Cut Operations', () => {
    test('selected block cut stores clipboard text and removes block from page', async ({
      page,
    }) => {
      await loadClipboardFixture(page, 'Text to cut');
      await selectClipBlock(page);

      await pressGlobalKey(page, 'Meta+x');

      await expect.poll(() => clipboardText(page)).toBe('- Text to cut');
      await expect(page.locator('[data-block-id="clip-block"]')).not.toBeVisible();
    });
  });

  test.describe('Edge Cases', () => {
    test('paste empty string does nothing harmful', async ({ page }) => {
      await enterClipBlock(page);
      await page.keyboard.type('Original');

      await dispatchPaste(page, '');
      await wait(page, 200);

      await expect(page.locator('[contenteditable="true"]')).toBeVisible();
    });
  });
});
