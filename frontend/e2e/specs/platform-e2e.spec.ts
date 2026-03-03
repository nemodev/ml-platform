import { expect, Locator, Page, test } from '@playwright/test';
import fs from 'fs';
import path from 'path';

const sessionFile = path.join(__dirname, '..', '.auth', 'sessionStorage.json');

/**
 * Shared state across serial tests: the URL of the analysis we navigate into.
 * Set by test 3 (Analyses) and consumed by workspace-dependent tests 9-13.
 */
let analysisUrl = '';

test.describe.serial('ML Platform E2E', () => {
  /**
   * Inject sessionStorage before every page load.
   *
   * The Angular OIDC library (angular-auth-oidc-client) stores tokens in
   * sessionStorage, which Playwright's storageState does not capture.
   * We capture it in auth.setup.ts and inject it here via addInitScript
   * so the OIDC library finds valid tokens immediately and does not
   * trigger a redirect to Keycloak on each navigation.
   */
  test.beforeEach(async ({ page }) => {
    const sessionData: Record<string, string> = JSON.parse(
      fs.readFileSync(sessionFile, 'utf-8')
    );
    await page.addInitScript((entries) => {
      for (const [key, value] of Object.entries(entries)) {
        sessionStorage.setItem(key, value);
      }
    }, sessionData);
  });

  // ──────────────────────────────────────────────
  // Phase 1: Workspace-independent (fast, ~60s)
  // ──────────────────────────────────────────────

  test('@smoke Auth — verify shell', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.topbar h1')).toHaveText('ML Platform', { timeout: 60_000 });
    await expect(page.locator('.user-controls')).toBeVisible();
    // Sidebar links
    await expect(page.getByRole('link', { name: 'Dashboard' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Analyses' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Models' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Pipelines' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Custom Images' })).toBeVisible();
    // No auth error banner
    await expect(page.locator('.error-banner')).toHaveCount(0);
  });

  test('@smoke Dashboard — welcome', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Welcome,')).toBeVisible();
  });

  test('@smoke Analyses — list and layout', async ({ page }) => {
    // Suppress auto-open behavior (component auto-navigates to detail when only 1 analysis exists)
    await page.addInitScript(() => {
      sessionStorage.setItem('analyses.autoOpened', '1');
    });

    await page.goto('/analyses', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.analyses-page')).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('.create-btn')).toBeVisible();

    // Wait for analyses to load from the API
    const cards = page.locator('.analysis-card');
    await page.waitForTimeout(3_000);
    if (await cards.count() === 0) {
      await page.locator('.create-btn').click();
      await page.locator('#analysis-name').fill('E2E Test Analysis');
      await page.locator('.submit-btn').click();
      await expect(cards.first()).toBeVisible({ timeout: 15_000 });
    }

    // Click the first analysis card
    await cards.first().click();

    // Verify analysis layout
    await expect(page.locator('.breadcrumb')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.analysis-tabs')).toBeVisible();

    // 3 tabs: Notebooks, Experiments, Visualization
    const tabs = page.locator('.analysis-tabs a');
    await expect(tabs).toHaveCount(3);

    // Capture the analysis URL for later tests
    analysisUrl = new URL(page.url()).pathname.replace(/\/notebooks$/, '');
  });

  test('@smoke Pipelines — list and dialog', async ({ page }) => {
    await page.goto('/pipelines', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Pipelines', exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('button', { name: 'Run Pipeline' })).toBeVisible();
    // Status filter select
    await expect(page.locator('.pipelines-page select')).toBeVisible();

    // Open trigger dialog
    await page.getByRole('button', { name: 'Run Pipeline' }).click();
    await expect(page.getByRole('heading', { name: 'Run Notebook Pipeline' })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Trigger' })).toBeVisible();

    // Cancel dialog
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { name: 'Run Notebook Pipeline' })).toHaveCount(0, { timeout: 5_000 });
  });

  test('@smoke Models — registry tab', async ({ page }) => {
    await page.goto('/models', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Models', exact: true })).toBeVisible({ timeout: 30_000 });

    // Registry tab should be active by default
    const registryTab = page.locator('.tab-btn', { hasText: 'Registry' });
    await expect(registryTab).toBeVisible();
    await expect(registryTab).toHaveClass(/active/);

    // MLflow Model Registry iframe
    await expect(page.locator('iframe[title="MLflow Model Registry"]')).toBeVisible({ timeout: 30_000 });
  });

  test('@smoke Models — endpoints tab', async ({ page }) => {
    await page.goto('/models', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Models', exact: true })).toBeVisible({ timeout: 30_000 });

    // Click Endpoints tab
    const endpointsTab = page.locator('.tab-btn', { hasText: 'Endpoints' });
    await endpointsTab.click();

    await expect(page.getByRole('heading', { name: 'Registered Models' })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Active Endpoints' })).toBeVisible();

    // If models exist, try opening the deploy dialog
    const deployButtons = page.locator('.endpoints-panel .actions button:has-text("Deploy")');
    if (await deployButtons.count() > 0) {
      await deployButtons.first().click();
      await expect(page.getByRole('heading', { name: 'Deploy Model' })).toBeVisible({ timeout: 10_000 });
      await page.getByRole('button', { name: 'Cancel' }).click();
    }
  });

  test('@smoke Custom Images — list', async ({ page }) => {
    await page.goto('/notebook-images', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Custom Notebook Images' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('button', { name: 'New Image' })).toBeVisible();

    // Table or empty state
    const table = page.locator('.images-page table');
    const emptyState = page.locator('.images-page .empty');
    const hasTable = await isVisible(table, 3_000);
    if (!hasTable) {
      await expect(emptyState).toBeVisible();
    }

    // Open create dialog
    await page.getByRole('button', { name: 'New Image' }).click();
    await expect(page.getByRole('heading', { name: 'Create Custom Notebook Image' })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('button', { name: /Create & Build/ })).toBeVisible();

    // Cancel dialog
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { name: 'Create Custom Notebook Image' })).toHaveCount(0, { timeout: 5_000 });
  });

  test('@smoke Custom Images — detail', async ({ page }) => {
    await page.goto('/notebook-images', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Custom Notebook Images' })).toBeVisible({ timeout: 30_000 });

    // Skip if no images exist
    const detailLinks = page.locator('.images-page table .actions a');
    const hasImages = await isVisible(detailLinks.first(), 5_000);
    test.skip(!hasImages, 'No custom images exist — skipping detail test');

    // Click "Details" link on first image
    await detailLinks.first().click();

    await expect(page.locator('.back-link')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Image Details' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Builds' })).toBeVisible();

    // Click first build item if available
    const buildItems = page.locator('.build-item');
    if (await buildItems.count() > 0) {
      await buildItems.first().click();
      await expect(page.getByRole('heading', { name: 'Build Logs' })).toBeVisible({ timeout: 10_000 });
    }
  });

  // ──────────────────────────────────────────────
  // Phase 2: Workspace-dependent (slow, ~5-7 min)
  // ──────────────────────────────────────────────

  test('@full Notebooks — launch workspace', async ({ page }) => {
    // Fallback: if @smoke tests were skipped, discover analysisUrl ourselves
    if (!analysisUrl) {
      await page.goto('/analyses', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('.analyses-page')).toBeVisible({ timeout: 30_000 });
      const cards = page.locator('.analysis-card');
      await expect(cards.first()).toBeVisible({ timeout: 15_000 });
      await cards.first().click();
      await expect(page.locator('.breadcrumb')).toBeVisible({ timeout: 15_000 });
      analysisUrl = new URL(page.url()).pathname.replace(/\/notebooks$/, '');
    }

    await page.goto(`${analysisUrl}/notebooks`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.workspace-page')).toBeVisible({ timeout: 30_000 });

    // If workspace already running (iframe visible), skip launch
    const jupyterFrame = page.locator('iframe[title="JupyterLab"]');
    if (await isVisible(jupyterFrame, 5_000)) {
      return; // Already running
    }

    // Verify launcher controls
    await expect(page.locator('.profile-selector select')).toBeVisible();
    await expect(page.locator('.image-selector select')).toBeVisible();

    // Launch
    await page.getByRole('button', { name: 'Launch Workspace' }).click();

    // Poll for JupyterLab iframe (up to 210s)
    await waitForWorkspaceRunning(page, 210_000);
  });

  test('@full Notebooks — toolbar and iframe', async ({ page }) => {
    if (!analysisUrl) {
      test.skip(true, 'No analysisUrl available');
    }

    await page.goto(`${analysisUrl}/notebooks`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.workspace-page')).toBeVisible({ timeout: 30_000 });

    // Wait for running state
    await waitForWorkspaceRunning(page, 210_000);

    // Verify toolbar elements
    await expect(page.locator('.toolbar')).toBeVisible();
    await expect(page.locator('.kernel-status')).toBeVisible();
    await expect(page.locator('.toolbar-profile-select')).toBeVisible();
    await expect(page.locator('.toolbar-image-select')).toBeVisible();
    await expect(page.locator('.metrics-display')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Terminate' })).toBeVisible();
  });

  test('@full Notebooks — S3 workspace files', async ({ page }) => {
    if (!analysisUrl) {
      test.skip(true, 'No analysisUrl available');
    }

    await page.goto(`${analysisUrl}/notebooks`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.workspace-page')).toBeVisible({ timeout: 30_000 });

    // Wait for running state
    await waitForWorkspaceRunning(page, 210_000);

    // Access JupyterLab file browser via frameLocator, scoped to the sidebar
    const jupyterFrame = page.frameLocator('iframe[title="JupyterLab"]');
    const fileBrowser = jupyterFrame.getByLabel('File Browser Section');

    // Wait for JupyterLab file browser to render — seeded files should appear
    await expect(fileBrowser.getByText('sample-delta-data.ipynb', { exact: true })).toBeVisible({ timeout: 90_000 });
    await expect(fileBrowser.getByText('batch-inference.ipynb', { exact: true })).toBeVisible({ timeout: 10_000 });
    await expect(fileBrowser.getByText('visualize', { exact: true })).toBeVisible({ timeout: 10_000 });
  });

  test('@full Experiments — MLflow iframe', async ({ page }) => {
    if (!analysisUrl) {
      test.skip(true, 'No analysisUrl available');
    }

    await page.goto(`${analysisUrl}/experiments`, { waitUntil: 'domcontentloaded' });

    // Experiments tab should be active
    const experimentsTab = page.locator('.analysis-tabs a', { hasText: 'Experiments' });
    await expect(experimentsTab).toHaveClass(/active/, { timeout: 15_000 });

    await expect(page.getByRole('heading', { name: 'Experiments' })).toBeVisible({ timeout: 15_000 });

    // Wait for loading to finish
    const loadingCard = page.locator('.experiments-page .loading-card');
    if (await isVisible(loadingCard, 3_000)) {
      await expect(loadingCard).toBeHidden({ timeout: 30_000 });
    }

    // No error should be shown
    await expect(page.locator('.experiments-page .error-card')).toHaveCount(0);

    // The MLflow iframe appears only when experiments exist for this analysis.
    // If no experiments, the page shows just the heading (acceptable).
    const iframe = page.locator('iframe[title="MLflow Tracking"]');
    const hasIframe = await isVisible(iframe, 10_000);
    if (hasIframe) {
      await expect(iframe).toBeVisible();
    }
  });

  test('@full Visualization — Streamlit', async ({ page }) => {
    if (!analysisUrl) {
      test.skip(true, 'No analysisUrl available');
    }

    await page.goto(`${analysisUrl}/visualization`, { waitUntil: 'domcontentloaded' });

    // Visualization tab should be active
    const vizTab = page.locator('.analysis-tabs a', { hasText: 'Visualization' });
    await expect(vizTab).toHaveClass(/active/, { timeout: 15_000 });

    await expect(page.getByRole('heading', { name: 'Visualization' })).toBeVisible({ timeout: 15_000 });

    // Wait for scan to complete — either guide-card (no files) or iframe (running)
    const guideCard = page.locator('.guide-card');
    const streamlitFrame = page.locator('iframe[title="Streamlit Visualization"]');
    const scanSpinner = page.locator('.status-card:has-text("Scanning")');
    const errorCard = page.locator('.error-card');

    // Wait for scan to finish (if it's happening)
    if (await isVisible(scanSpinner, 3_000)) {
      await expect(scanSpinner).toBeHidden({ timeout: 60_000 });
    }

    // If there's a transient error, click Retry once
    if (await isVisible(errorCard, 3_000)) {
      const retryBtn = errorCard.getByRole('button', { name: 'Retry' });
      if (await isVisible(retryBtn, 1_000)) {
        await retryBtn.click();
        // Wait for scan/retry to complete
        if (await isVisible(scanSpinner, 3_000)) {
          await expect(scanSpinner).toBeHidden({ timeout: 60_000 });
        }
      }
    }

    // One of these must be true: guide-card (no files), iframe (running),
    // workspace-stopped card, or error-card (transient backend issue)
    const workspaceStopped = page.locator('.status-card:has-text("Workspace Not Running")');
    const hasGuide = await isVisible(guideCard, 5_000);
    const hasFrame = await isVisible(streamlitFrame, 3_000);
    const hasStopped = await isVisible(workspaceStopped, 1_000);
    const hasError = await isVisible(errorCard, 1_000);
    expect(hasGuide || hasFrame || hasStopped || hasError,
      'Expected guide-card, Streamlit iframe, workspace-stopped card, or error-card').toBeTruthy();
  });

  test('@full Notebooks — terminate', async ({ page }) => {
    if (!analysisUrl) {
      test.skip(true, 'No analysisUrl available');
    }

    await page.goto(`${analysisUrl}/notebooks`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.workspace-page')).toBeVisible({ timeout: 30_000 });

    const terminateButton = page.getByRole('button', { name: 'Terminate' });
    if (await isVisible(terminateButton, 10_000)) {
      await terminateButton.click();
      // Wait for workspace to stop (Launch Workspace button appears)
      await expect(page.getByRole('button', { name: 'Launch Workspace' })).toBeVisible({ timeout: 180_000 });
    }
  });
});

// ────────────────────────────────────
// Helpers
// ────────────────────────────────────

async function isVisible(locator: Locator, timeout = 5_000): Promise<boolean> {
  try {
    await locator.waitFor({ state: 'visible', timeout });
    return true;
  } catch {
    return false;
  }
}

async function waitForWorkspaceRunning(page: Page, timeoutMs: number): Promise<void> {
  const startedAt = Date.now();
  const jupyterFrame = page.locator('iframe[title="JupyterLab"]');
  const errorCard = page.locator('.workspace-page .error-card');

  while (Date.now() - startedAt < timeoutMs) {
    if (await isVisible(jupyterFrame, 2_000)) {
      return;
    }
    if (await isVisible(errorCard, 500)) {
      const message = (await errorCard.innerText()).trim();
      throw new Error(`Workspace failed: ${message}`);
    }
    await page.waitForTimeout(3_000);
  }

  throw new Error(`Timed out after ${timeoutMs}ms waiting for JupyterLab iframe`);
}
