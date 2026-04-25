// @ts-check
import { expect, test } from '@playwright/test';
import { enterEditMode, exitEditMode, updateBlockText } from './helpers/block-helpers.js';
import { modKey, pressKeyCombo } from './helpers/keyboard.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);
const blockId = 'image-block';

async function loadImageFixture(page, text = '') {
  await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('domcontentloaded');
  await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
  await page.evaluate((initialText) => {
    window.TEST_HELPERS.loadFixture({
      ops: [
        { op: 'create-node', id: 'image-page', type: 'page', props: { title: 'Images' } },
        { op: 'place', id: 'image-page', under: 'doc', at: 'last' },
        { op: 'create-node', id: 'image-block', type: 'block', props: { text: initialText } },
        { op: 'place', id: 'image-block', under: 'image-page', at: 'last' },
      ],
      session: {
        ui: { 'current-page': 'image-page', 'journals-view?': false },
        selection: { nodes: [] },
      },
    });
  }, text);
  await page.waitForSelector(`div.block[data-block-id="${blockId}"]`, { timeout: 5000 });
}

/**
 * Image Block E2E Tests
 *
 * Tests the unified markdown-first image model:
 * - Images are stored as markdown: ![alt](path){width=N}
 * - Image-only blocks render with .image-block-content wrapper
 * - Inline images render within text blocks as img.inline-image
 * - Edit mode shows raw markdown
 *
 * NOTE: External image URLs won't load in test environment, so the <img>
 * elements are hidden by the error handler. We test DOM structure (element
 * existence + attributes) rather than visual visibility for image elements.
 */

