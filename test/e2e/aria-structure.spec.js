import { expect, test } from '@playwright/test';

test.describe('ARIA Structure', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('initial editor shell exposes stable landmarks and controls', async ({ page }) => {
    await expect(page.getByRole('navigation', { name: 'Page navigation' })).toBeVisible();
    await expect(page.getByRole('main')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Open Folder' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Toggle reading mode' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Toggle keyboard shortcuts' })).toBeVisible();
  });

  test('hotkeys panel has a discoverable structural surface', async ({ page }) => {
    await page.getByRole('button', { name: 'Toggle keyboard shortcuts' }).click();

    const panel = page.locator('.hotkeys-panel');
    await expect(panel).toBeVisible();
    await expect(panel).toContainText('Shortcuts');
    await expect(panel).toContainText('Navigation');
    await expect(page.getByRole('button', { name: 'Toggle keyboard shortcuts' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
  });

  test('quick switcher dialog exposes search and results structure', async ({ page }) => {
    await page.evaluate(() => {
      window.TEST_HELPERS.dispatchIntent({ type: 'create-page', title: 'Structure Target' });
      window.TEST_HELPERS.dispatchIntent({ type: 'toggle-quick-switcher' });
    });

    const dialog = page.locator('dialog.quick-switcher');
    await expect(dialog).toBeVisible();
    await expect(page.getByPlaceholder('Search pages...')).toBeFocused();

    await page.getByPlaceholder('Search pages...').fill('structure');
    await expect(dialog.locator('.quick-switcher-results')).toContainText('Structure Target');
    await expect(dialog.locator('.quick-switcher-item')).toHaveCount(1);
  });
});
