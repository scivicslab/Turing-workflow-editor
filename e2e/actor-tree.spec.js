// @ts-check
const { test, expect } = require('@playwright/test');

const BASE_URL = process.env.EDITOR_URL || 'http://localhost:8500';

test.describe('Actor Tree Browser', () => {

  test('page loads with Actor Tree button', async ({ page }) => {
    await page.goto(BASE_URL);
    const treeBtn = page.locator('#treeBtn');
    await expect(treeBtn).toBeVisible();
    await expect(treeBtn).toHaveText('Actor Tree');
  });

  test('no old Actors button exists', async ({ page }) => {
    await page.goto(BASE_URL);
    const actorsBtn = page.locator('#actorsBtn');
    await expect(actorsBtn).toHaveCount(0);
  });

  test('Actor Tree panel opens on click', async ({ page }) => {
    await page.goto(BASE_URL);
    const panel = page.locator('#sidePanel');
    await expect(panel).toBeHidden();

    await page.click('#treeBtn');
    await expect(panel).toBeVisible();
  });

  test('Actor Tree shows 5 actors (log, shell, loader, milestone, interpreter)', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#treeBtn');

    const treeBody = page.locator('#actorTreeBody');
    await expect(treeBody).toBeVisible();

    // Wait for tree nodes to appear
    const nodes = treeBody.locator('.tree-node');
    await expect(nodes).toHaveCount(5, { timeout: 5000 });
  });

  test('milestone actor is visible in tree', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#treeBtn');

    const msNode = page.locator('.tree-node', { hasText: 'milestone' });
    await expect(msNode).toBeVisible();
  });

  test('interpreter node shows as Interpreter type', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#treeBtn');

    // Find the interpreter node
    const interpNode = page.locator('.tree-node', { hasText: 'interpreter' });
    await expect(interpNode).toBeVisible();
  });

  test('clicking actor shows detail panel with actions', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#treeBtn');

    // Click on shell actor
    const shellNode = page.locator('.tree-node', { hasText: 'shell' });
    await shellNode.click();

    const dataPanel = page.locator('#actorDataPanel');
    await expect(dataPanel).toBeVisible();

    // Should show "exec" action
    const execTag = dataPanel.locator('.actor-action-tag', { hasText: 'exec' });
    await expect(execTag).toBeVisible();
  });

  test('clicking interpreter shows status info', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#treeBtn');

    const interpNode = page.locator('.tree-node', { hasText: 'interpreter' });
    await interpNode.click();

    const dataPanel = page.locator('#actorDataPanel');
    await expect(dataPanel).toBeVisible();

    // Should show status label
    const statusLabel = dataPanel.locator('.actor-section-label', { hasText: 'Status' });
    await expect(statusLabel).toBeVisible();
  });

  test('side panel closes with X button', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#treeBtn');
    await expect(page.locator('#sidePanel')).toBeVisible();

    await page.click('#sidePanelClose');
    await expect(page.locator('#sidePanel')).toBeHidden();
  });

  test('loader actor shows loadJar and createChild actions', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#treeBtn');

    const loaderNode = page.locator('.tree-node', { hasText: 'loader' });
    await loaderNode.click();

    const dataPanel = page.locator('#actorDataPanel');
    await expect(dataPanel.locator('.actor-action-tag', { hasText: 'loadJar' })).toBeVisible();
    await expect(dataPanel.locator('.actor-action-tag', { hasText: 'createChild' })).toBeVisible();
  });

});

test.describe('Plugins Browser', () => {

  test('Plugins button exists', async ({ page }) => {
    await page.goto(BASE_URL);
    const btn = page.locator('#pluginsBtn');
    await expect(btn).toBeVisible();
    await expect(btn).toHaveText('Plugins');
  });

  test('Plugins panel opens and shows items', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#pluginsBtn');

    const panel = page.locator('#sidePanel');
    await expect(panel).toBeVisible();

    // Plugins tab should be active
    const activeTab = page.locator('.side-tab.active');
    await expect(activeTab).toHaveText('Plugins');

    // Should have at least one browse-item (actor-IaC etc.)
    const items = page.locator('#pluginsBody .browse-item');
    await expect(items.first()).toBeVisible({ timeout: 5000 });
  });

  test('Plugin items have Load button', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#pluginsBtn');

    const loadBtn = page.locator('#pluginsBody .browse-item-actions button', { hasText: 'Load' });
    await expect(loadBtn.first()).toBeVisible({ timeout: 5000 });
  });

});

test.describe('Workflows Browser', () => {

  test('Workflows button exists', async ({ page }) => {
    await page.goto(BASE_URL);
    const btn = page.locator('#workflowsBtn');
    await expect(btn).toBeVisible();
    await expect(btn).toHaveText('Workflows');
  });

  test('Workflows panel opens and shows grouped items', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#workflowsBtn');

    const panel = page.locator('#sidePanel');
    await expect(panel).toBeVisible();

    // Workflows tab should be active
    const activeTab = page.locator('.side-tab.active');
    await expect(activeTab).toHaveText('Workflows');

    // Should have group labels (project names)
    const groups = page.locator('#workflowsBody .browse-group-label');
    await expect(groups.first()).toBeVisible({ timeout: 5000 });

    // Should have workflow items
    const items = page.locator('#workflowsBody .browse-item');
    await expect(items.first()).toBeVisible();
  });

  test('Workflow items have Open button', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.click('#workflowsBtn');

    const openBtn = page.locator('#workflowsBody .browse-item-actions button', { hasText: 'Open' });
    await expect(openBtn.first()).toBeVisible({ timeout: 5000 });
  });

  test('switching tabs changes panel content', async ({ page }) => {
    await page.goto(BASE_URL);

    // Open Actors tab
    await page.click('#treeBtn');
    await expect(page.locator('#sidePanelActors')).toBeVisible();
    await expect(page.locator('#sidePanelPlugins')).toBeHidden();

    // Switch to Plugins by clicking tab
    await page.click('.side-tab[data-tab="plugins"]');
    await expect(page.locator('#sidePanelPlugins')).toBeVisible();
    await expect(page.locator('#sidePanelActors')).toBeHidden();

    // Switch to Workflows
    await page.click('.side-tab[data-tab="workflows"]');
    await expect(page.locator('#sidePanelWorkflows')).toBeVisible();
    await expect(page.locator('#sidePanelPlugins')).toBeHidden();
  });

});
