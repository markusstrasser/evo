/**
 * Keyboard Event Helpers for E2E Testing
 *
 * IMPORTANT: Playwright's `page.keyboard.press()` API does NOT properly trigger
 * keyboard event handlers on contenteditable elements in this application.
 *
 * Always use these helpers instead of `page.keyboard.press()` when testing
 * keyboard interactions with contenteditable blocks.
 *
 * For non-contenteditable elements (modals, buttons, etc.), `page.keyboard.press()`
 * works fine and can be used directly.
 */

/**
 * Dispatch a keyboard event on the currently focused contenteditable element.
 *
 * This creates a synthetic KeyboardEvent and dispatches it directly on the
 * active element, bypassing Playwright's keyboard abstraction which doesn't
 * properly trigger handlers on contenteditable elements.
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 * @param {string} key - Key name (e.g., 'ArrowLeft', 'Enter', 'a')
 * @param {Object} options - Additional options
 * @param {boolean} options.shiftKey - Whether Shift is pressed
 * @param {boolean} options.ctrlKey - Whether Ctrl is pressed (Windows/Linux)
 * @param {boolean} options.metaKey - Whether Meta/Cmd is pressed (macOS)
 * @param {boolean} options.altKey - Whether Alt is pressed
 *
 * @example
 * // Navigate left at block boundary
 * await pressKeyOnContentEditable(page, 'ArrowLeft');
 *
 * @example
 * // Create new block with Enter
 * await pressKeyOnContentEditable(page, 'Enter');
 *
 * @example
 * // Select with Shift+ArrowDown
 * await pressKeyOnContentEditable(page, 'ArrowDown', { shiftKey: true });
 *
 * @example
 * // Bold text with Mod+b
 * const isMac = process.platform === 'darwin';
 * await pressKeyOnContentEditable(page, 'b', {
 *   metaKey: isMac,
 *   ctrlKey: !isMac
 * });
 */
export async function pressKeyOnContentEditable(page, key, options = {}) {
  const {
    shiftKey = false,
    ctrlKey = false,
    metaKey = false,
    altKey = false
  } = options;

  await page.evaluate(({ key, shiftKey, ctrlKey, metaKey, altKey }) => {
    const elem = document.activeElement;

    if (!elem || !elem.getAttribute('contenteditable')) {
      throw new Error(
        `No contenteditable element is focused. ` +
        `Active element: ${elem?.tagName || 'none'}\n` +
        `Tip: Use page.keyboard.press() for non-contenteditable elements.`
      );
    }

    // Map key names to key codes (for legacy compatibility)
    const keyCodeMap = {
      'ArrowLeft': 37,
      'ArrowUp': 38,
      'ArrowRight': 39,
      'ArrowDown': 40,
      'Home': 36,
      'End': 35,
      'Enter': 13,
      'Escape': 27,
      'Backspace': 8,
      'Delete': 46,
      'Tab': 9
    };

    const keyCode = keyCodeMap[key] || key.charCodeAt(0);

    const event = new KeyboardEvent('keydown', {
      key,
      code: key,
      keyCode,
      which: keyCode,
      shiftKey,
      ctrlKey,
      metaKey,
      altKey,
      bubbles: true,
      cancelable: true
    });

    elem.dispatchEvent(event);
  }, { key, shiftKey, ctrlKey, metaKey, altKey });
}

/**
 * Type text character-by-character on contenteditable element.
 *
 * For simple typing, `page.keyboard.type()` usually works. Use this helper
 * if you need guaranteed event dispatch for each character.
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 * @param {string} text - Text to type
 *
 * @example
 * await typeOnContentEditable(page, 'Hello world');
 */
export async function typeOnContentEditable(page, text) {
  for (const char of text) {
    await pressKeyOnContentEditable(page, char);
    await page.waitForTimeout(10); // Small delay between characters
  }
}

/**
 * Press a modifier key combination on contenteditable element.
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 * @param {string} key - Key name
 * @param {string[]} modifiers - Array of modifier keys ('Shift', 'Control', 'Meta', 'Alt')
 *
 * @example
 * // Mod+Enter (Meta on Mac, Ctrl on Windows/Linux)
 * const isMac = process.platform === 'darwin';
 * await pressKeyCombo(page, 'Enter', [isMac ? 'Meta' : 'Control']);
 *
 * @example
 * // Shift+ArrowUp
 * await pressKeyCombo(page, 'ArrowUp', ['Shift']);
 */
export async function pressKeyCombo(page, key, modifiers = []) {
  const options = {
    shiftKey: modifiers.includes('Shift'),
    ctrlKey: modifiers.includes('Control'),
    metaKey: modifiers.includes('Meta'),
    altKey: modifiers.includes('Alt')
  };

  await pressKeyOnContentEditable(page, key, options);
}
