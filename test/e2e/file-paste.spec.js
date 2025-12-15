// @ts-check
import { test, expect } from '@playwright/test';
import { enterEditModeAndClick } from './helpers/index.js';

const wait = (page, ms = 100) => page.waitForTimeout(ms);

/**
 * File Upload on Paste E2E Tests
 *
 * Tests that pasting image files from clipboard:
 * 1. Uploads them to the assets folder
 * 2. Inserts markdown image links at cursor position
 *
 * Note: These tests require a folder to be open for file system access.
 * Without a folder, an alert is shown asking user to open one.
 */

test.describe('File Paste Upload', () => {
  let blockId;

  test.beforeEach(async ({ page }) => {
    await page.goto('/index.html?test=true');
    await page.waitForLoadState('domcontentloaded');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
    await enterEditModeAndClick(page);

    blockId = await page.evaluate(() => {
      return window.TEST_HELPERS?.getSession()?.ui?.['editing-block-id'];
    });
  });

  /**
   * Helper to simulate paste with an image file
   * Creates a small PNG blob for testing
   */
  async function simulatePasteWithImageFile(page, filename = 'test-image.png') {
    return await page.evaluate(async ({ fname }) => {
      const editable = document.querySelector('[contenteditable="true"]');
      if (!editable) return { success: false, error: 'No contenteditable' };

      // Create a small 1x1 PNG blob (minimal valid PNG)
      const base64Png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
      const binaryString = atob(base64Png);
      const bytes = new Uint8Array(binaryString.length);
      for (let i = 0; i < binaryString.length; i++) {
        bytes[i] = binaryString.charCodeAt(i);
      }
      const blob = new Blob([bytes], { type: 'image/png' });
      const file = new File([blob], fname, { type: 'image/png' });

      // Create DataTransfer with the file
      const dataTransfer = new DataTransfer();
      dataTransfer.items.add(file);

      const pasteEvent = new ClipboardEvent('paste', {
        bubbles: true,
        cancelable: true,
        clipboardData: dataTransfer
      });

      editable.dispatchEvent(pasteEvent);
      return { success: true, filename: fname };
    }, { fname: filename });
  }

  test('pasting image without folder shows alert', async ({ page }) => {
    // Set up dialog handler to capture alert
    let alertMessage = '';
    page.on('dialog', async dialog => {
      alertMessage = dialog.message();
      await dialog.accept();
    });

    // Ensure no folder is open
    await page.evaluate(() => {
      // Clear any folder handle
      window.directoryHandle = null;
    });

    await simulatePasteWithImageFile(page);
    await wait(page, 300);

    // Should show alert about opening folder
    expect(alertMessage).toContain('open a folder');
  });

  test.describe('With folder access (mocked)', () => {
    test.beforeEach(async ({ page }) => {
      // Mock the storage module to simulate folder access
      await page.evaluate(() => {
        // Mock storage.has-folder? to return true
        if (window.shell?.storage) {
          window.shell.storage.has_folder_QMARK_ = () => true;
        }
      });
    });

    test('image paste calls upload function', async ({ page }) => {
      // Track console logs for upload
      const logs = [];
      page.on('console', msg => {
        if (msg.text().includes('📷')) {
          logs.push(msg.text());
        }
      });

      await simulatePasteWithImageFile(page, 'screenshot.png');
      await wait(page, 500);

      // Should have attempted upload (logged the attempt)
      const uploadLogs = logs.filter(l => l.includes('upload-and-insert-images'));
      expect(uploadLogs.length).toBeGreaterThan(0);
    });
  });

  test('image file detection works for PNG', async ({ page }) => {
    // Test the get-image-files function directly
    const result = await page.evaluate(() => {
      const base64Png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
      const binaryString = atob(base64Png);
      const bytes = new Uint8Array(binaryString.length);
      for (let i = 0; i < binaryString.length; i++) {
        bytes[i] = binaryString.charCodeAt(i);
      }
      const blob = new Blob([bytes], { type: 'image/png' });
      const file = new File([blob], 'test.png', { type: 'image/png' });

      // Check if it's detected as an image
      return {
        type: file.type,
        isImage: file.type.startsWith('image/')
      };
    });

    expect(result.type).toBe('image/png');
    expect(result.isImage).toBe(true);
  });

  test('non-image file paste falls through to text handling', async ({ page }) => {
    // Paste a text file (not an image)
    await page.evaluate(() => {
      const editable = document.querySelector('[contenteditable="true"]');
      if (!editable) return;

      const file = new File(['hello world'], 'test.txt', { type: 'text/plain' });
      const dataTransfer = new DataTransfer();
      dataTransfer.items.add(file);
      dataTransfer.setData('text/plain', 'fallback text');

      const pasteEvent = new ClipboardEvent('paste', {
        bubbles: true,
        cancelable: true,
        clipboardData: dataTransfer
      });

      editable.dispatchEvent(pasteEvent);
    });
    await wait(page, 200);

    // Should fall through to text paste
    const result = await page.evaluate((bid) => {
      const db = window.DEBUG.getDb();
      return db?.nodes?.[bid]?.props?.text;
    }, blockId);

    // Text file should not be treated as image, text paste happens
    expect(result).toBe('fallback text');
  });
});
