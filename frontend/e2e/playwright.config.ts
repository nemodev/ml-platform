import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.BASE_URL ?? 'http://172.16.100.10:30080';
const headless = process.env.UI_TEST_HEADLESS !== 'false';

export default defineConfig({
  testDir: './specs',
  fullyParallel: false,
  retries: 0,
  timeout: 8 * 60 * 1000,
  expect: {
    timeout: 30_000
  },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }]
  ],
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    headless,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    viewport: { width: 1440, height: 900 },
    ignoreHTTPSErrors: true
  }
});
