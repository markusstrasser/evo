// @ts-check
import { defineConfig, devices } from '@playwright/test';

const headless =
  process.env.PLAYWRIGHT_HEADLESS
    ? process.env.PLAYWRIGHT_HEADLESS !== 'false'
    : !!process.env.CI;

export default defineConfig({
  testDir: './test/e2e',
  testMatch: '**/*.spec.js',

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
  webServer: {
    command: 'bb dev',
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
    timeout: 120000
  }
});
