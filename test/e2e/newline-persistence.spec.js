/**
 * Newline Persistence E2E Tests
 *
 * Tests that Shift+Enter inserts newlines that persist across edit mode transitions.
 *
 * REGRESSION: Previously, textContent was used which ignores <br> elements,
 * causing newlines to be lost when exiting edit mode. Fixed by using
 * element->text utility that properly converts BR to \n.
 *
 * FR: LOGSEQ_PARITY - Shift+Enter inserts literal newline
 */

import { test, expect } from '@playwright/test';
import { enterEditModeAndClick, pressKeyOnContentEditable } from './helpers/index.js';

test.describe('Newline Persistence (Shift+Enter)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);
  });

  test('Shift+Enter inserts visible newline in edit mode', async ({ page }) => {
    const editor = page.locator('[contenteditable="true"]');
    await editor.click();

    // Type first line
    await page.keyboard.type('Line 1');

    // Insert newline with Shift+Enter
    await pressKeyOnContentEditable(page, 'Enter', { shiftKey: true });

    // Type second line
    await page.keyboard.type('Line 2');

    // Verify DOM contains <br> element
    const innerHTML = await editor.evaluate(el => el.innerHTML);
    expect(innerHTML).toContain('<br>');

    // Verify text content has both lines
    const textContent = await editor.evaluate(el => el.textContent);
    expect(textContent).toContain('Line 1');
    expect(textContent).toContain('Line 2');
  });

  test('REGRESSION: newline persists after exiting edit mode', async ({ page }) => {
    const editor = page.locator('[contenteditable="true"]');
    await editor.click();

    // Type text with newline
    await page.keyboard.type('Before newline');
    await pressKeyOnContentEditable(page, 'Enter', { shiftKey: true });
    await page.keyboard.type('After newline');

    // Get block ID for later verification
    const blockId = await page.locator('[data-block-id]').first().getAttribute('data-block-id');

    // Exit edit mode with Escape
    await page.keyboard.press('Escape');

    // Playwright's expect() auto-waits for condition (no sleep needed)
    await expect(editor).not.toBeVisible();

    // Get the DB text content using TEST_HELPERS
    const savedText = await page.evaluate((id) => {
      return window.TEST_HELPERS?.getBlockText(id);
    }, blockId);

    // CRITICAL: The saved text must contain newline character
    expect(savedText).toContain('\n');
    expect(savedText).toBe('Before newline\nAfter newline');
  });

  test('REGRESSION: newline visible in view mode after exit', async ({ page }) => {
    const editor = page.locator('[contenteditable="true"]');
    await editor.click();

    // Type text with newline
    await page.keyboard.type('View line 1');
    await pressKeyOnContentEditable(page, 'Enter', { shiftKey: true });
    await page.keyboard.type('View line 2');

    // Get block ID
    const blockId = await page.locator('[data-block-id]').first().getAttribute('data-block-id');

    // Exit edit mode - wait for contenteditable to disappear
    await page.keyboard.press('Escape');
    await expect(editor).not.toBeVisible();

    // Find the view mode content element (the span that renders the block content)
    const blockContainer = page.locator(`[data-block-id="${blockId}"]`);

    // The content span is the one that shows the text (not the bullet)
    // Check for innerHTML with <br> for visual line break
    const viewHTML = await blockContainer.evaluate(el => {
      // Find the span that contains the block content (not the bullet)
      const spans = el.querySelectorAll('span');
      for (const span of spans) {
        if (span.textContent.includes('View line')) {
          return span.innerHTML;
        }
      }
      return '';
    });

    // View mode should render newline as <br>
    expect(viewHTML).toContain('<br>');

    // Both lines should be visible in the text
    const viewText = await blockContainer.evaluate(el => el.textContent);
    expect(viewText).toContain('View line 1');
    expect(viewText).toContain('View line 2');
  });

  test('REGRESSION: newline preserved when re-entering edit mode', async ({ page }) => {
    const editor = page.locator('[contenteditable="true"]');
    await editor.click();

    // Type text with newline
    await page.keyboard.type('Edit line 1');
    await pressKeyOnContentEditable(page, 'Enter', { shiftKey: true });
    await page.keyboard.type('Edit line 2');

    // Get block ID
    const blockId = await page.locator('[data-block-id]').first().getAttribute('data-block-id');

    // Exit edit mode
    await page.keyboard.press('Escape');
    await expect(editor).not.toBeVisible();

    // Re-enter edit mode: first select the block, then enter edit
    await page.evaluate((id) => {
      // Select the block first (required by state machine)
      window.TEST_HELPERS?.dispatchIntent({type: 'selection', mode: 'replace', ids: id});
      // Then enter edit mode
      window.TEST_HELPERS?.dispatchIntent({type: 'enter-edit', 'block-id': id});
    }, blockId);

    // Wait for contenteditable
    await page.waitForSelector('[contenteditable="true"]', { timeout: 3000 });

    // Verify edit mode has <br> for the newline
    const reenteredEditor = page.locator('[contenteditable="true"]');
    const innerHTML = await reenteredEditor.evaluate(el => el.innerHTML);
    expect(innerHTML).toContain('<br>');

    // Both lines should be present
    const textContent = await reenteredEditor.evaluate(el => el.textContent);
    expect(textContent).toContain('Edit line 1');
    expect(textContent).toContain('Edit line 2');
  });

  test('multiple newlines persist correctly', async ({ page }) => {
    const editor = page.locator('[contenteditable="true"]');
    await editor.click();

    // Type text with multiple newlines
    await page.keyboard.type('Line A');
    await pressKeyOnContentEditable(page, 'Enter', { shiftKey: true });
    await page.keyboard.type('Line B');
    await pressKeyOnContentEditable(page, 'Enter', { shiftKey: true });
    await page.keyboard.type('Line C');

    // Get block ID
    const blockId = await page.locator('[data-block-id]').first().getAttribute('data-block-id');

    // Exit edit mode
    await page.keyboard.press('Escape');
    await expect(editor).not.toBeVisible();

    // Verify DB has all newlines using TEST_HELPERS
    const savedText = await page.evaluate((id) => {
      return window.TEST_HELPERS?.getBlockText(id);
    }, blockId);

    expect(savedText).toBe('Line A\nLine B\nLine C');

    // Count newlines
    const newlineCount = (savedText.match(/\n/g) || []).length;
    expect(newlineCount).toBe(2);
  });
});
