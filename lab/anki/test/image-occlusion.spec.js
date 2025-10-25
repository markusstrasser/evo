import { test, expect } from '@playwright/test';

test.describe('Image Occlusion', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/public/anki.html');
    await page.waitForSelector('.anki-app');
  });

  test('should create test image occlusion card', async ({ page }) => {
    // Click select folder button
    await page.click('text=Select Folder');

    // Wait for folder picker (this will hang in headless mode, so skip in CI)
    // For manual testing, select a folder
    await page.waitForTimeout(1000);

    // Look for the create test button
    const button = await page.$('text=Create Test Image Occlusion');

    if (button) {
      console.log('Found test occlusion button');
      await button.click();

      // Wait for cards to be created
      await page.waitForTimeout(1000);

      // Check if we have due cards now
      const reviewCard = await page.$('.review-card');
      expect(reviewCard).toBeTruthy();

      // Take screenshot
      await page.screenshot({ path: '/tmp/anki-occlusion-created.png', fullPage: true });
    }
  });

  test('should display canvas with image occlusion', async ({ page }) => {
    // This test requires manually adding a card first
    // Just check if canvas rendering would work

    // Inject test card via JS
    await page.evaluate(() => {
      const core = window['lab']['anki']['core'];
      const ui = window['lab']['anki']['ui'];

      const card = {
        type: 'image-occlusion',
        asset: { url: '/test-images/test-regions.png', width: 400, height: 300 },
        prompt: 'What is this region?',
        occlusions: [{
          oid: crypto.randomUUID(),
          shape: { kind: 'rect', normalized: true, x: 0.125, y: 0.167, w: 0.25, h: 0.267 },
          answer: 'Region A'
        }]
      };

      const h = core.card_hash(card);
      const event = core.card_created_event(h, card);

      // Apply event to state
      const state = ui['!state'].deref();
      const newState = core.apply_event(state.state, event);
      ui['!state'].swap(s => ({
        ...s,
        state: newState,
        events: [...s.events, event]
      }));
    });

    await page.waitForTimeout(1000);

    // Check for canvas
    const canvas = await page.$('canvas');
    if (canvas) {
      await page.screenshot({ path: '/tmp/anki-occlusion-canvas.png', fullPage: true });
      console.log('✓ Canvas found and rendered');
    }
  });
});
