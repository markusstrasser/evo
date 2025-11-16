import { test, expect } from '@playwright/test';

/**
 * Debug test to capture console output and verify test mode detection
 */

test.describe('Debug Test Mode Detection', () => {
  test('capture console logs on page load', async ({ page }) => {
    const consoleLogs: string[] = [];

    // Capture all console messages
    page.on('console', msg => {
      consoleLogs.push(`[${msg.type()}] ${msg.text()}`);
    });

    // Load with test mode
    await page.goto('/?test=true');

    // Wait a bit for initialization
    await page.waitForTimeout(2000);

    // Print all console logs
    console.log('=== BROWSER CONSOLE OUTPUT ===');
    consoleLogs.forEach(log => console.log(log));
    console.log('=== END CONSOLE OUTPUT ===');

    // Check DOM state
    const blockCount = await page.locator('[data-block-id]').count();
    console.log(`Block count: ${blockCount}`);

    // Check if page loaded
    const bodyText = await page.locator('body').textContent();
    console.log(`Body text length: ${bodyText?.length || 0}`);

    // Take screenshot for manual inspection
    await page.screenshot({ path: 'test-results/debug-test-mode.png', fullPage: true });

    // Check DB state via window object
    const dbState = await page.evaluate(() => {
      const win = window as any;
      if (win.DEBUG && win.DEBUG.state) {
        const db = win.DEBUG.state();
        return {
          nodeCount: Object.keys(db.nodes || {}).length,
          currentPage: db.nodes?.['session/ui']?.props?.['current-page'],
          roots: db.roots
        };
      }
      return null;
    });
    console.log('DB State:', JSON.stringify(dbState, null, 2));
  });
});
