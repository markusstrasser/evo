/**
 * Keyboard Event Helpers for E2E Testing
 *
 * IMPORTANT: Always use these helpers for keyboard interactions with contenteditable elements.
 *
 * These helpers wrap Playwright's keyboard API with:
 * - Contenteditable focus verification
 * - Correct modifier key syntax ('+' notation instead of modifiers array)
 * - Better error messages
 *
 * CRITICAL: Playwright's `page.keyboard.press(key, { modifiers: [...] })` does NOT work!
 * The modifiers option is silently ignored. You MUST use '+' notation: 'Shift+ArrowDown'
 *
 * For non-contenteditable elements, use a named helper that asserts the
 * intended focus/UI contract before dispatching the key.
 */

/**
 * Dispatch a keyboard event on the currently focused contenteditable element.
 *
 * This verifies the contenteditable focus contract, then uses Playwright's
 * '+' key-combo notation so modifier keys are delivered correctly.
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
  const { shiftKey = false, ctrlKey = false, metaKey = false, altKey = false } = options;

  // Verify contenteditable is focused
  await page.evaluate(() => {
    const elem = document.activeElement;
    if (!elem?.getAttribute('contenteditable')) {
      throw new Error(
        `No contenteditable element is focused. ` +
          `Active element: ${elem?.tagName || 'none'}\n` +
          `Tip: Use page.keyboard.press() for non-contenteditable elements.`
      );
    }
  });

  // Build key combination string (e.g., "Shift+ArrowDown")
  // Playwright's .press() uses '+' notation for modifier combinations
  const modifierParts = [];
  if (shiftKey) modifierParts.push('Shift');
  if (ctrlKey) modifierParts.push('Control');
  if (metaKey) modifierParts.push('Meta');
  if (altKey) modifierParts.push('Alt');

  const keyCombo = modifierParts.length > 0 ? `${modifierParts.join('+')}+${key}` : key;

  await page.keyboard.press(keyCombo);
}

// NOTE: typeOnContentEditable was removed - page.keyboard.type() works fine
// and has better performance. Use page.keyboard.type() for typing text.

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
    altKey: modifiers.includes('Alt'),
  };

  await pressKeyOnContentEditable(page, key, options);
}

/**
 * Press an app-global key when no contenteditable owns focus.
 *
 * Use this for idle/selected-block shortcuts that are handled by app-level
 * keyboard routing rather than the editing surface.
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 * @param {string} keyCombo - Playwright key combo string (e.g. 'Enter', 'Shift+ArrowDown')
 */
export async function pressGlobalKey(page, keyCombo) {
  await page.evaluate(() => {
    const elem = document.activeElement;
    if (elem?.getAttribute?.('contenteditable') === 'true') {
      throw new Error(
        `Expected app-global focus, but contenteditable is active. ` +
          `Use pressKeyOnContentEditable() for editing keys.`
      );
    }
  });

  await page.keyboard.press(keyCombo);
}

/**
 * Press a key handled by the quick switcher search overlay.
 *
 * The quick switcher intentionally keeps focus in its search input while
 * Arrow/Enter keys drive the overlay result list.
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 * @param {string} keyCombo - Playwright key combo string
 */
export async function pressQuickSwitcherKey(page, keyCombo) {
  await page.evaluate(() => {
    const overlay = document.querySelector('dialog.quick-switcher');
    if (!overlay) {
      throw new Error('Expected quick switcher overlay to be visible before pressing overlay key.');
    }

    const searchInput = overlay.querySelector('input[placeholder="Search pages..."]');
    if (!searchInput || document.activeElement !== searchInput) {
      const active = document.activeElement;
      throw new Error(
        `Expected quick switcher search input to be focused. ` +
          `Active element: ${active?.tagName || 'none'}`
      );
    }
  });

  await page.keyboard.press(keyCombo);
}

// ─────────────────────────────────────────────────────────────────────────────
// Cross-Platform Key Mappings
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Detect if running on macOS.
 * In Playwright, process.platform reflects the test runner's OS.
 */
export const isMac = process.platform === 'darwin';

/**
 * Cross-platform "Home" - go to start of line.
 * - macOS: Cmd+Left
 * - Windows/Linux: Home
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 */
export async function pressHome(page) {
  if (isMac) {
    await pressKeyOnContentEditable(page, 'ArrowLeft', { metaKey: true });
  } else {
    await pressKeyOnContentEditable(page, 'Home');
  }
}

/**
 * Cross-platform "End" - go to end of line.
 * - macOS: Cmd+Right
 * - Windows/Linux: End
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 */
export async function pressEnd(page) {
  if (isMac) {
    await pressKeyOnContentEditable(page, 'ArrowRight', { metaKey: true });
  } else {
    await pressKeyOnContentEditable(page, 'End');
  }
}

/**
 * Cross-platform "Mod" key helper.
 * Returns 'Meta' on Mac, 'Control' on Windows/Linux.
 * Use with pressKeyCombo for Cmd/Ctrl shortcuts.
 *
 * @example
 * await pressKeyCombo(page, 'b', [modKey]); // Bold: Cmd+B on Mac, Ctrl+B elsewhere
 */
export const modKey = isMac ? 'Meta' : 'Control';

/**
 * Cross-platform word-left navigation.
 * - macOS: Alt+Left
 * - Windows/Linux: Ctrl+Left
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 */
export async function pressWordLeft(page) {
  if (isMac) {
    await pressKeyOnContentEditable(page, 'ArrowLeft', { altKey: true });
  } else {
    await pressKeyOnContentEditable(page, 'ArrowLeft', { ctrlKey: true });
  }
}

/**
 * Cross-platform word-right navigation.
 * - macOS: Alt+Right
 * - Windows/Linux: Ctrl+Right
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 */
export async function pressWordRight(page) {
  if (isMac) {
    await pressKeyOnContentEditable(page, 'ArrowRight', { altKey: true });
  } else {
    await pressKeyOnContentEditable(page, 'ArrowRight', { ctrlKey: true });
  }
}

/**
 * Cross-platform select-to-start.
 * - macOS: Cmd+Shift+Left
 * - Windows/Linux: Shift+Home
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 */
export async function pressSelectToStart(page) {
  if (isMac) {
    await pressKeyOnContentEditable(page, 'ArrowLeft', { metaKey: true, shiftKey: true });
  } else {
    await pressKeyOnContentEditable(page, 'Home', { shiftKey: true });
  }
}

/**
 * Cross-platform select-to-end.
 * - macOS: Cmd+Shift+Right
 * - Windows/Linux: Shift+End
 *
 * @param {import('@playwright/test').Page} page - Playwright page object
 */
export async function pressSelectToEnd(page) {
  if (isMac) {
    await pressKeyOnContentEditable(page, 'ArrowRight', { metaKey: true, shiftKey: true });
  } else {
    await pressKeyOnContentEditable(page, 'End', { shiftKey: true });
  }
}
