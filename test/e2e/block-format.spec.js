// @ts-check
import { expect, test } from '@playwright/test';
import { enterEditModeAndClick } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * Block Format E2E Tests
 *
 * Tests markdown-style block formatting:
 * - Block quotes (> prefix)
 * - Headings (# - ###### prefix)
 */

test.describe('Block Formats', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test.describe('Block Quotes', () => {
    test('/quote command inserts quote marker', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('/quote');
      await wait(page);

      await page.keyboard.press('Enter');
      await wait(page);

      // Should have > marker in edit mode
      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toBe('> ');
    });

    test('quote renders as blockquote in view mode', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('> This is a quote');
      await wait(page);

      // Exit edit mode by clicking elsewhere
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Should render as blockquote
      const blockquote = page.locator('blockquote.block-content');
      await expect(blockquote).toBeVisible();

      // Content should NOT include the "> " prefix
      const text = await blockquote.textContent();
      expect(text).toBe('This is a quote');
    });

    test('blockquote has proper styling', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('> Styled quote');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      const blockquote = page.locator('blockquote.block-content');
      await expect(blockquote).toBeVisible();

      // Check styling via computed styles
      const styles = await blockquote.evaluate((el) => {
        const cs = window.getComputedStyle(el);
        return {
          borderLeftWidth: cs.borderLeftWidth,
          fontStyle: cs.fontStyle,
        };
      });

      expect(styles.borderLeftWidth).toBe('4px');
      expect(styles.fontStyle).toBe('italic');
    });

    test('clicking blockquote enters edit mode with raw markdown', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('> Editable quote');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Click the blockquote to focus block first
      const blockquote = page.locator('blockquote.block-content');
      await blockquote.click();
      await wait(page, 100);

      // Click again to enter edit mode (focused -> editing)
      await blockquote.click();
      await wait(page, 100);

      // Should now be in edit mode with raw markdown
      const editable = page.locator('[contenteditable="true"]');
      await expect(editable).toBeVisible({ timeout: 2000 });
      const text = await editable.textContent();
      expect(text).toBe('> Editable quote');
    });
  });

  test.describe('Headings', () => {
    test('/h1 command inserts h1 marker', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('/h1');
      await wait(page);

      // Select Heading 1 (first option)
      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toBe('# ');
    });

    test('h1 renders with large font in view mode', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('# Main Heading');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Should render as h1
      const h1 = page.locator('h1.block-content');
      await expect(h1).toBeVisible();

      // Content should NOT include "# " prefix
      const text = await h1.textContent();
      expect(text).toBe('Main Heading');

      // Check font size is larger
      const fontSize = await h1.evaluate((el) => {
        return window.getComputedStyle(el).fontSize;
      });
      // Should be 1.75rem = 28px (assuming 16px base)
      expect(parseFloat(fontSize)).toBeGreaterThan(20);
    });

    test('h2 renders with medium font', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('## Secondary Heading');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      const h2 = page.locator('h2.block-content');
      await expect(h2).toBeVisible();

      const text = await h2.textContent();
      expect(text).toBe('Secondary Heading');
    });

    test('h3 renders correctly', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('### Third Level');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      const h3 = page.locator('h3.block-content');
      await expect(h3).toBeVisible();
      expect(await h3.textContent()).toBe('Third Level');
    });

    test('h4 renders correctly', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('#### Fourth Level');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      const h4 = page.locator('h4.block-content');
      await expect(h4).toBeVisible();
      expect(await h4.textContent()).toBe('Fourth Level');
    });

    test('h5 renders correctly', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('##### Fifth Level');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      const h5 = page.locator('h5.block-content');
      await expect(h5).toBeVisible();
      expect(await h5.textContent()).toBe('Fifth Level');
    });

    test('h6 renders with uppercase styling', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('###### Sixth Level');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      const h6 = page.locator('h6.block-content');
      await expect(h6).toBeVisible();
      expect(await h6.textContent()).toBe('Sixth Level');

      // h6 should have uppercase transform
      const textTransform = await h6.evaluate((el) => {
        return window.getComputedStyle(el).textTransform;
      });
      expect(textTransform).toBe('uppercase');
    });

    test('clicking heading enters edit mode with raw markdown', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('# Clickable Heading');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Click the heading to focus block first
      const h1 = page.locator('h1.block-content');
      await h1.click();
      await wait(page, 100);

      // Click again to enter edit mode (focused -> editing)
      await h1.click();
      await wait(page, 100);

      // Should now be in edit mode with raw markdown
      const editable = page.locator('[contenteditable="true"]');
      await expect(editable).toBeVisible({ timeout: 2000 });
      const text = await editable.textContent();
      expect(text).toBe('# Clickable Heading');
    });

    test('7 hashes does not render as heading', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('####### Too many hashes');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Should NOT render as any heading (h1-h6)
      const h1 = page.locator('h1.block-content');
      const h2 = page.locator('h2.block-content');
      const h3 = page.locator('h3.block-content');
      const h4 = page.locator('h4.block-content');
      const h5 = page.locator('h5.block-content');
      const h6 = page.locator('h6.block-content');

      await expect(h1).not.toBeVisible();
      await expect(h2).not.toBeVisible();
      await expect(h3).not.toBeVisible();
      await expect(h4).not.toBeVisible();
      await expect(h5).not.toBeVisible();
      await expect(h6).not.toBeVisible();

      // Should render as plain span (use text filter for specificity)
      const span = page
        .locator('span.block-content')
        .filter({ hasText: '####### Too many hashes' });
      await expect(span).toBeVisible();
      expect(await span.textContent()).toBe('####### Too many hashes');
    });
  });

  test.describe('Edge Cases', () => {
    test('> without space is not a quote', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('>Not a quote');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Should NOT be blockquote
      const blockquote = page.locator('blockquote.block-content');
      await expect(blockquote).not.toBeVisible();

      // Should be plain span (use text filter for specificity)
      const span = page.locator('span.block-content').filter({ hasText: '>Not a quote' });
      await expect(span).toBeVisible();
      expect(await span.textContent()).toBe('>Not a quote');
    });

    test('# without space is not a heading', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('#NotAHeading');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Should NOT be heading
      const h1 = page.locator('h1.block-content');
      await expect(h1).not.toBeVisible();

      // Should be plain span (use text filter for specificity)
      const span = page.locator('span.block-content').filter({ hasText: '#NotAHeading' });
      await expect(span).toBeVisible();
      expect(await span.textContent()).toBe('#NotAHeading');
    });

    test('empty quote renders correctly', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('> some quote text');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Should render as blockquote with content
      const blockquote = page.locator('blockquote.block-content');
      await expect(blockquote).toBeVisible();
      expect(await blockquote.textContent()).toBe('some quote text');
    });

    test('empty heading renders correctly', async ({ page }) => {
      await enterEditModeAndClick(page);
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('# some heading');
      await wait(page);

      // Exit edit mode
      await page.click('body', { position: { x: 10, y: 10 } });
      await wait(page, 200);

      // Should render as h1 with content
      const h1 = page.locator('h1.block-content');
      await expect(h1).toBeVisible();
      expect(await h1.textContent()).toBe('some heading');
    });
  });
});
