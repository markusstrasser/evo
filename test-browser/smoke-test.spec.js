// Smoke test - verify core tree editing works in browser
// Run: npx shadow-cljs watch blocks-ui
// Then: npx playwright test test-browser/smoke-test.spec.js --headed

import { test, expect } from '@playwright/test';

test.describe('Tree Editor - Core Functionality', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8080/blocks.html');
    await page.waitForSelector('.app', { timeout: 5000 });
  });

  test('SMOKE: UI loads with blocks', async ({ page }) => {
    const blocks = page.locator('.block');
    const count = await blocks.count();
    console.log(`Found ${count} blocks`);
    expect(count).toBeGreaterThan(0);
  });

  test('SMOKE: Click selects block', async ({ page }) => {
    const firstBlock = page.locator('.block').first();
    await firstBlock.click();

    // Check if has focus styling
    const bgColor = await firstBlock.evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    console.log(`Selected block background: ${bgColor}`);
    expect(bgColor).not.toBe('rgba(0, 0, 0, 0)'); // Has some background
  });

  test('SMOKE: Tab indents block', async ({ page }) => {
    const blocks = page.locator('.block');
    // Click block "c" (3rd top-level block) which CAN be indented
    // Structure: a, b (with child d), c
    // Index: 0=a, 1=b, 2=d, 3=c
    const thirdTopBlock = blocks.nth(3);

    await thirdTopBlock.click();

    const beforeMargin = await thirdTopBlock.evaluate(el => el.style.marginLeft);
    console.log(`Before indent margin: ${beforeMargin}`);

    await page.keyboard.press('Tab');
    await page.waitForTimeout(200);

    const afterMargin = await thirdTopBlock.evaluate(el => el.style.marginLeft);
    console.log(`After indent margin: ${afterMargin}`);

    const before = parseInt(beforeMargin) || 0;
    const after = parseInt(afterMargin) || 0;
    expect(after).toBeGreaterThan(before);
  });

  test('SMOKE: Debug panel shows state', async ({ page }) => {
    // Just verify debug panel exists and updates
    await page.locator('.block').first().click();

    const debugText = await page.textContent('body');
    console.log('Debug panel present:', debugText.includes('Selection:'));
    expect(debugText).toContain('Selection:');
  });
});
