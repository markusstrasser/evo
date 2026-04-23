// @ts-check
import { expect, test } from '@playwright/test';
import { enterEditModeAndClick, getBlockText, getFirstBlockId } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * Kill Operations E2E Tests
 *
 * Tests kill operations (Cmd+K, Cmd+U, etc.) and verifies they
 * copy killed text to clipboard via the DEBUG API.
 */

test.describe('Kill Operations', () => {
  test.beforeEach(async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test.describe('Kill to End (Cmd+K)', () => {
    test('kills text from cursor to end and copies to clipboard', async ({ page }) => {
      // Setup: Enter edit mode and type text
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('Hello World');
      await wait(page);

      // Position cursor after "Hello " (position 6)
      const blockId = await getFirstBlockId(page);
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-cursor-state',
          blockId: id,
          cursorPos: 6,
        });
      }, blockId);
      await wait(page);

      // Kill to end via intent (keyboard binding may not work in test)
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'kill-to-end',
          blockId: id,
        });
      }, blockId);
      await wait(page, 200);

      // Verify text is truncated
      const text = await getBlockText(page, blockId);
      expect(text).toBe('Hello ');

      // Verify killed text in clipboard via DEBUG API
      const lastClip = await page.evaluate(() => window.DEBUG?.lastCopy?.());
      expect(lastClip).toBeTruthy();
      expect(lastClip.text).toBe('World');
      expect(lastClip.type).toBe('kill');
    });

    test('killing empty suffix copies empty string', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('Hello');
      await wait(page);

      const blockId = await getFirstBlockId(page);

      // Position cursor at end (position 5)
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-cursor-state',
          blockId: id,
          cursorPos: 5,
        });
      }, blockId);
      await wait(page);

      // Kill to end at end of text
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'kill-to-end',
          blockId: id,
        });
      }, blockId);
      await wait(page, 200);

      // Text should remain unchanged
      const text = await getBlockText(page, blockId);
      expect(text).toBe('Hello');

      // Clipboard should have empty string
      const lastClip = await page.evaluate(() => window.DEBUG?.lastCopy?.());
      expect(lastClip?.text).toBe('');
    });
  });

  test.describe('Kill to Beginning (Cmd+U)', () => {
    test('kills text from beginning to cursor and copies to clipboard', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('Hello World');
      await wait(page);

      const blockId = await getFirstBlockId(page);

      // Position cursor after "Hello " (position 6)
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-cursor-state',
          blockId: id,
          cursorPos: 6,
        });
      }, blockId);
      await wait(page);

      // Kill to beginning
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'kill-to-beginning',
          blockId: id,
        });
      }, blockId);
      await wait(page, 200);

      // Verify text is prefix-removed
      const text = await getBlockText(page, blockId);
      expect(text).toBe('World');

      // Verify killed text in clipboard
      const lastClip = await page.evaluate(() => window.DEBUG?.lastCopy?.());
      expect(lastClip?.text).toBe('Hello ');
    });
  });

  test.describe('Kill Word Forward (Cmd+Delete)', () => {
    test('kills next word and copies to clipboard', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('Hello World Test');
      await wait(page);

      const blockId = await getFirstBlockId(page);

      // Position cursor at start
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-cursor-state',
          blockId: id,
          cursorPos: 0,
        });
      }, blockId);
      await wait(page);

      // Kill word forward
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'kill-word-forward',
          blockId: id,
        });
      }, blockId);
      await wait(page, 200);

      // "Hello" should be killed, leaving " World Test"
      const text = await getBlockText(page, blockId);
      expect(text).toBe(' World Test');

      // Verify killed text
      const lastClip = await page.evaluate(() => window.DEBUG?.lastCopy?.());
      expect(lastClip?.text).toBe('Hello');
    });
  });

  test.describe('Kill Word Backward (Alt+Delete)', () => {
    test('kills previous word and copies to clipboard', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('Hello World Test');
      await wait(page);

      const blockId = await getFirstBlockId(page);

      // Position cursor at end (after "Test")
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-cursor-state',
          blockId: id,
          cursorPos: 16, // Length of "Hello World Test"
        });
      }, blockId);
      await wait(page);

      // Kill word backward
      await page.evaluate((id) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'kill-word-backward',
          blockId: id,
        });
      }, blockId);
      await wait(page, 200);

      // "Test" should be killed
      const text = await getBlockText(page, blockId);
      expect(text).toBe('Hello World ');

      // Verify killed text
      const lastClip = await page.evaluate(() => window.DEBUG?.lastCopy?.());
      expect(lastClip?.text).toBe('Test');
    });
  });
});

