/**
 * Backlinks (Linked References) E2E Tests
 *
 * Tests for the backlinks panel that shows blocks referencing the current page.
 */

import { expect, test } from '@playwright/test';
import { pressGlobalKey } from './helpers/index.js';

async function loadBacklinksFixture(page) {
  await page.waitForFunction(() => window.TEST_HELPERS?.loadFixture);
  await page.evaluate(() => {
    window.TEST_HELPERS.loadFixture({
      ops: [
        { op: 'create-node', id: 'projects', type: 'page', props: { title: 'Projects' } },
        { op: 'place', id: 'projects', under: 'doc', at: 'last' },
        { op: 'create-node', id: 'tasks', type: 'page', props: { title: 'Tasks' } },
        { op: 'place', id: 'tasks', under: 'doc', at: 'last' },
        { op: 'create-node', id: 'notes', type: 'page', props: { title: 'Notes' } },
        { op: 'place', id: 'notes', under: 'doc', at: 'last' },
        {
          op: 'create-node',
          id: 'projects-ref-tasks',
          type: 'block',
          props: { text: 'See also: [[Tasks]] page for work items' },
        },
        { op: 'place', id: 'projects-ref-tasks', under: 'projects', at: 'last' },
        {
          op: 'create-node',
          id: 'tasks-ref-projects',
          type: 'block',
          props: { text: 'Track [[Projects]] tasks' },
        },
        { op: 'place', id: 'tasks-ref-projects', under: 'tasks', at: 'last' },
        {
          op: 'create-node',
          id: 'notes-ref-pages',
          type: 'block',
          props: { text: 'Navigate between: [[Projects]], [[Tasks]], [[Notes]]' },
        },
        { op: 'place', id: 'notes-ref-pages', under: 'notes', at: 'last' },
      ],
      session: {
        ui: { 'current-page': 'projects', 'journals-view?': false },
        selection: { nodes: [] },
      },
    });
  });
}

async function switchPage(page, pageId) {
  await page.evaluate((id) => {
    window.TEST_HELPERS.dispatchIntent({ type: 'switch-page', 'page-id': id });
  }, pageId);
}

test.describe('Backlinks Panel', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');
    await loadBacklinksFixture(page);
    await page.waitForSelector('[data-block-id="projects-ref-tasks"]', { timeout: 5000 });
  });

  test('shows backlinks from other pages', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Projects' })).toBeVisible();

    await expect(page.getByRole('heading', { name: 'Linked References' })).toBeVisible();

    const countBadge = page.locator('.backlinks-count');
    await expect(countBadge).toHaveText('2');

    await expect(
      page.locator('.backlink-page-header').getByText('Notes', { exact: true })
    ).toBeVisible();
    await expect(
      page.locator('.backlink-page-header').getByText('Tasks', { exact: true })
    ).toBeVisible();
  });

  test('backlink page refs are clickable links', async ({ page }) => {
    // Projects page backlinks should contain clickable [[Tasks]] link
    const tasksLink = page.locator('.backlinks-panel').getByRole('link', { name: '[[Tasks]]' });
    await expect(tasksLink).toBeVisible();

    // Click should navigate to Tasks page
    await tasksLink.click();
    await expect(page.getByRole('heading', { name: 'Tasks' })).toBeVisible();
  });

  test('clicking source page header navigates to that page', async ({ page }) => {
    await page.locator('.backlink-page-header').getByText('Notes', { exact: true }).click();

    await expect(page.getByRole('heading', { name: 'Notes' })).toBeVisible();
  });

  test('backlinks update when navigating to different page', async ({ page }) => {
    await expect(page.locator('.backlinks-count')).toHaveText('2');

    await switchPage(page, 'notes');
    await expect(page.getByRole('heading', { name: 'Notes' })).toBeVisible();

    await expect(page.locator('.backlinks-panel')).not.toBeVisible();
  });

  test('backlinks update reactively when block content changes', async ({ page }) => {
    await expect(page.locator('.backlinks-count')).toHaveText('2');

    await switchPage(page, 'notes');
    await expect(page.getByRole('heading', { name: 'Notes' })).toBeVisible();

    await page.evaluate(() => {
      window.TEST_HELPERS.transact([
        {
          op: 'update-node',
          id: 'notes-ref-pages',
          props: { text: 'Just plain text, no refs' },
        },
      ]);
    });
    await page.waitForFunction(
      () => window.TEST_HELPERS.getBlockText('notes-ref-pages') === 'Just plain text, no refs'
    );

    await switchPage(page, 'projects');
    await expect(page.getByRole('heading', { name: 'Projects' })).toBeVisible();

    await expect(page.locator('.backlinks-count')).toHaveText('1');

    await expect(
      page.locator('.backlink-page-header').getByText('Notes', { exact: true })
    ).not.toBeVisible();
    await expect(
      page.locator('.backlink-page-header').getByText('Tasks', { exact: true })
    ).toBeVisible();
  });

  test('self-references are filtered out', async ({ page }) => {
    await switchPage(page, 'notes');
    await expect(page.getByRole('heading', { name: 'Notes' })).toBeVisible();
    await expect(page.locator('.backlinks-panel')).not.toBeVisible();
  });

  test('page ref click in main content navigates correctly', async ({ page }) => {
    const tasksRef = page
      .locator('.outline, [class*="outline"]')
      .getByRole('link', { name: '[[Tasks]]' })
      .first();
    await expect(tasksRef).toBeVisible();

    await tasksRef.click();

    await expect(page.getByRole('heading', { name: 'Tasks' })).toBeVisible();
  });

  test('backlinks update when referencing block is deleted', async ({ page }) => {
    await expect(page.locator('.backlinks-count')).toHaveText('2');

    await switchPage(page, 'notes');
    await expect(page.getByRole('heading', { name: 'Notes' })).toBeVisible();

    const blockWithRef = page.locator('[data-block-id]').filter({ hasText: 'Navigate between:' });
    const blockId = await blockWithRef.getAttribute('data-block-id');

    await page.evaluate((id) => {
      window.TEST_HELPERS.dispatchIntent({ type: 'selection', mode: 'replace', ids: id });
    }, blockId);

    // Delete the block with Backspace
    await pressGlobalKey(page, 'Backspace');

    // Verify block is deleted from the page
    await expect(
      page.locator('[data-block-id]').filter({ hasText: 'Navigate between:' })
    ).not.toBeVisible();

    await switchPage(page, 'projects');
    await expect(page.getByRole('heading', { name: 'Projects' })).toBeVisible();

    await expect(page.locator('.backlinks-count')).toHaveText('1');

    await expect(
      page.locator('.backlink-page-header').getByText('Notes', { exact: true })
    ).not.toBeVisible();
    await expect(
      page.locator('.backlink-page-header').getByText('Tasks', { exact: true })
    ).toBeVisible();
  });
});
