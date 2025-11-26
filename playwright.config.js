// @ts-check
import { defineConfig, devices } from '@playwright/test';

/**
 * Headless mode configuration:
 * - Default: headless=true (browsers run in background, no UI)
 * - Set PLAYWRIGHT_HEADLESS=false to see browser windows
 * - CI always runs headless
 *
 * npm scripts:
 * - npm run test:e2e          → headless (default)
 * - npm run test:e2e:headed   → shows browser windows
 * - npm run test:e2e:debug    → debug mode with browser visible
 * - npm run test:e2e:ui       → Playwright UI mode
 */
const headless =
  process.env.PLAYWRIGHT_HEADLESS !== 'false';  // Default to headless=true

const shouldStartWebServer = process.env.PW_SKIP_WEB_SERVER !== '1';

export default defineConfig({
  testDir: './test/e2e',
  testMatch: '**/*.spec.{js,ts}',

  // Run tests in parallel
  fullyParallel: true,

  // Fail fast on CI, retry locally
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,

  // Structured output for AI parsing
  reporter: [
    ['list'],  // Console output
    ['json', { outputFile: 'test-results/e2e-results.json' }],
    ['html', { open: 'never' }]  // HTML report for manual review
  ],

  use: {
    // Base URL for tests
    baseURL: 'http://localhost:8080',

    // Headless mode (CI or env override)
    headless,

    // Capture on failure
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',

    // Browser viewport
    viewport: { width: 1280, height: 720 }
  },

  // Cross-browser testing
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] }
    },
    {
      name: 'webkit',  // Safari engine
      use: { ...devices['Desktop Safari'] }
    },
    {
      name: 'Mobile Safari',
      use: { ...devices['iPhone 13'] }
    }
  ],

  // Dev server
  webServer: shouldStartWebServer ? {
    command: 'bb dev',
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
    timeout: 120000
  } : undefined
});
