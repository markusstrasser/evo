/**
 * Block manipulation helpers for E2E testing.
 */

import { getCursorPosition } from './cursor.js';

/**
 * Get all blocks on page with their text content.
 */
export async function getAllBlocks(page) {
  return await page.evaluate(() => {
    const blocks = document.querySelectorAll('[data-block-id]');
    return Array.from(blocks).map((block, idx) => {
      const editable = block.querySelector('[contenteditable="true"]') ||
                       (block.getAttribute('contenteditable') === 'true' ? block : null);
      return {
        index: idx,
        id: block.getAttribute('data-block-id'),
        text: editable?.textContent || block.textContent?.replace(/^•\s*/, ''),
        isFocused: editable === document.activeElement || block.contains(document.activeElement)
      };
    });
  });
}

/**
 * Type text character-by-character and verify cursor advances.
 */
export async function typeAndVerifyCursor(page, text) {
  const results = [];

  for (const char of text) {
    const before = await getCursorPosition(page);
    await page.keyboard.type(char);
    await page.waitForTimeout(50);  // Wait for state update
    const after = await getCursorPosition(page);

    results.push({
      char,
      offsetBefore: before.offset,
      offsetAfter: after.offset,
      advanced: after.offset === before.offset + 1
    });

    // Assert cursor advanced
    if (after.offset !== before.offset + 1) {
      console.log('\n=== CURSOR JUMP DETECTED ===');
      console.log('Character:', char);
      console.log('Before:', before);
      console.log('After:', after);
      throw new Error(`Cursor jumped: expected ${before.offset + 1}, got ${after.offset}`);
    }
  }

  return results;
}
