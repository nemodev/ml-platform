import { expect, Locator, Page, test } from '@playwright/test';

const username = process.env.UI_TEST_USERNAME ?? 'scientist1';
const password = process.env.UI_TEST_PASSWORD ?? 'password1';
type AuthMonitor = { requestCount: number };

test.describe('ML Platform portal end-to-end journeys', () => {
  test('@smoke login and core portal pages are healthy', async ({ page }) => {
    const auth: AuthMonitor = { requestCount: 0 };
    page.on('request', (request) => {
      if (request.url().includes('/realms/ml-platform/protocol/openid-connect/auth?')) {
        auth.requestCount += 1;
      }
    });

    await login(page);
    await assertShellReady(page);
    await waitForAuthStability(page, auth, 'post-login');

    await verifySmokeJourneys(page, auth);

    await assertNoAuthLoop(page, auth, 'end-of-smoke');
  });

  test('@full complete feature workflows are healthy from user perspective', async ({ page }) => {
    const auth: AuthMonitor = { requestCount: 0 };
    page.on('request', (request) => {
      if (request.url().includes('/realms/ml-platform/protocol/openid-connect/auth?')) {
        auth.requestCount += 1;
      }
    });

    await login(page);
    await assertShellReady(page);
    await waitForAuthStability(page, auth, 'post-login');

    await verifySmokeJourneys(page, auth);
    await verifyNotebooksJourney(page, auth, { terminateAtEnd: false });
    await verifyFullPipelinesJourney(page, auth);
    await cleanupNotebookWorkspace(page, auth);

    await assertNoAuthLoop(page, auth, 'end-of-full');
  });
});

async function login(page: Page): Promise<void> {
  await page.goto('/', { waitUntil: 'domcontentloaded' });

  const keycloakEntryButton = page.getByRole('button', { name: /sign in with keycloak/i });
  if (await isVisible(keycloakEntryButton)) {
    await keycloakEntryButton.click();
  }

  const usernameInput = page.locator('input[name="username"], input#username').first();
  if (await isVisible(usernameInput, 20_000)) {
    await usernameInput.fill(username);
    await page.locator('input[name="password"], input#password').first().fill(password);
    await page.locator('button[type="submit"], input[type="submit"]').first().click();
  }
}

async function assertShellReady(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { name: 'ML Platform' })).toBeVisible({ timeout: 90_000 });
  await expect(page.getByRole('link', { name: 'Dashboard' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Notebooks' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Experiments' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Models' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Pipelines' })).toBeVisible();
  await expect(page.locator('.error-banner')).toHaveCount(0);
}

