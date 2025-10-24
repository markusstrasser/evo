// Manual debug - run with browser visible to see console
// npx playwright test test-browser/manual-debug.spec.js --headed

import { test, expect } from '@playwright/test';

test('Debug Tab indent with console', async ({ page }) => {
  // Listen to console
  page.on('console', msg => {
    console.log(`[BROWSER] ${msg.type()}: ${msg.text()}`);
  });

  await page.goto('http://localhost:8080/blocks.html');
  await page.waitForSelector('.app');

  // Click third top-level block ("Third block" - can be indented under "Second block")
  const thirdBlock = page.locator('.block').nth(3);  // "c" - third top-level block
  await thirdBlock.click();
  await page.waitForTimeout(500);

  console.log('\n=== Pressing Tab ===\n');

  // Press Tab
  await page.keyboard.press('Tab');
  await page.waitForTimeout(500);

  console.log('\n=== Done ===\n');

  // Verify the margin increased (indented)
  const afterMargin = await thirdBlock.evaluate(el => el.style.marginLeft);
  console.log(`Margin after indent: ${afterMargin}`);

  // Keep browser open
  await page.waitForTimeout(5000);
});
