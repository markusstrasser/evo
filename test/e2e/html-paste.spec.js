// @ts-check
import { expect, test } from '@playwright/test';
import { enterEditModeAndClick } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * HTML Paste Conversion E2E Tests
 *
 * Tests that pasting HTML content from web pages or rich text applications
 * gets converted to markdown when the HTML contains formatting.
 *
 * Uses turndown.js via the html-to-markdown module.
 */

test.describe('HTML Paste Conversion', () => {
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

  /**
   * Helper to simulate paste with both plain text and HTML
   */
  async function simulatePasteWithHtml(page, plainText, htmlText) {
    await page.evaluate(
      ({ plain, html }) => {
        const editable = document.querySelector('[contenteditable="true"]');
        if (!editable) return;

        // Create a paste event with DataTransfer containing both formats
        const dataTransfer = new DataTransfer();
        dataTransfer.setData('text/plain', plain);
        dataTransfer.setData('text/html', html);

        const pasteEvent = new ClipboardEvent('paste', {
          bubbles: true,
          cancelable: true,
          clipboardData: dataTransfer,
        });

        editable.dispatchEvent(pasteEvent);
      },
      { plain: plainText, html: htmlText }
    );
  }

  test('pasting HTML with bold converts to markdown bold', async ({ page }) => {
    await simulatePasteWithHtml(page, 'plain text', '<p><strong>bold text</strong></p>');
    await wait(page, 200);

    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Should contain **bold text** markdown
    expect(result).toContain('**bold text**');
  });

  test('pasting HTML with link converts to markdown link', async ({ page }) => {
    await simulatePasteWithHtml(
      page,
      'click here',
      '<p><a href="https://example.com">click here</a></p>'
    );
    await wait(page, 200);

    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Should contain [click here](https://example.com) markdown link
    expect(result).toContain('[click here](https://example.com)');
  });

  test('pasting HTML with code converts to markdown code', async ({ page }) => {
    await simulatePasteWithHtml(page, 'const x = 1', '<p><code>const x = 1</code></p>');
    await wait(page, 200);

    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Should contain `const x = 1` inline code
    expect(result).toContain('`const x = 1`');
  });

  test('pasting HTML with list creates separate blocks (outliner behavior)', async ({ page }) => {
    await simulatePasteWithHtml(page, 'item 1\nitem 2', '<ul><li>item 1</li><li>item 2</li></ul>');
    await wait(page, 300);

    // In an outliner, HTML list items become separate blocks (not markdown list syntax)
    // Get all block texts to verify list items became separate blocks
    const blocks = await page.evaluate(() => {
      const db = window.TEST_HELPERS.getDb();
      const session = window.TEST_HELPERS.getSession();
      const pageId = session?.ui?.['current-page'];
      const children = db?.['children-by-parent']?.[pageId] || [];
      return children.map((id) => db?.nodes?.[id]?.props?.text);
    });

    // List items should be separate blocks
    expect(blocks).toContain('item 1');
    expect(blocks).toContain('item 2');
  });

  test('pasting plain HTML (no formatting) stays as plain text', async ({ page }) => {
    await simulatePasteWithHtml(page, 'just plain text', '<p>just plain text</p>');
    await wait(page, 200);

    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Should be plain text without extra formatting
    expect(result).toBe('just plain text');
  });

  test('pasting HTML with italic converts to markdown italic', async ({ page }) => {
    await simulatePasteWithHtml(page, 'italic text', '<p><em>italic text</em></p>');
    await wait(page, 200);

    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Should contain _italic text_ markdown
    expect(result).toContain('_italic text_');
  });

  test('pasting HTML with heading converts to markdown heading', async ({ page }) => {
    await simulatePasteWithHtml(page, 'My Heading', '<h2>My Heading</h2>');
    await wait(page, 200);

    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Should contain ## My Heading
    expect(result).toContain('## My Heading');
  });

  test('pasting HTML with blockquote converts to markdown quote', async ({ page }) => {
    await simulatePasteWithHtml(page, 'quoted text', '<blockquote>quoted text</blockquote>');
    await wait(page, 200);

    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Should contain > quoted text
    expect(result).toContain('> quoted text');
  });

  test('pasting complex HTML with multiple formats', async ({ page }) => {
    await simulatePasteWithHtml(
      page,
      'Visit Example for bold info',
      '<p>Visit <a href="https://example.com">Example</a> for <strong>bold</strong> info</p>'
    );
    await wait(page, 200);

    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Should contain both link and bold markdown
    expect(result).toContain('[Example](https://example.com)');
    expect(result).toContain('**bold**');
  });
});
