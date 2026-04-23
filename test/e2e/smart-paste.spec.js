// @ts-check
import { expect, test } from '@playwright/test';
import { enterEditModeAndClick } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * Smart Paste E2E Tests
 *
 * Tests smart URL paste feature (Logseq parity):
 * - Text selected + URL pasted → [selection](URL)
 * - URL selected + text pasted → [text](URL)
 */

test.describe('Smart URL Paste', () => {
  let blockId;

  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);

    blockId = await page.evaluate(() => {
      return window.TEST_HELPERS?.getSession()?.ui?.['editing-block-id'];
    });
  });

  test('pasting URL over selected text creates markdown link', async ({ page }) => {
    // Set up text in DB
    await page.evaluate(
      ({ bid }) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-content',
          'block-id': bid,
          text: 'click here for info',
        });
      },
      { bid: blockId }
    );
    await wait(page);

    // Paste URL over selection "here" (positions 6-10)
    await page.evaluate(
      ({ bid }) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'paste-text',
          'block-id': bid,
          'cursor-pos': 6,
          'selection-end': 10,
          'pasted-text': 'https://example.com',
        });
      },
      { bid: blockId }
    );
    await wait(page);

    // Verify result
    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    expect(result).toBe('click [here](https://example.com) for info');
  });

  test('pasting text over selected URL creates markdown link', async ({ page }) => {
    // Set up text with URL
    await page.evaluate(
      ({ bid }) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-content',
          'block-id': bid,
          text: 'Visit https://example.com today',
        });
      },
      { bid: blockId }
    );
    await wait(page);

    // Paste text over selected URL (positions 6-25)
    await page.evaluate(
      ({ bid }) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'paste-text',
          'block-id': bid,
          'cursor-pos': 6,
          'selection-end': 25,
          'pasted-text': 'my site',
        });
      },
      { bid: blockId }
    );
    await wait(page);

    // Verify result
    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    expect(result).toBe('Visit [my site](https://example.com) today');
  });

  test('pasting URL without selection pastes inline', async ({ page }) => {
    // Set up text
    await page.evaluate(
      ({ bid }) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'update-content',
          'block-id': bid,
          text: 'Check out ',
        });
      },
      { bid: blockId }
    );
    await wait(page);

    // Paste URL without selection
    await page.evaluate(
      ({ bid }) => {
        window.TEST_HELPERS.dispatchIntent({
          type: 'paste-text',
          'block-id': bid,
          'cursor-pos': 10,
          'pasted-text': 'https://example.com',
        });
      },
      { bid: blockId }
    );
    await wait(page);

    // Verify just inline paste
    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    expect(result).toBe('Check out https://example.com');
  });
});
