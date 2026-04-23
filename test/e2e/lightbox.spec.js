// @ts-check
import { expect, test } from '@playwright/test';

const appReady = async (page) => {
  await page.goto('/index.html');
  await page.waitForLoadState('domcontentloaded');
  await page.waitForSelector('[data-block-id]', { timeout: 5000 });
};

const openLightbox = async (page, src = 'blob:mock-lightbox', alt = 'Mock image') => {
  await page.evaluate(
    ([imageSrc, imageAlt]) => {
      window.TEST_HELPERS.showLightbox(imageSrc, imageAlt);
    },
    [src, alt]
  );
  await expect(page.locator('.lightbox-overlay')).toBeVisible();
  await expect(page.locator('body')).toHaveClass(/lightbox-open/);
};

test.describe('Lightbox regressions', () => {
  test.beforeEach(async ({ page }) => {
    await appReady(page);
  });

  test('blocks global shortcuts while open', async ({ page }) => {
    await openLightbox(page);

    const hotkeysPanel = page.locator('.hotkeys-footer');
    await expect(hotkeysPanel).toHaveCount(0);

    const shortcut = process.platform === 'darwin' ? 'Meta+p' : 'Control+p';
    await page.keyboard.press(shortcut);

    await expect(page.locator('.lightbox-overlay')).toBeVisible();
    await expect(hotkeysPanel).toHaveCount(0);
    await expect(page.locator('body')).toHaveClass(/lightbox-open/);
  });

  test('clear-folder closes the lightbox and removes body class', async ({ page }) => {
    await openLightbox(page, 'blob:local-asset', 'Local asset');

    await page.evaluate(() => {
      window.TEST_HELPERS.clearFolder();
    });

    await expect(page.locator('.lightbox-overlay')).toHaveCount(0);
    await expect(page.locator('body')).not.toHaveClass(/lightbox-open/);
  });

  test('resetting the app removes stale lightbox body class', async ({ page }) => {
    await openLightbox(page);

    await page.evaluate(() => {
      window.TEST_HELPERS.resetToEmptyDb();
    });

    await expect(page.locator('.lightbox-overlay')).toHaveCount(0);
    await expect(page.locator('body')).not.toHaveClass(/lightbox-open/);
  });
});
