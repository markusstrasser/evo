import { test, expect } from '@playwright/test';

test.describe('Diagnostic', () => {
  test('check what renders on the page', async ({ page }) => {
    await page.goto('http://localhost:8080/');

    // Wait a bit for any rendering
    await page.waitForTimeout(3000);

    // Get the entire page content
    const bodyHTML = await page.evaluate(() => document.body.innerHTML);
    console.log('=== PAGE HTML ===');
    console.log(bodyHTML);

    // Check for errors
    const errors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') {
        errors.push(msg.text());
      }
    });

    page.on('pageerror', error => {
      errors.push(error.message);
    });

    console.log('=== ERRORS ===');
    console.log(errors);

    // Check what elements exist
    const hasRoot = await page.locator('#root').count();
    const hasContentEditable = await page.locator('[contenteditable]').count();
    const hasBlocks = await page.locator('[data-block-id]').count();

    console.log('=== ELEMENT COUNTS ===');
    console.log(`#root: ${hasRoot}`);
    console.log(`[contenteditable]: ${hasContentEditable}`);
    console.log(`[data-block-id]: ${hasBlocks}`);
  });
});
