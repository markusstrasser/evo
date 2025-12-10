/**
 * Backlinks (Linked References) E2E Tests
 *
 * Tests for the backlinks panel that shows blocks referencing the current page.
 */

import { test, expect } from '@playwright/test';

test.describe('Backlinks Panel', () => {
  test.beforeEach(async ({ page }) => {
    // Load default demo data (has Projects, Tasks, Notes pages with cross-references)
    await page.goto('/index.html');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('shows backlinks from other pages', async ({ page }) => {
    // Projects page should have backlinks from Notes and Tasks
    await expect(page.getByRole('heading', { name: '📄 Projects' })).toBeVisible();
    
    // Check backlinks panel exists
    await expect(page.getByRole('heading', { name: 'Linked References' })).toBeVisible();
    
    // Should show 2 backlinks
    const countBadge = page.locator('.backlinks-count');
    await expect(countBadge).toHaveText('2');
    
    // Should have backlinks from Notes and Tasks pages
    await expect(page.locator('.backlinks-panel').getByText('📄 Notes')).toBeVisible();
    await expect(page.locator('.backlinks-panel').getByText('📄 Tasks')).toBeVisible();
  });

  test('backlink page refs are clickable links', async ({ page }) => {
    // Projects page backlinks should contain clickable [[Tasks]] link
    const tasksLink = page.locator('.backlinks-panel').getByRole('link', { name: '[[Tasks]]' });
    await expect(tasksLink).toBeVisible();
    
    // Click should navigate to Tasks page
    await tasksLink.click();
    await expect(page.getByRole('heading', { name: '📄 Tasks' })).toBeVisible();
  });

  test('clicking source page header navigates to that page', async ({ page }) => {
    // Click on "📄 Notes" header in backlinks
    await page.locator('.backlinks-panel').getByText('📄 Notes').click();
    
    // Should navigate to Notes page
    await expect(page.getByRole('heading', { name: '📄 Notes' })).toBeVisible();
  });

  test('backlinks update when navigating to different page', async ({ page }) => {
    // Start on Projects - should have 2 backlinks
    await expect(page.locator('.backlinks-count')).toHaveText('2');
    
    // Navigate to Notes page via sidebar
    await page.locator('.sidebar, [class*="sidebar"]').getByText('Notes').click();
    await expect(page.getByRole('heading', { name: '📄 Notes' })).toBeVisible();
    
    // Notes page should have 0 backlinks (only self-references filtered out)
    await expect(page.locator('.backlinks-count')).toHaveText('0');
    await expect(page.getByText('No references to this page yet')).toBeVisible();
  });

  test('backlinks update reactively when block content changes', async ({ page }) => {
    // Start on Projects - should have 2 backlinks
    await expect(page.locator('.backlinks-count')).toHaveText('2');
    
    // Navigate to Notes page
    await page.locator('.sidebar, [class*="sidebar"]').getByText('Notes').click();
    await expect(page.getByRole('heading', { name: '📄 Notes' })).toBeVisible();
    
    // Find the block with [[Projects]] reference
    // The block contains "Navigate between: [[Projects]], [[Tasks]], [[Notes]]"
    const blockWithRef = page.locator('[data-block-id]').filter({ hasText: 'Navigate between:' });
    const blockId = await blockWithRef.getAttribute('data-block-id');
    
    // Use TEST_HELPERS to enter edit mode (more reliable than double-click)
    await page.evaluate((id) => {
      window.TEST_HELPERS.dispatchIntent({type: 'selection', mode: 'replace', ids: id});
      window.TEST_HELPERS.dispatchIntent({type: 'enter-edit', 'block-id': id});
    }, blockId);
    
    // Wait for contenteditable to appear (edit mode)
    await page.waitForSelector('[contenteditable="true"]', { timeout: 2000 });
    
    // Select all and replace with text that has no [[Projects]] ref
    await page.keyboard.press('Meta+a');
    await page.keyboard.type('Just plain text, no refs');
    
    // Press Escape to exit edit mode and commit changes
    await page.keyboard.press('Escape');
    
    // Wait for edit mode to exit
    await page.waitForTimeout(100);
    
    // Navigate back to Projects
    await page.locator('.sidebar, [class*="sidebar"]').getByText('Projects').click();
    await expect(page.getByRole('heading', { name: '📄 Projects' })).toBeVisible();
    
    // Should now have only 1 backlink (Tasks only, Notes reference removed)
    await expect(page.locator('.backlinks-count')).toHaveText('1');
    
    // Notes should no longer appear in backlinks
    await expect(page.locator('.backlinks-panel').getByText('📄 Notes')).not.toBeVisible();
    
    // Tasks should still be there
    await expect(page.locator('.backlinks-panel').getByText('📄 Tasks')).toBeVisible();
  });

  test('self-references are filtered out', async ({ page }) => {
    // Navigate to Notes page
    await page.locator('.sidebar, [class*="sidebar"]').getByText('Notes').click();
    await expect(page.getByRole('heading', { name: '📄 Notes' })).toBeVisible();
    
    // Notes has a block with [[Notes]] (self-reference) - should not appear in backlinks
    // The page has "Navigate between: [[Projects]], [[Tasks]], [[Notes]]"
    // But since that's on the Notes page itself, [[Notes]] shouldn't create a backlink
    
    // Should show 0 backlinks (other pages reference Notes, but Notes page itself has self-ref)
    await expect(page.locator('.backlinks-count')).toHaveText('0');
  });

  test('empty backlinks shows placeholder message', async ({ page }) => {
    // Notes page should have 0 backlinks in demo data
    await page.locator('.sidebar, [class*="sidebar"]').getByText('Notes').click();
    await expect(page.getByRole('heading', { name: '📄 Notes' })).toBeVisible();
    
    await expect(page.getByText('No references to this page yet')).toBeVisible();
  });

  test('page ref click in main content navigates correctly', async ({ page }) => {
    // Projects page has "See also: [[Tasks]] page for work items"
    const tasksRef = page.locator('.outline, [class*="outline"]').getByRole('link', { name: '[[Tasks]]' }).first();
    await expect(tasksRef).toBeVisible();
    
    await tasksRef.click();
    
    // Should navigate to Tasks page
    await expect(page.getByRole('heading', { name: '📄 Tasks' })).toBeVisible();
  });

  test('backlinks update when referencing block is deleted', async ({ page }) => {
    // Start on Projects - should have 2 backlinks
    await expect(page.locator('.backlinks-count')).toHaveText('2');
    
    // Navigate to Notes page
    await page.locator('.sidebar, [class*="sidebar"]').getByText('Notes').click();
    await expect(page.getByRole('heading', { name: '📄 Notes' })).toBeVisible();
    
    // Find and select the block with [[Projects]] reference
    const blockWithRef = page.locator('[data-block-id]').filter({ hasText: 'Navigate between:' });
    const blockId = await blockWithRef.getAttribute('data-block-id');
    
    // Select the block using TEST_HELPERS
    await page.evaluate((id) => {
      window.TEST_HELPERS.dispatchIntent({type: 'selection', mode: 'replace', ids: id});
    }, blockId);
    
    // Delete the block with Backspace
    await page.keyboard.press('Backspace');
    
    // Verify block is deleted from the page
    await expect(page.locator('[data-block-id]').filter({ hasText: 'Navigate between:' })).not.toBeVisible();
    
    // Navigate back to Projects
    await page.locator('.sidebar, [class*="sidebar"]').getByText('Projects').click();
    await expect(page.getByRole('heading', { name: '📄 Projects' })).toBeVisible();
    
    // Should now have only 1 backlink (deleted block should not appear)
    await expect(page.locator('.backlinks-count')).toHaveText('1');
    
    // Notes should no longer appear in backlinks (its referencing block was deleted)
    await expect(page.locator('.backlinks-panel').getByText('📄 Notes')).not.toBeVisible();
    
    // Tasks should still be there
    await expect(page.locator('.backlinks-panel').getByText('📄 Tasks')).toBeVisible();
  });
});
