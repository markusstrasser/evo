import { test, expect } from '@playwright/test';

/**
 * E2E Tests for Paste Semantics (FR-Clipboard-03)
 *
 * LOGSEQ PARITY: Paste behavior depends on blank lines:
 * - Single newlines → stay inline as literal \n
 * - Blank lines (\n\n) → split into multiple blocks
 * - Preserve list markers and checkboxes
 *
 * Reference: dev/specs/LOGSEQ_SPEC.md §9.7
 */

test.describe('Paste Semantics (FR-Clipboard-03)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:8000');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('[data-block-id]', { timeout: 5000 });
  });

  test('single newline paste stays inline', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Type some text
    await page.keyboard.type('Before');

    // Simulate paste with single newline
    await page.evaluate(() => {
      const text = 'Line 1\nLine 2\nLine 3';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify: Still one block with literal newlines
    const blocks = page.locator('[data-block-id]');
    await expect(blocks).toHaveCount(1);
    await expect(firstBlock).toContainText('BeforeLine 1');
    await expect(firstBlock).toContainText('Line 2');
    await expect(firstBlock).toContainText('Line 3');
  });

  test('blank line paste creates multiple blocks', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    await page.keyboard.type('First block text');

    // Simulate paste with blank lines
    await page.evaluate(() => {
      const text = '\n\nSecond block\n\nThird block';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify: Three blocks created
    const blocks = page.locator('[data-block-id]');
    await expect(blocks).toHaveCount(3);
    await expect(blocks.nth(0)).toContainText('First block text');
    await expect(blocks.nth(1)).toContainText('Second block');
    await expect(blocks.nth(2)).toContainText('Third block');
  });

  test('paste with list markers preserves formatting', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Simulate paste with list markers
    await page.evaluate(() => {
      const text = '- Item 1\n\n- Item 2\n\n- Item 3';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify: Three blocks with list markers
    const blocks = page.locator('[data-block-id]');
    await expect(blocks).toHaveCount(3);
    await expect(blocks.nth(0)).toContainText('- Item 1');
    await expect(blocks.nth(1)).toContainText('- Item 2');
    await expect(blocks.nth(2)).toContainText('- Item 3');
  });

  test('paste with checkboxes preserves formatting', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Simulate paste with checkboxes
    await page.evaluate(() => {
      const text = '- [ ] Todo 1\n\n- [x] Done task\n\n- [ ] Todo 2';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify: Three blocks with checkboxes
    const blocks = page.locator('[data-block-id]');
    await expect(blocks).toHaveCount(3);
    await expect(blocks.nth(0)).toContainText('[ ] Todo 1');
    await expect(blocks.nth(1)).toContainText('[x] Done task');
    await expect(blocks.nth(2)).toContainText('[ ] Todo 2');
  });

  test('paste at cursor position splits correctly', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    await page.keyboard.type('Start End');

    // Move cursor between "Start" and " End"
    for (let i = 0; i < 4; i++) {
      await page.keyboard.press('ArrowLeft');
    }

    // Simulate paste with blank line
    await page.evaluate(() => {
      const text = ' Middle\n\nAnother';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify: Text split correctly
    const blocks = page.locator('[data-block-id]');
    await expect(blocks).toHaveCount(2);
    await expect(blocks.nth(0)).toContainText('Start Middle');
    await expect(blocks.nth(1)).toContainText('Another End'); // Should have " End" suffix
  });

  test('paste with numbered list increments correctly', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Simulate paste with numbered list
    await page.evaluate(() => {
      const text = '1. First\n\n2. Second\n\n3. Third';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify: Three blocks with numbers preserved
    const blocks = page.locator('[data-block-id]');
    await expect(blocks).toHaveCount(3);
    await expect(blocks.nth(0)).toContainText('1. First');
    await expect(blocks.nth(1)).toContainText('2. Second');
    await expect(blocks.nth(2)).toContainText('3. Third');
  });

  test('paste with mixed content creates correct blocks', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Simulate paste with mixed content
    await page.evaluate(() => {
      const text = 'Plain text\n\n- List item\n\n1. Numbered\n\n- [ ] Checkbox';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify: Four blocks with correct formatting
    const blocks = page.locator('[data-block-id]');
    await expect(blocks).toHaveCount(4);
    await expect(blocks.nth(0)).toContainText('Plain text');
    await expect(blocks.nth(1)).toContainText('- List item');
    await expect(blocks.nth(2)).toContainText('1. Numbered');
    await expect(blocks.nth(3)).toContainText('[ ] Checkbox');
  });

  test('paste replaces selected text before inserting', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    await page.keyboard.type('Replace this text');

    // Select "this"
    await page.keyboard.press('Shift+ArrowLeft');
    await page.keyboard.press('Shift+ArrowLeft');
    await page.keyboard.press('Shift+ArrowLeft');
    await page.keyboard.press('Shift+ArrowLeft');

    // Simulate paste
    await page.evaluate(() => {
      const text = 'that';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify: Text replaced
    await expect(firstBlock).toContainText('Replace that text');
    await expect(firstBlock).not.toContainText('this');
  });

  test('cursor positioned correctly after multi-block paste', async ({ page }) => {
    // Enter edit mode
    const firstBlock = page.locator('[data-block-id]').first();
    await firstBlock.click();
    await page.keyboard.press('Enter');

    // Simulate paste that creates multiple blocks
    await page.evaluate(() => {
      const text = 'Block 1\n\nBlock 2\n\nBlock 3';
      const event = new ClipboardEvent('paste', {
        clipboardData: new DataTransfer()
      });
      event.clipboardData?.setData('text/plain', text);
      document.activeElement?.dispatchEvent(event);
    });

    // Verify cursor is at end of last block
    const cursorPos = await page.evaluate(() => {
      const sel = window.getSelection();
      return {
        offset: sel?.anchorOffset,
        text: sel?.anchorNode?.textContent
      };
    });

    expect(cursorPos.text).toContain('Block 3');
    expect(cursorPos.offset).toBe(7); // After "Block 3"
  });
});
