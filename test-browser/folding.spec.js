const { test, expect } = require('@playwright/test');

test.describe('Folding Features', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8080');
    await page.waitForSelector('[data-block-id]', { timeout: 10000 });
  });

  test('clicking bullet toggles fold state', async ({ page }) => {
    // Find a block with children (should show ▾)
    const parentBlock = page.locator('[data-block-id="b"]');
    const bullet = parentBlock.locator('span').first();

    // Initially expanded - should show ▾
    await expect(bullet).toHaveText('▾');

    // Click to collapse
    await bullet.click();

    // Should now show ▸ (collapsed)
    await expect(bullet).toHaveText('▸');

    // Child block should not be visible
    const childBlock = page.locator('[data-block-id="d"]');
    await expect(childBlock).not.toBeVisible();

    // Click to expand again
    await bullet.click();

    // Should show ▾ again
    await expect(bullet).toHaveText('▾');

    // Child should be visible again
    await expect(childBlock).toBeVisible();
  });

  test('keyboard shortcut Cmd+; toggles fold', async ({ page }) => {
    // Select a block with children
    const parentBlock = page.locator('[data-block-id="b"]');
    await parentBlock.click();

    const bullet = parentBlock.locator('span').first();

    // Initially expanded
    await expect(bullet).toHaveText('▾');

    // Use keyboard shortcut to collapse (Cmd+; on Mac, Ctrl+; on others)
    const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
    await page.keyboard.press(`${modifier}+Semicolon`);

    // Should be collapsed
    await expect(bullet).toHaveText('▸');

    // Use keyboard shortcut again to expand
    await page.keyboard.press(`${modifier}+Semicolon`);

    // Should be expanded again
    await expect(bullet).toHaveText('▾');
  });

  test('Cmd+ArrowDown expands all descendants', async ({ page }) => {
    // First, collapse the parent
    const parentBlock = page.locator('[data-block-id="b"]');
    const bullet = parentBlock.locator('span').first();
    await bullet.click();

    // Verify it's collapsed
    await expect(bullet).toHaveText('▸');

    // Select the block and use Cmd+ArrowDown
    await parentBlock.click();
    const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
    await page.keyboard.press(`${modifier}+ArrowDown`);

    // Should be expanded
    await expect(bullet).toHaveText('▾');

    // Child should be visible
    const childBlock = page.locator('[data-block-id="d"]');
    await expect(childBlock).toBeVisible();
  });

  test('Cmd+ArrowUp collapses block', async ({ page }) => {
    // Select a block with children
    const parentBlock = page.locator('[data-block-id="b"]');
    await parentBlock.click();

    const bullet = parentBlock.locator('span').first();

    // Initially expanded
    await expect(bullet).toHaveText('▾');

    // Use Cmd+ArrowUp to collapse
    const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
    await page.keyboard.press(`${modifier}+ArrowUp`);

    // Should be collapsed
    await expect(bullet).toHaveText('▸');

    // Child should not be visible
    const childBlock = page.locator('[data-block-id="d"]');
    await expect(childBlock).not.toBeVisible();
  });

  test('leaf blocks show simple bullet', async ({ page }) => {
    // Find a leaf block (no children)
    const leafBlock = page.locator('[data-block-id="a"]');
    const bullet = leafBlock.locator('span').first();

    // Should show simple bullet
    await expect(bullet).toHaveText('•');

    // Clicking should not change it
    await bullet.click();
    await expect(bullet).toHaveText('•');
  });
});

test.describe('Smart Editing Features', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8080');
    await page.waitForSelector('[data-block-id]', { timeout: 10000 });
  });

  test('Cmd+Enter toggles checkbox', async ({ page }) => {
    // Create a block with checkbox
    const block = page.locator('[data-block-id="a"]');
    await block.click();
    await page.keyboard.press('Enter');

    // Type checkbox text
    const editableSpan = page.locator('.content-edit').first();
    await editableSpan.type('[ ] Task to complete');
    await page.keyboard.press('Escape');

    // Now use Cmd+Enter to toggle
    await block.click();
    const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
    await page.keyboard.press(`${modifier}+Enter`);

    // Check if text changed to [x]
    const content = page.locator('[data-block-id="a"] .content-view');
    await expect(content).toContainText('[x] Task to complete');

    // Toggle again
    await page.keyboard.press(`${modifier}+Enter`);
    await expect(content).toContainText('[ ] Task to complete');
  });
});
