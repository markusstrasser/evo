// @ts-check
import { defineConfig, devices } from '@playwright/test';

const headless =
  process.env.PLAYWRIGHT_HEADLESS
    ? process.env.PLAYWRIGHT_HEADLESS !== 'false'
    : !!process.env.CI;

export default defineConfig({
  testDir: './test-browser',
  testMatch: '**/*.spec.js',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: 'list',

  use: {
    baseURL: 'http://localhost:8080',
    headless, // Default headless in CI; override with PLAYWRIGHT_HEADLESS env var
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // Don't start a web server - shadow-cljs already running
  webServer: undefined,
});
