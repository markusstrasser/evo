/**
 * Cursor position utilities for E2E testing.
 * Returns structured data for AI-parseable assertions.
 */

import { expect } from '@playwright/test';

/**
 * Get current cursor position in contenteditable element.
 * Returns {offset, text, elementId} for AI-parseable assertions.
 */
export async function getCursorPosition(page) {
  return await page.evaluate(() => {
    const sel = window.getSelection();
    const elem = document.activeElement;

    if (!elem || !sel || sel.rangeCount === 0) {
      return { offset: null, text: null, elementId: null };
    }

    return {
      offset: sel.focusOffset,
      text: elem.textContent,
      elementId:
        elem.id ||
        elem.getAttribute('data-block-id') ||
        elem.closest('[data-block-id]')?.getAttribute('data-block-id'),
      nodeType: sel.focusNode?.nodeType,
      isCollapsed: sel.isCollapsed,
    };
  });
}

/**
 * Set cursor position in specific block.
 */
export async function setCursorPosition(page, blockId, offset) {
  await page.evaluate(
    ({ blockId, offset }) => {
      const baseSelector = `[data-block-id="${blockId}"]`;
      const editable =
        document.querySelector(`${baseSelector}[contenteditable="true"]`) ||
        document.querySelector(`${baseSelector} [contenteditable="true"]`) ||
        document.querySelector(`${baseSelector}.content-edit`) ||
        document.querySelector(`${baseSelector} .content-edit`);

      if (!editable) return false;

      // Focus the actual contenteditable span so it receives keyboard events
      editable.focus();

      const range = document.createRange();
      const sel = window.getSelection();

      // Find the text node containing actual block text (contenteditable children only)
      const walker = document.createTreeWalker(editable, NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          return node.textContent.trim() === '•'
            ? NodeFilter.FILTER_REJECT
            : NodeFilter.FILTER_ACCEPT;
        },
      });

      let textNode = walker.nextNode();

      if (!textNode) {
        if (editable.firstChild && editable.firstChild.nodeType === Node.TEXT_NODE) {
          textNode = editable.firstChild;
        } else {
          textNode = document.createTextNode('');
          editable.appendChild(textNode);
        }
      }

      const clampedOffset = Math.min(offset, textNode.length ?? 0);
      range.setStart(textNode, clampedOffset);
      range.setEnd(textNode, clampedOffset);
      sel.removeAllRanges();
      sel.addRange(range);

      return true;
    },
    { blockId, offset }
  );
}

/**
 * Assert cursor at expected position.
 * Prints structured diff on failure (AI-readable).
 */
export async function expectCursorAt(page, expectedOffset, expectedText) {
  const cursor = await getCursorPosition(page);

  if (cursor.offset !== expectedOffset) {
    console.log('\n=== CURSOR POSITION MISMATCH ===');
    console.log('Expected offset:', expectedOffset);
    console.log('Actual offset:', cursor.offset);
    console.log('Text:', cursor.text);
    console.log('Element:', cursor.elementId);
  }

  expect(cursor.offset).toBe(expectedOffset);

  if (expectedText !== undefined) {
    expect(cursor.text).toContain(expectedText);
  }
}

/**
 * Type text character-by-character and verify cursor advances.
 */
export async function typeAndVerifyCursor(page, text) {
  const results = [];

  for (const char of text) {
    const before = await getCursorPosition(page);
    await page.keyboard.type(char);
    // Wait for cursor to advance (no fixed timeout needed)
    await page.waitForFunction(
      (expectedOffset) => {
        const sel = window.getSelection();
        return sel && sel.anchorOffset === expectedOffset;
      },
      before.offset + 1,
      { timeout: 2000 }
    );
    const after = await getCursorPosition(page);

    results.push({
      char,
      offsetBefore: before.offset,
      offsetAfter: after.offset,
      advanced: after.offset === before.offset + 1,
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