async function assertNoAuthLoop(page: Page, auth: AuthMonitor, stage: string): Promise<void> {
  await page.waitForTimeout(1_000);

  const currentUrl = page.url();
  expect(currentUrl, `${stage}: callback URL parameters were not cleared`).not.toContain('id_token=');
  expect(currentUrl, `${stage}: callback URL parameters were not cleared`).not.toContain('access_token=');

  const isCallbackUrl = /[?#].*(state=|session_state=|code=)/.test(currentUrl);
  expect(isCallbackUrl, `${stage}: app stayed in callback URL state`).toBeFalsy();

  expect(
    auth.requestCount,
    `${stage}: repeated authorization requests detected (possible redirect cycle)`
  ).toBeLessThanOrEqual(6);
}

async function waitForAuthStability(page: Page, auth: AuthMonitor, stage: string): Promise<void> {
  const timeoutMs = 15_000;
  const quietPeriodMs = 2_000;
  const maxAuthRequests = 6;
  const startedAt = Date.now();

  let lastAuthRequestCount = auth.requestCount;
  let lastChangeAt = Date.now();

  while (Date.now() - startedAt < timeoutMs) {
    const currentUrl = page.url();
    const isCallbackUrl = /[?#].*(state=|session_state=|code=|id_token=|access_token=)/.test(currentUrl);

    if (auth.requestCount !== lastAuthRequestCount) {
      lastAuthRequestCount = auth.requestCount;
      lastChangeAt = Date.now();
    }

    if (isCallbackUrl) {
      lastChangeAt = Date.now();
    }

    if (auth.requestCount > maxAuthRequests) {
      throw new Error(
        `${stage}: redirect cycle detected (${auth.requestCount} authorization requests). Current URL: ${currentUrl}`
      );
    }

    if (!isCallbackUrl && Date.now() - lastChangeAt >= quietPeriodMs) {
      return;
    }

    await page.waitForTimeout(250);
  }

  throw new Error(
    `${stage}: auth state did not stabilize in ${timeoutMs}ms (auth requests: ${auth.requestCount}, current URL: ${page.url()})`
  );
}

async function gotoSection(page: Page, path: string, heading: string, auth: AuthMonitor): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await waitForAuthStability(page, auth, `route ${path}`);
  await expect(page.getByRole('heading', { name: heading, exact: true }).first()).toBeVisible({ timeout: 30_000 });
}

async function verifySmokeJourneys(page: Page, auth: AuthMonitor): Promise<void> {
  await gotoSection(page, '/dashboard', 'Dashboard', auth);

  await gotoSection(page, '/notebooks', 'Notebooks', auth);
  await expect(page.locator('.workspace-page')).toBeVisible();
  const notebooksErrorCard = page.locator('.workspace-page .error-card');
  if (await isVisible(notebooksErrorCard, 2_000)) {
    throw new Error(`Notebooks page is in failed state: ${(await notebooksErrorCard.innerText()).trim()}`);
  }

  await gotoSection(page, '/experiments', 'Experiments', auth);
  const experimentsErrorCard = page.locator('.experiments-page .error-card');
  await expect(experimentsErrorCard).toHaveCount(0);
  await page.waitForTimeout(2_000);
  if (await isVisible(experimentsErrorCard, 500)) {
    throw new Error(`Experiments page is in failed state: ${(await experimentsErrorCard.innerText()).trim()}`);
  }

  await gotoSection(page, '/models', 'Models', auth);
  await expect(page.getByRole('heading', { name: 'Registered Models' })).toBeVisible();
  await expect(page.getByText(/Unable to load registered models/i)).toHaveCount(0);

  const modelRows = page.locator('.models-page .card table tbody tr');
  const modelRowCount = await modelRows.count();
  if (modelRowCount === 0) {
    await expect(page.getByText('No models found. Register a model from Notebooks first.')).toBeVisible();
  } else {
    await expect(modelRows.first()).toBeVisible();
  }

  await expect(page.getByRole('heading', { name: 'Deployments' })).toBeVisible();
  await expect(page.getByText(/Unable to load deployments/i)).toHaveCount(0);

  await gotoSection(page, '/pipelines', 'Pipelines', auth);
  await expect(page.getByText(/Unable to load pipeline runs/i)).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Run Pipeline' })).toBeVisible();
}

async function verifyNotebooksJourney(
  page: Page,
  auth: AuthMonitor,
  options: { terminateAtEnd?: boolean } = {}
): Promise<void> {
  const terminateAtEnd = options.terminateAtEnd ?? true;

  await gotoSection(page, '/notebooks', 'Notebooks', auth);
  await expect(page.locator('.workspace-page')).toBeVisible();

  const launchButton = page.getByRole('button', { name: 'Launch Workspace' });
  if (await isVisible(launchButton, 4_000)) {
    await launchButton.click();
  }

  await waitForNotebookState(page, 180_000);

  if (terminateAtEnd) {
    const terminateButton = page.getByRole('button', { name: 'Terminate Workspace' });
    if (await isVisible(terminateButton, 5_000)) {
      await terminateButton.click();
      await expect(launchButton).toBeVisible({ timeout: 90_000 });
    }
  }
}

async function cleanupNotebookWorkspace(page: Page, auth: AuthMonitor): Promise<void> {
  await gotoSection(page, '/notebooks', 'Notebooks', auth);
  const terminateButton = page.getByRole('button', { name: 'Terminate Workspace' });
  if (await isVisible(terminateButton, 5_000)) {
    await terminateButton.click();
    await expect(page.getByRole('button', { name: 'Launch Workspace' })).toBeVisible({ timeout: 90_000 });
  }
}

async function verifyFullPipelinesJourney(page: Page, auth: AuthMonitor): Promise<void> {
  await gotoSection(page, '/pipelines', 'Pipelines', auth);
  await expect(page.getByText(/Unable to load pipeline runs/i)).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Run Pipeline' })).toBeVisible();

  await page.getByRole('button', { name: 'Run Pipeline' }).click();
  await expect(page.getByRole('heading', { name: 'Run Notebook Pipeline' })).toBeVisible();

  const loadingNotebooks = page.getByText('Loading notebooks...');
  if (await isVisible(loadingNotebooks, 3_000)) {
    await expect(loadingNotebooks).toBeHidden({ timeout: 60_000 });
  }

  const triggerDialogError = page.locator('.dialog-card .error').first();
  if (await isVisible(triggerDialogError, 1_000)) {
    throw new Error(`Pipeline dialog failed to load: ${(await triggerDialogError.innerText()).trim()}`);
  }

  const notebookOptions = page.locator('.dialog-card select option');
  const notebookOptionCount = await notebookOptions.count();
  expect(notebookOptionCount, 'Pipeline trigger should have at least one notebook option').toBeGreaterThan(0);

  await page.getByRole('button', { name: 'Trigger' }).click();
  await expect(page.getByRole('heading', { name: 'Run Notebook Pipeline' })).toHaveCount(0, { timeout: 30_000 });

  await expect(page.locator('.pipelines-page table tbody tr').first()).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('heading', { name: 'Run Detail' })).toBeVisible({ timeout: 20_000 });
}

async function waitForNotebookState(page: Page, timeoutMs: number): Promise<void> {
  const startedAt = Date.now();
  const runningFrame = page.locator('iframe[title="JupyterLab"]');
  const errorCard = page.locator('.workspace-page .error-card');

  while (Date.now() - startedAt < timeoutMs) {
    if (await isVisible(runningFrame, 1_000)) {
      return;
    }

    if (await isVisible(errorCard, 1_000)) {
      const message = (await errorCard.innerText()).trim();
      throw new Error(`Notebook workspace failed: ${message}`);
    }

    await page.waitForTimeout(2_000);
  }

  throw new Error('Timed out waiting for notebook workspace to become running.');
}

async function isVisible(locator: Locator, timeout = 5_000): Promise<boolean> {
  try {
    await locator.waitFor({ state: 'visible', timeout });
    return true;
  } catch {
    return false;
  }
}
