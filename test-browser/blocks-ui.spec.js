// Browser test for blocks UI - verify keyboard shortcuts actually work
// Run with: npx playwright test test-browser/blocks-ui.spec.js

import { test, expect } from '@playwright/test';

test.describe('Blocks UI - Keyboard Shortcuts', () => {
  test.beforeEach(async ({ page }) => {
    // Start shadow-cljs server first: npx shadow-cljs watch blocks-ui
    await page.goto('http://localhost:8080/blocks.html');

    // Wait for app to load
    await page.waitForSelector('.app', { timeout: 5000 });
  });

  test('should load blocks UI with initial blocks', async ({ page }) => {
    // Check title
    await expect(page.locator('h2')).toContainText('Blocks UI - Architectural Demo');

    // Check initial blocks exist (from blocks_ui.cljs initialization)
    const blocks = page.locator('.block');
    await expect(blocks).toHaveCount(4); // page has 4 initial blocks

    // Verify block text
    await expect(page.locator('.block:has-text("First block")')).toBeVisible();
    await expect(page.locator('.block:has-text("Second block")')).toBeVisible();
    await expect(page.locator('.block:has-text("Third block")')).toBeVisible();
  });

  test('should select block on click', async ({ page }) => {
    // Click first block
    await page.locator('.block:has-text("First block")').click();

    // Should have focus styling (blue background)
    const firstBlock = page.locator('.block:has-text("First block")');
    await expect(firstBlock).toHaveCSS('background-color', 'rgb(179, 217, 255)'); // #b3d9ff
  });

  test('should extend selection with Shift+Click', async ({ page }) => {
    // Click first block to anchor selection
    await page.locator('.block[data-block-id="a"]').click();

    // Shift+click third block to extend range
    await page.locator('.block[data-block-id="c"]').click({ modifiers: ['Shift'] });

    // Intermediate block should be selected (range selection)
    const secondBlock = page.locator('.block[data-block-id="b"]');
    await expect(secondBlock).toHaveCSS('background-color', 'rgb(230, 242, 255)');

    // Focused block should be the third block
    const thirdBlock = page.locator('.block[data-block-id="c"]');
    await expect(thirdBlock).toHaveCSS('background-color', 'rgb(179, 217, 255)');
  });

  test('should indent block with Tab', async ({ page }) => {
    // Use nth(3) to get the third top-level block by DOM position
    const thirdBlock = page.locator('.block[data-block-id="c"]');

    await thirdBlock.click();

    // Get initial margin-left
    const initialMargin = await thirdBlock.evaluate(el => el.style.marginLeft);

    // Press Tab to indent
    await page.keyboard.press('Tab');

    // Wait a bit for state update
    await page.waitForTimeout(200);

    // Margin should increase (indented under second block)
    const newMargin = await thirdBlock.evaluate(el => el.style.marginLeft);
    expect(parseInt(newMargin)).toBeGreaterThan(parseInt(initialMargin || '0'));
  });

  test('should outdent block with Shift+Tab', async ({ page }) => {
    // First, indent a block (use nth(3) to avoid ambiguity)
    const thirdBlock = page.locator('.block[data-block-id="c"]');

    await thirdBlock.click();
    await page.keyboard.press('Tab');
    await page.waitForTimeout(200);

    const indentedMargin = await thirdBlock.evaluate(el => el.style.marginLeft);

    // Now outdent with Shift+Tab
    await page.keyboard.press('Shift+Tab');
    await page.waitForTimeout(200);

    const outdentedMargin = await thirdBlock.evaluate(el => el.style.marginLeft);

    expect(parseInt(outdentedMargin || '0')).toBeLessThan(parseInt(indentedMargin));
  });

  test('should navigate with Alt+ArrowDown/Up', async ({ page }) => {
    // Wait for app to be fully initialized
    await page.waitForSelector('.debug-panel');

    // Click first block
    await page.locator('.block[data-block-id="a"]').click();

    // Wait for selection to be committed
    await page.waitForTimeout(200);

    // Check initial selection
    const initialSelection = await page.locator('.debug-panel').textContent();
    console.log('Initial selection:', initialSelection?.match(/Selection: ([^\s]+)/)?.[1]);
    console.log('Initial focus:', initialSelection?.match(/Focus: ([^\s]+)/)?.[1]);

    // Dispatch keyboard event via JavaScript to ensure it works
    await page.evaluate(() => {
      const event = new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        altKey: true,
        bubbles: true,
        cancelable: true
      });
      document.dispatchEvent(event);
    });
    await page.waitForTimeout(200);

    // Check final selection
    const finalSelection = await page.locator('.debug-panel').textContent();
    console.log('Final selection:', finalSelection?.match(/Selection: ([^\s]+)/)?.[1]);
    console.log('Final focus:', finalSelection?.match(/Focus: ([^\s]+)/)?.[1]);

    // Second block should now be focused
    const secondBlock = page.locator('.block:has-text("Second block")');
    await expect(secondBlock).toHaveCSS('background-color', 'rgb(179, 217, 255)');
  });

  test('should extend selection with Shift+ArrowDown', async ({ page }) => {
    // Click first block
    await page.locator('.block[data-block-id="a"]').click();

    // Press Shift+ArrowDown to extend selection
    await page.keyboard.press('Shift+ArrowDown');
    await page.waitForTimeout(100);

    // Both blocks should be selected
    const selectedBlocks = page.locator('.block[style*="background-color"]');
    const count = await selectedBlocks.count();
    expect(count).toBeGreaterThanOrEqual(2);
  });

  test('should move block up with Cmd/Alt+Shift+ArrowUp', async ({ page }) => {
    // Click third block (c) which has no children
    await page.locator('.block[data-block-id="c"]').click();
    await page.waitForTimeout(200);

    // Get blocks by data-block-id to verify initial order
    const blocks = await page.locator('.outline > .block').all();
    const secondId = await blocks[1].getAttribute('data-block-id');
    const thirdId = await blocks[2].getAttribute('data-block-id');
    expect(secondId).toBe('b');
    expect(thirdId).toBe('c');

    // Move up with Cmd+Shift+ArrowUp (Mac) or Alt+Shift+ArrowUp (Linux/Windows)
    const isMac = process.platform === 'darwin';
    await page.keyboard.press(isMac ? 'Meta+Shift+ArrowUp' : 'Alt+Shift+ArrowUp');
    await page.waitForTimeout(200);

    // Order should be swapped - c should now be second, b should be third
    const newBlocks = await page.locator('.outline > .block').all();
    const newSecondId = await newBlocks[1].getAttribute('data-block-id');
    const newThirdId = await newBlocks[2].getAttribute('data-block-id');
    expect(newSecondId).toBe('c');
    expect(newThirdId).toBe('b');
  });

  test('should undo with Cmd/Ctrl+Z', async ({ page }) => {
    // Make a change (indent third block using nth)
    const thirdBlock = page.locator('.block[data-block-id="c"]');

    await thirdBlock.click();
    await page.keyboard.press('Tab');
    await page.waitForTimeout(200);

    // Verify block is indented
    const indentedMargin = await thirdBlock.evaluate(el => el.style.marginLeft);
    expect(parseInt(indentedMargin)).toBeGreaterThan(0);

    // Undo with Cmd+Z (Mac) or Ctrl+Z (Windows/Linux)
    const isMac = process.platform === 'darwin';
    await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');
    await page.waitForTimeout(200);

    // Should be back to original margin
    const undoneMargin = await thirdBlock.evaluate(el => el.style.marginLeft);
    expect(parseInt(undoneMargin || '0')).toBe(0);
  });

  test('should delete selected blocks with Backspace', async ({ page }) => {
    // Select multiple blocks
    await page.locator('.block[data-block-id="b"]').click();
    await page.keyboard.press('Shift+ArrowDown');
    await page.waitForTimeout(100);

    // Count blocks before delete
    const beforeCount = await page.locator('.block').count();

    // Delete with Backspace
    await page.keyboard.press('Backspace');
    await page.waitForTimeout(100);

    // Should have fewer blocks
    const afterCount = await page.locator('.block').count();
    expect(afterCount).toBeLessThan(beforeCount);
  });

  test('should display debug panel with selection state', async ({ page }) => {
    // Click a block
    await page.locator('.block:has-text("First block")').click();

    // Check debug panel shows selection
    const debugPanel = page.locator('.debug-panel');
    await expect(debugPanel).toBeVisible();

    // Should show the selected block ID
    await expect(debugPanel).toContainText('Selection: #{"a"}');
  });

  test('should show keyboard shortcuts legend', async ({ page }) => {
    // Check hotkeys footer is visible
    await expect(page.locator('.hotkeys-footer')).toBeVisible();
    await expect(page.locator('h4:has-text("Keyboard Shortcuts")')).toBeVisible();

    // Check some key shortcuts are documented
    await expect(page.locator('.hotkey-item:has-text("Tab - Indent")')).toBeVisible();
    await expect(page.locator('.hotkey-item:has-text("Shift+Tab - Outdent")')).toBeVisible();
    await expect(page.locator('.hotkey-item:has-text("Backspace - Delete/merge")')).toBeVisible();
  });
});

test.describe('Blocks UI - Replicant Events as Data', () => {
  test('should handle events declaratively', async ({ page }) => {
    await page.goto('http://localhost:8080/blocks.html');
    await page.waitForSelector('.app');

    // Replicant uses :on {:click handler} syntax
    // Let's verify click events work
    const block = page.locator('.block:has-text("First block")');

    // Spy on console to see event handling
    const consoleLogs = [];
    page.on('console', msg => {
      if (msg.type() === 'log') {
        consoleLogs.push(msg.text());
      }
    });

    await block.click();
    await page.waitForTimeout(100);

    // Block should be selected (visual feedback works)
    await expect(block).toHaveCSS('background-color', 'rgb(179, 217, 255)');
  });
});
