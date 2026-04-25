// @ts-check
import { expect, test } from '@playwright/test';

/**
 * Block Format E2E Tests
 *
 * Slash-command insertion is covered in slash-commands.spec.js. This file owns
 * view rendering and click-to-edit behavior for markdown-style block formats.
 */

async function loadFormatBlock(page, text) {
  await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
  await page.evaluate((blockText) => {
    window.TEST_HELPERS.loadFixture({
      ops: [
        { op: 'create-node', id: 'format-page', type: 'page', props: { title: 'Formats' } },
        { op: 'place', id: 'format-page', under: 'doc', at: 'last' },
        { op: 'create-node', id: 'format-block', type: 'block', props: { text: blockText } },
        { op: 'place', id: 'format-block', under: 'format-page', at: 'last' },
      ],
      session: {
        ui: { 'current-page': 'format-page', 'journals-view?': false },
        selection: { nodes: [] },
      },
    });
  }, text);
  await page.waitForSelector('[data-block-id="format-block"]', { timeout: 5000 });
}

async function enterFormattedBlockByClick(page, selector) {
  const formatted = page.locator(selector);
  await formatted.click();
  await page.waitForFunction(
    () => window.TEST_HELPERS?.getSession?.()?.selection?.focus === 'format-block'
  );
  await formatted.click();
  await expect(page.locator('[contenteditable="true"]')).toBeVisible({ timeout: 2000 });
}

test.describe('Block Formats', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
  });

  test.describe('Block Quotes', () => {
    test('quote renders as blockquote in view mode', async ({ page }) => {
      await loadFormatBlock(page, '> This is a quote');

      const blockquote = page.locator('blockquote.block-content');
      await expect(blockquote).toBeVisible();
      await expect(blockquote).toHaveText('This is a quote');
    });

    test('blockquote has proper styling', async ({ page }) => {
      await loadFormatBlock(page, '> Styled quote');

      const blockquote = page.locator('blockquote.block-content');
      await expect(blockquote).toBeVisible();

      const styles = await blockquote.evaluate((el) => {
        const cs = window.getComputedStyle(el);
        return {
          borderLeftWidth: cs.borderLeftWidth,
          fontStyle: cs.fontStyle,
        };
      });

      expect(parseFloat(styles.borderLeftWidth)).toBeGreaterThanOrEqual(3);
      expect(styles.fontStyle).toBe('italic');
    });

    test('clicking blockquote enters edit mode with raw markdown', async ({ page }) => {
      await loadFormatBlock(page, '> Editable quote');

      await enterFormattedBlockByClick(page, 'blockquote.block-content');

      await expect(page.locator('[contenteditable="true"]')).toHaveText('> Editable quote');
    });
  });

  test.describe('Headings', () => {
    test('h1 renders with large font in view mode', async ({ page }) => {
      await loadFormatBlock(page, '# Main Heading');

      const h1 = page.locator('h1.block-content');
      await expect(h1).toBeVisible();
      await expect(h1).toHaveText('Main Heading');

      const fontSize = await h1.evaluate((el) => window.getComputedStyle(el).fontSize);
      expect(parseFloat(fontSize)).toBeGreaterThan(20);
    });

    test('h2 renders with medium font', async ({ page }) => {
      await loadFormatBlock(page, '## Secondary Heading');

      await expect(page.locator('h2.block-content')).toHaveText('Secondary Heading');
    });

    test('h3 renders correctly', async ({ page }) => {
      await loadFormatBlock(page, '### Third Level');

      await expect(page.locator('h3.block-content')).toHaveText('Third Level');
    });

    test('h4 renders correctly', async ({ page }) => {
      await loadFormatBlock(page, '#### Fourth Level');

      await expect(page.locator('h4.block-content')).toHaveText('Fourth Level');
    });

    test('h5 renders correctly', async ({ page }) => {
      await loadFormatBlock(page, '##### Fifth Level');

      await expect(page.locator('h5.block-content')).toHaveText('Fifth Level');
    });

    test('h6 renders with uppercase styling', async ({ page }) => {
      await loadFormatBlock(page, '###### Sixth Level');

      const h6 = page.locator('h6.block-content');
      await expect(h6).toHaveText('Sixth Level');

      const textTransform = await h6.evaluate((el) => window.getComputedStyle(el).textTransform);
      expect(textTransform).toBe('uppercase');
    });

    test('clicking heading enters edit mode with raw markdown', async ({ page }) => {
      await loadFormatBlock(page, '# Clickable Heading');

      await enterFormattedBlockByClick(page, 'h1.block-content');

      await expect(page.locator('[contenteditable="true"]')).toHaveText('# Clickable Heading');
    });

    test('7 hashes does not render as heading', async ({ page }) => {
      await loadFormatBlock(page, '####### Too many hashes');

      await expect(page.locator('h1.block-content')).not.toBeVisible();
      await expect(page.locator('h2.block-content')).not.toBeVisible();
      await expect(page.locator('h3.block-content')).not.toBeVisible();
      await expect(page.locator('h4.block-content')).not.toBeVisible();
      await expect(page.locator('h5.block-content')).not.toBeVisible();
      await expect(page.locator('h6.block-content')).not.toBeVisible();
      await expect(
        page.locator('span.block-content').filter({ hasText: '####### Too many hashes' })
      ).toHaveText('####### Too many hashes');
    });
  });

  test.describe('Edge Cases', () => {
    test('> without space is not a quote', async ({ page }) => {
      await loadFormatBlock(page, '>Not a quote');

      await expect(page.locator('blockquote.block-content')).not.toBeVisible();
      await expect(
        page.locator('span.block-content').filter({ hasText: '>Not a quote' })
      ).toHaveText('>Not a quote');
    });

    test('# without space is not a heading', async ({ page }) => {
      await loadFormatBlock(page, '#NotAHeading');

      await expect(page.locator('h1.block-content')).not.toBeVisible();
      await expect(
        page.locator('span.block-content').filter({ hasText: '#NotAHeading' })
      ).toHaveText('#NotAHeading');
    });

    test('quote marker renders content', async ({ page }) => {
      await loadFormatBlock(page, '> some quote text');

      await expect(page.locator('blockquote.block-content')).toHaveText('some quote text');
    });

    test('heading marker renders content', async ({ page }) => {
      await loadFormatBlock(page, '# some heading');

      await expect(page.locator('h1.block-content')).toHaveText('some heading');
    });
  });
});
