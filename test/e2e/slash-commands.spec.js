// @ts-check
import { test, expect } from '@playwright/test';
import { enterEditModeAndClick, pressHome } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * Slash Commands E2E Tests
 *
 * Tests the slash command autocomplete system for inserting dates,
 * embeds, and other special content.
 */

test.describe('Slash Commands', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html');
    // Use domcontentloaded instead of networkidle (shadow-cljs keeps websocket open)
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 10000 });
  });

  test.describe('Triggering', () => {
    test('typing / at start of line shows command popup', async ({ page }) => {
      await enterEditModeAndClick(page);

      // Move cursor to start and type /
      await pressHome(page);
      await page.keyboard.type('/');
      await page.waitForTimeout(100);

      // Popup should appear
      const popup = page.locator('#autocomplete-popup');
      await expect(popup).toBeVisible();

      // Should show some commands
      const items = popup.locator('.autocomplete-item');
      await expect(items.first()).toBeVisible();
    });

    test('typing / after space shows command popup', async ({ page }) => {
      await enterEditModeAndClick(page);

      // Type text then space then /
      await page.keyboard.type('hello /');
      await wait(page);

      // Popup should appear
      const popup = page.locator('#autocomplete-popup');
      await expect(popup).toBeVisible();
    });

    test('typing / in middle of word does not show popup', async ({ page }) => {
      await enterEditModeAndClick(page);

      // Type without space before /
      await page.keyboard.type('hello/');
      await wait(page);

      // Popup should NOT appear
      const popup = page.locator('#autocomplete-popup');
      await expect(popup).not.toBeVisible();
    });

    test('Escape dismisses popup', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/');
      await wait(page);

      const popup = page.locator('#autocomplete-popup');
      await expect(popup).toBeVisible();

      await page.keyboard.press('Escape');
      await expect(popup).not.toBeVisible();
    });

    test('Backspace on / dismisses popup', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/');
      await wait(page);

      const popup = page.locator('#autocomplete-popup');
      await expect(popup).toBeVisible();

      await page.keyboard.press('Backspace');
      await expect(popup).not.toBeVisible();
    });
  });

  test.describe('Navigation', () => {
    test('Arrow keys navigate command list', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/');
      await wait(page);

      // First item should be selected
      const popup = page.locator('#autocomplete-popup');
      let selectedItem = popup.locator('.autocomplete-item.selected');
      await expect(selectedItem).toBeVisible();

      const firstText = await selectedItem.textContent();

      // Press down arrow
      await page.keyboard.press('ArrowDown');
      await wait(page);

      // Different item should now be selected
      selectedItem = popup.locator('.autocomplete-item.selected');
      const secondText = await selectedItem.textContent();
      expect(secondText).not.toBe(firstText);

      // Press up arrow to go back
      await page.keyboard.press('ArrowUp');
      await wait(page);

      selectedItem = popup.locator('.autocomplete-item.selected');
      const backToFirstText = await selectedItem.textContent();
      expect(backToFirstText).toBe(firstText);
    });
  });

  test.describe('Filtering', () => {
    test('typing filters command list', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/');
      await wait(page);

      const popup = page.locator('#autocomplete-popup');
      const initialItems = await popup.locator('.autocomplete-item').count();

      // Type to filter
      await page.keyboard.type('tod');
      await wait(page);

      // Should have fewer items
      const filteredItems = await popup.locator('.autocomplete-item').count();
      expect(filteredItems).toBeLessThan(initialItems);

      // Should show "Today" command
      await expect(popup.locator('.autocomplete-item').filter({ hasText: 'Today' })).toBeVisible();
    });

    test('no matches shows empty state', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/');
      await wait(page);

      // Type gibberish
      await page.keyboard.type('xyzabc123');
      await wait(page);

      const popup = page.locator('#autocomplete-popup');
      await expect(popup.locator('.autocomplete-empty')).toBeVisible();
    });
  });

  test.describe('Date Commands', () => {
    test('/today inserts today date as page ref', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/');
      await wait(page);

      // Type to filter to Today
      await page.keyboard.type('today');
      await wait(page);

      // Select with Enter
      await page.keyboard.press('Enter');
      await wait(page);

      // Get block text - should have date format [[Mon DD, YYYY]]
      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();

      // Should match date pattern like [[Dec 10, 2025]]
      expect(text).toMatch(/\[\[[A-Z][a-z]{2} \d{1,2}, \d{4}\]\]/);
    });

    test('/tomorrow inserts tomorrow date', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/tom');
      await wait(page);

      // Should show Tomorrow option
      const popup = page.locator('#autocomplete-popup');
      await expect(popup.locator('.autocomplete-item').filter({ hasText: 'Tomorrow' })).toBeVisible();

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toMatch(/\[\[[A-Z][a-z]{2} \d{1,2}, \d{4}\]\]/);
    });

    test('/yesterday inserts yesterday date', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/yes');
      await wait(page);

      const popup = page.locator('#autocomplete-popup');
      await expect(popup.locator('.autocomplete-item').filter({ hasText: 'Yesterday' })).toBeVisible();

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toMatch(/\[\[[A-Z][a-z]{2} \d{1,2}, \d{4}\]\]/);
    });

    test('/time inserts current time', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/time');
      await wait(page);

      const popup = page.locator('#autocomplete-popup');
      await expect(popup.locator('.autocomplete-item').filter({ hasText: 'Time' })).toBeVisible();

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      // Should match HH:MM format
      expect(text).toMatch(/\d{2}:\d{2}/);
    });
  });

  test.describe('Embed Commands', () => {
    test('/tweet inserts tweet template with cursor positioned', async ({ page }) => {
      await enterEditModeAndClick(page);
      // Clear existing text first
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('/tweet');
      await wait(page);

      const popup = page.locator('#autocomplete-popup');
      await expect(popup.locator('.autocomplete-item').filter({ hasText: 'Tweet' })).toBeVisible();

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toBe('{{tweet }}');

      // Cursor should be positioned between "tweet " and "}"
      // Type something to verify position
      await page.keyboard.type('https://twitter.com/test');
      const updatedText = await block.textContent();
      expect(updatedText).toBe('{{tweet https://twitter.com/test}}');
    });

    test('/video inserts video template', async ({ page }) => {
      await enterEditModeAndClick(page);
      // Clear existing text first
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('/video');
      await wait(page);

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toBe('{{video }}');
    });
  });

  test.describe('Code & Quote Commands', () => {
    test('/code inserts code block template', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/code');
      await wait(page);

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toContain('```');
    });

    test('/quote inserts blockquote marker', async ({ page }) => {
      await enterEditModeAndClick(page);
      // Clear existing text first
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('/quote');
      await wait(page);

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toBe('> ');
    });
  });

  test.describe('Heading Commands', () => {
    test('/h1 inserts h1 marker', async ({ page }) => {
      await enterEditModeAndClick(page);
      // Clear existing text first
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('/h1');
      await wait(page);

      // Select H1
      const popup = page.locator('#autocomplete-popup');
      await expect(popup.locator('.autocomplete-item').filter({ hasText: 'H1' })).toBeVisible();

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toBe('# ');
    });

    test('/h2 inserts h2 marker', async ({ page }) => {
      await enterEditModeAndClick(page);
      // Clear existing text first
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('/h2');
      await wait(page);

      // Select H2
      const popup = page.locator('#autocomplete-popup');
      await expect(popup.locator('.autocomplete-item').filter({ hasText: 'H2' })).toBeVisible();

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toBe('## ');
    });
  });

  test.describe('Tab Selection', () => {
    test('Tab key also selects command', async ({ page }) => {
      await enterEditModeAndClick(page);
      await pressHome(page);
      await page.keyboard.type('/today');
      await wait(page);

      // Select with Tab instead of Enter
      await page.keyboard.press('Tab');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();
      expect(text).toMatch(/\[\[[A-Z][a-z]{2} \d{1,2}, \d{4}\]\]/);
    });
  });

  test.describe('Existing Text', () => {
    test('command inserts at cursor position in existing text', async ({ page }) => {
      await enterEditModeAndClick(page);

      // Clear existing text and type some text first
      await page.keyboard.press('Meta+a');
      await page.keyboard.type('Before ');
      await page.keyboard.type('/today');
      await wait(page);

      await page.keyboard.press('Enter');
      await wait(page);

      const block = page.locator('[contenteditable="true"]');
      const text = await block.textContent();

      // Should have "Before " then the date
      expect(text).toMatch(/^Before \[\[[A-Z][a-z]{2} \d{1,2}, \d{4}\]\]$/);
    });
  });
});
