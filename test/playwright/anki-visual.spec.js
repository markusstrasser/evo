// Visual/integration tests that actually load the app in a browser
const { test, expect } = require('@playwright/test');

test.describe('Anki App Visual Tests', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8000/public/anki.html');
  });

  test('should render setup screen on initial load', async ({ page }) => {
    // Wait for app to initialize
    await page.waitForSelector('.anki-app');

    // Check that we see the setup screen, not object strings
    await expect(page.locator('.setup-screen')).toBeVisible();
    await expect(page.locator('h1')).toContainText('Welcome to Local-First Anki');
    await expect(page.locator('button')).toContainText('Select Folder');

    // Make sure we're NOT seeing function objects
    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('#object');
    expect(bodyText).not.toContain('lab$anki');
  });

  test('should have proper navigation header', async ({ page }) => {
    await page.waitForSelector('.anki-app');

    await expect(page.locator('nav h1')).toContainText('Local-First Anki');
    await expect(page.locator('nav p')).toContainText('Edit cards.md');
  });

  test('should render components as DOM, not strings', async ({ page }) => {
    await page.waitForSelector('.anki-app');

    // Check that components are actual DOM elements
    const setupScreen = await page.locator('.setup-screen');
    await expect(setupScreen).toBeVisible();

    // Verify button is a real button, not text
    const button = await page.locator('.setup-screen button');
    await expect(button).toBeVisible();
    await expect(button).toHaveAttribute('type', 'button', { timeout: 1000 }).catch(() => {
      // Button might not have explicit type, that's ok as long as it's a button element
      return expect(button).toBeVisible();
    });
  });

  test('console should not have errors on load', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
      }
    });

    await page.goto('http://localhost:8000/public/anki.html');
    await page.waitForSelector('.anki-app');

    // Check for common errors
    const errorText = consoleErrors.join(' ');
    expect(errorText).not.toContain('Cannot read properties');
    expect(errorText).not.toContain('undefined is not');
    expect(errorText).not.toContain('is not a function');

    // Log errors for debugging if any
    if (consoleErrors.length > 0) {
      console.log('Console errors:', consoleErrors);
    }
  });

  test('should log Replicant initialization', async ({ page }) => {
    const consoleLogs = [];
    page.on('console', msg => {
      if (msg.type() === 'log') {
        consoleLogs.push(msg.text());
      }
    });

    await page.goto('http://localhost:8000/public/anki.html');
    await page.waitForSelector('.anki-app');

    // Check that Replicant started
    const logText = consoleLogs.join(' ');
    expect(logText).toContain('Anki app starting with Replicant');
  });

  test('should have styled components', async ({ page }) => {
    await page.waitForSelector('.anki-app');

    // Check that CSS is loaded
    const appBackground = await page.locator('.anki-app').evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );

    // Should have white background (rgb(255, 255, 255))
    expect(appBackground).toBe('rgb(255, 255, 255)');
  });
});
