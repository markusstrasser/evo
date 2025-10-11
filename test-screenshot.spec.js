const { test } = require('@playwright/test');

test('visual test of image occlusion', async ({ page }) => {
  await page.goto('http://localhost:8081/test-occlusion.html');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await page.screenshot({ path: '/tmp/occlusion-visual-test.png', fullPage: true });
  console.log('\n✓ Screenshot saved to /tmp/occlusion-visual-test.png\n');
});