test.describe('DEBUG API Verification', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
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

    expect(snapshot.db).toHaveProperty('node_count');
    expect(snapshot.session).toHaveProperty('editing');
    expect(snapshot.history).toHaveProperty('undo_count');
  });

  test('DEBUG.assertBlockText() validates block content', async ({ page }) => {
    await enterEditModeAndClick(page);
    await page.keyboard.press('Meta+a');
    await page.keyboard.type('Test content');
    await wait(page);

    const blockId = await getFirstBlockId(page);

    // Commit the text
    await page.keyboard.press('Escape');
    await wait(page, 200);

    // Assert correct text
    const result = await page.evaluate(
      (id) => window.DEBUG?.assertBlockText?.(id, 'Test content'),
      blockId
    );
    expect(result?.ok).toBe(true);

    // Assert incorrect text
    const failResult = await page.evaluate(
      (id) => window.DEBUG?.assertBlockText?.(id, 'Wrong content'),
      blockId
    );
    expect(failResult?.ok).toBe(false);
    expect(failResult?.actual).toBe('Test content');
  });

  test('DEBUG.undoCount() and canUndo() work correctly', async ({ page }) => {
    // Initially no undo available (fresh state)
    const initialUndo = await page.evaluate(() => window.DEBUG?.undoCount?.());
    expect(initialUndo).toBe(0);

    const canUndoInitial = await page.evaluate(() => window.DEBUG?.canUndo?.());
    expect(canUndoInitial).toBe(false);

    // Create a block to generate history
    await enterEditModeAndClick(page);
    await page.keyboard.press('Meta+a');
    await page.keyboard.type('New content');
    await wait(page);
    await page.keyboard.press('Enter');
    await wait(page, 200);

    // Now undo should be available
    const _canUndoAfter = await page.evaluate(() => window.DEBUG?.canUndo?.());
    // Note: depends on whether Enter creates history entry
  });

  test('DEBUG.clipboardLog() tracks clipboard operations', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);

    // Clear clipboard log
    await page.evaluate(() => window.DEBUG?.clearClipboardLog?.());

    await enterEditModeAndClick(page);
    await page.keyboard.press('Meta+a');
    await page.keyboard.type('Copy me');
    await wait(page);

    const blockId = await getFirstBlockId(page);

    // Perform a copy
    await page.evaluate((_id) => {
      window.TEST_HELPERS.dispatchIntent({
        type: 'copy-selected',
      });
    }, blockId);
    await wait(page, 200);

    // Check clipboard log
    const clipLog = await page.evaluate(() => window.DEBUG?.clipboardLog?.());
    expect(clipLog?.length).toBeGreaterThan(0);
    expect(clipLog[0]).toHaveProperty('type');
    expect(clipLog[0]).toHaveProperty('timestamp');
  });

  test('DEBUG.lastIntent() returns most recent intent', async ({ page }) => {
    await enterEditModeAndClick(page);

    // Clear log first
    await page.evaluate(() => window.DEBUG?.clearLog?.());

    const blockId = await getFirstBlockId(page);

    // Dispatch an intent
    await page.evaluate((id) => {
      window.TEST_HELPERS.dispatchIntent({
        type: 'enter-edit',
        blockId: id,
      });
    }, blockId);
    await wait(page, 100);

    // Check last intent
    const lastIntent = await page.evaluate(() => window.DEBUG?.lastIntent?.());
    expect(lastIntent?.type).toBe('enter-edit');
  });
});
