// Test Enter key creates new blocks
import { test, expect } from '@playwright/test';

test('Enter key creates new block', async ({ page }) => {
  await page.goto('http://localhost:8080/blocks.html');
  await page.waitForSelector('.app');

  // Get initial block count
  const initialCount = await page.locator('.block').count();
  console.log(`Initial block count: ${initialCount}`);

  // Click first block to select it
  await page.locator('.block').first().click();
  await page.waitForTimeout(200);

  // Press Enter to create new block
  await page.keyboard.press('Enter');
  await page.waitForTimeout(300);

  // Check new block was created
  const afterCount = await page.locator('.block').count();
  console.log(`After Enter block count: ${afterCount}`);

  expect(afterCount).toBe(initialCount + 1);
});

test('Up/Down arrows navigate blocks', async ({ page }) => {
  await page.goto('http://localhost:8080/blocks.html');
  await page.waitForSelector('.app');

  // Click first block
  const blocks = page.locator('.block');
  await blocks.nth(0).click();
  await page.waitForTimeout(200);

  // Check first block is focused (blue background)
  let focused = await blocks.nth(0).evaluate(el =>
    window.getComputedStyle(el).backgroundColor
  );
  console.log(`First block bg: ${focused}`);
  expect(focused).toBe('rgb(179, 217, 255)');

  // Press Down arrow
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(200);

  // Check second block is now focused
  focused = await blocks.nth(1).evaluate(el =>
    window.getComputedStyle(el).backgroundColor
  );
  console.log(`Second block bg after Down: ${focused}`);
  expect(focused).toBe('rgb(179, 217, 255)');

  // Press Up arrow
  await page.keyboard.press('ArrowUp');
  await page.waitForTimeout(200);

  // Check first block is focused again
  focused = await blocks.nth(0).evaluate(el =>
    window.getComputedStyle(el).backgroundColor
  );
  console.log(`First block bg after Up: ${focused}`);
  expect(focused).toBe('rgb(179, 217, 255)');
});

test('Text editing works in contentEditable', async ({ page }) => {
  await page.goto('http://localhost:8080/blocks.html');
  await page.waitForSelector('.app');

  // Click first block's text span
  const firstBlockText = page.locator('.block').first().locator('span[contenteditable]');
  await firstBlockText.click();
  await page.waitForTimeout(200);

  // Clear and type new text
  await page.keyboard.press('Meta+A'); // Select all
  await page.keyboard.type('Hello World');
  await page.waitForTimeout(300);

  // Verify text changed
  const text = await firstBlockText.textContent();
  console.log(`Text after typing: ${text}`);
  expect(text).toBe('Hello World');
});