test.describe('Image Blocks (Markdown-First Model)', () => {
  test.beforeEach(async ({ page }) => {
    await loadImageFixture(page);
  });

  test.describe('Image-only block rendering', () => {
    test('image markdown creates image-block-content wrapper', async ({ page }) => {
      await updateBlockText(page, blockId, '![cat](https://example.com/cat.png)');
      await wait(page, 200);

      // Should render image-block-content div (even if img inside is hidden due to load error)
      const imageBlock = page.locator(`div.block[data-block-id="${blockId}"] .image-block-content`);
      await expect(imageBlock).toHaveCount(1);

      // Should contain an img element with correct alt text
      const img = imageBlock.locator('img');
      await expect(img).toHaveCount(1);
      expect(await img.getAttribute('alt')).toBe('cat');
    });

    test('image with width has style attribute on img', async ({ page }) => {
      await updateBlockText(page, blockId, '![](https://example.com/photo.png){width=250}');
      await wait(page, 200);

      const img = page.locator(`div.block[data-block-id="${blockId}"] .image-block-content img`);
      await expect(img).toHaveCount(1);

      // Check width style is applied
      const style = await img.getAttribute('style');
      expect(style).toContain('width');
      expect(style).toContain('250px');
    });

    test('image without width has no style attribute', async ({ page }) => {
      await updateBlockText(page, blockId, '![cat](https://example.com/cat.png)');
      await wait(page, 200);

      const img = page.locator(`div.block[data-block-id="${blockId}"] .image-block-content img`);
      await expect(img).toHaveCount(1);

      const style = await img.getAttribute('style');
      // No width style when no {width=N} attribute
      expect(style === null || !style.includes('width')).toBeTruthy();
    });

    test('clicking image-only block enters edit mode with raw markdown', async ({ page }) => {
      const markdown = '![photo](https://example.com/photo.png)';
      await updateBlockText(page, blockId, markdown);
      await wait(page, 200);

      // Use programmatic enter-edit for reliability
      await enterEditMode(page, blockId);
      await wait(page, 100);

      // Should now be in edit mode with raw markdown
      const editable = page.locator('[contenteditable="true"]');
      await expect(editable).toBeVisible({ timeout: 2000 });
      const text = await editable.textContent();
      expect(text).toBe(markdown);
    });
  });

  test.describe('Image-only block detection', () => {
    test('block with text + image is NOT image-only (renders inline)', async ({ page }) => {
      await updateBlockText(page, blockId, 'Look at this: ![cat](https://example.com/cat.png)');
      await wait(page, 200);

      // Should NOT have image-block-content wrapper
      const imageBlock = page.locator(`div.block[data-block-id="${blockId}"] .image-block-content`);
      await expect(imageBlock).toHaveCount(0);

      // Should have inline img
      const inlineImg = page.locator(`div.block[data-block-id="${blockId}"] img.inline-image`);
      await expect(inlineImg).toHaveCount(1);
    });

    test('plain text block has no image elements', async ({ page }) => {
      await updateBlockText(page, blockId, 'Just regular text');
      await wait(page, 200);

      const imageBlock = page.locator(`div.block[data-block-id="${blockId}"] .image-block-content`);
      await expect(imageBlock).toHaveCount(0);

      const img = page.locator(`div.block[data-block-id="${blockId}"] img`);
      await expect(img).toHaveCount(0);
    });

    test('link syntax (no !) does not create image', async ({ page }) => {
      await updateBlockText(page, blockId, '[link](https://example.com)');
      await wait(page, 200);

      const img = page.locator(`div.block[data-block-id="${blockId}"] img`);
      await expect(img).toHaveCount(0);
    });
  });

  test.describe('Inline images in text', () => {
    test('image within text renders as inline-image', async ({ page }) => {
      await updateBlockText(page, blockId, 'Before ![icon](https://example.com/icon.png) after');
      await wait(page, 200);

      const inlineImg = page.locator(`div.block[data-block-id="${blockId}"] img.inline-image`);
      await expect(inlineImg).toHaveCount(1);
      expect(await inlineImg.getAttribute('alt')).toBe('icon');
    });

    test('multiple inline images render correctly', async ({ page }) => {
      await updateBlockText(
        page,
        blockId,
        '![a](https://example.com/a.png) and ![b](https://example.com/b.png)'
      );
      await wait(page, 200);

      const imgs = page.locator(`div.block[data-block-id="${blockId}"] img.inline-image`);
      await expect(imgs).toHaveCount(2);
    });
  });

  test.describe('Edit mode round-trip', () => {
    test('image markdown survives edit → view → edit cycle', async ({ page }) => {
      const markdown = '![test](https://example.com/test.png){width=300}';

      await updateBlockText(page, blockId, markdown);
      await wait(page, 200);

      // Enter edit mode
      await enterEditMode(page, blockId);
      await wait(page, 100);

      // Verify raw markdown is shown
      const editable = page.locator('[contenteditable="true"]');
      const text = await editable.textContent();
      expect(text).toBe(markdown);

      // Exit edit mode
      await exitEditMode(page);
      await wait(page, 200);

      // Should be back to image-block-content rendering
      const imageBlock = page.locator(`div.block[data-block-id="${blockId}"] .image-block-content`);
      await expect(imageBlock).toHaveCount(1);

      // Width should still be present on the img
      const img = imageBlock.locator('img');
      const style = await img.getAttribute('style');
      expect(style).toContain('300px');
    });

    test('setting image text via helper creates image block after re-render', async ({ page }) => {
      // First set some normal text, then change to image markdown
      await updateBlockText(page, blockId, 'normal text');
      await wait(page, 200);

      // Now update to image markdown
      await updateBlockText(page, blockId, '![demo](https://example.com/demo.png)');
      await wait(page, 300);

      // Should render with image-block-content wrapper
      const imageBlock = page.locator(`div.block[data-block-id="${blockId}"] .image-block-content`);
      await expect(imageBlock).toHaveCount(1);
    });

    test('editing image block preserves width after exit', async ({ page }) => {
      await updateBlockText(page, blockId, '![](https://example.com/img.png){width=500}');
      await wait(page, 200);

      // Enter edit, add alt text, exit
      await enterEditMode(page, blockId);
      await wait(page, 100);

      const _editable = page.locator('[contenteditable="true"]');
      // Select all and retype with alt text but keep width
      await pressKeyCombo(page, 'a', [modKey]);
      await page.keyboard.type('![updated](https://example.com/img.png){width=500}');
      await wait(page);

      await exitEditMode(page);
      await wait(page, 200);

      // Verify img has updated alt and preserved width
      const img = page.locator(`div.block[data-block-id="${blockId}"] .image-block-content img`);
      await expect(img).toHaveCount(1);
      expect(await img.getAttribute('alt')).toBe('updated');
      const style = await img.getAttribute('style');
      expect(style).toContain('500px');
    });
  });
});
