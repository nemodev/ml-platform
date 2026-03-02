import { expect, test as setup } from '@playwright/test';
import fs from 'fs';
import path from 'path';

const username = process.env.UI_TEST_USERNAME ?? 'user1';
const password = process.env.UI_TEST_PASSWORD ?? 'password1';
const authDir = path.join(__dirname, '..', '.auth');
const authFile = path.join(authDir, 'user1.json');
const sessionFile = path.join(authDir, 'sessionStorage.json');

setup('authenticate via Keycloak', async ({ page }) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' });

  // The Angular app may show a "Sign in with Keycloak" button, or redirect
  // directly to the Keycloak login form.
  const keycloakEntryButton = page.getByRole('button', { name: /sign in with keycloak/i });
  if (await isVisible(keycloakEntryButton, 5_000)) {
    await keycloakEntryButton.click();
  }

  // Fill Keycloak login form
  const usernameInput = page.locator('input[name="username"], input#username').first();
  await usernameInput.waitFor({ state: 'visible', timeout: 20_000 });
  await usernameInput.fill(username);
  await page.locator('input[name="password"], input#password').first().fill(password);
  await page.locator('button[type="submit"], input[type="submit"]').first().click();

  // Wait for the Angular shell to be fully loaded
  await expect(page.locator('.topbar h1')).toHaveText('ML Platform', { timeout: 90_000 });

  // Save cookies + localStorage
  fs.mkdirSync(authDir, { recursive: true });
  await page.context().storageState({ path: authFile });

  // Save sessionStorage (where angular-auth-oidc-client stores OIDC tokens)
  const sessionData = await page.evaluate(() => {
    const entries: Record<string, string> = {};
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i)!;
      entries[key] = sessionStorage.getItem(key)!;
    }
    return entries;
  });
  fs.writeFileSync(sessionFile, JSON.stringify(sessionData));
});

async function isVisible(locator: import('@playwright/test').Locator, timeout = 5_000): Promise<boolean> {
  try {
    await locator.waitFor({ state: 'visible', timeout });
    return true;
  } catch {
    return false;
  }
}
