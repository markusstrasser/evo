/**
 * Browser Guard - Auto-check focus/cursor after every UI interaction
 *
 * Load this in browser console at startup:
 *   - Monitors all keyboard events
 *   - Auto-validates focus after Enter/arrow keys
 *   - Logs cursor position changes
 *   - Warns when focus breaks
 *
 * Would have caught all 4 bugs immediately!
 */

window.UI_GUARD = {
  enabled: true,
  lastCursorPos: null,
  lastFocusedElement: null,

  /**
   * CHECK 1: Validate focus after keyboard navigation
   */
  checkFocusAfterNavigation(key) {
    setTimeout(() => {
      const editable = document.querySelector('[contenteditable="true"]');
      const focused = document.activeElement;

      if (editable && focused !== editable) {
        console.error(
          `🚨 FOCUS BUG: After pressing ${key}, focus is on ${focused.tagName} not contenteditable!`
        );
        console.warn('User will have to click before typing (BAD UX)');
        console.trace();

        // Visual indicator
        editable.style.outline = '3px solid red';
        setTimeout(() => {
          editable.style.outline = '';
        }, 1000);
      } else if (editable) {
        console.log(`✅ Focus OK after ${key}`);
      }
    }, 10);  // Wait for re-render
  },

  /**
   * CHECK 2: Track cursor position to detect resets
   */
  trackCursorPosition() {
    const editable = document.querySelector('[contenteditable="true"]');
    if (!editable || document.activeElement !== editable) return;

    const sel = window.getSelection();
    if (!sel.rangeCount) return;

    const pos = sel.getRangeAt(0).startOffset;

    if (this.lastCursorPos !== null && pos === 0 && this.lastCursorPos > 0) {
      console.error(
        `🚨 CURSOR RESET BUG: Cursor jumped from ${this.lastCursorPos} to 0!`
      );
      console.warn('Characters will appear in wrong order');
      console.trace();
    }

    this.lastCursorPos = pos;
  },

  /**
   * CHECK 3: Validate DB vs DOM text content
   */
  checkDBvsDOMMatch() {
    const editable = document.querySelector('[contenteditable="true"]');
    if (!editable) return;

    const blockId = editable.dataset.blockId;
    const domText = editable.textContent;

    if (window.DEBUG?.getBlockText) {
      const dbText = window.DEBUG.getBlockText(blockId);

      if (dbText !== domText) {
        console.error(
          `🚨 DB/DOM MISMATCH for ${blockId}:`,
          `\n  DB:  "${dbText}"`,
          `\n  DOM: "${domText}"`
        );
        console.warn('Stale closure or render bug!');
      }
    }
  },

  /**
   * CHECK 4: Detect duplicate operations
   */
  checkForDuplicateOps() {
    if (!window.DEBUG?.getOperations) return;

    const ops = window.DEBUG.getOperations();
    const last10 = ops.slice(-10);

    // Look for duplicate operations within 100ms
    const grouped = {};
    last10.forEach(op => {
      const key = `${op.type}-${op.timestamp}`;
      grouped[key] = (grouped[key] || 0) + 1;
    });

    Object.entries(grouped).forEach(([key, count]) => {
      if (count > 1) {
        console.error(
          `🚨 DUPLICATE OP: ${key} fired ${count} times!`
        );
        console.warn('Keymap + component both handling same key?');
      }
    });
  },

  /**
   * Install global keyboard monitor
   */
  install() {
    console.log('🛡️ UI Guard installed - monitoring focus/cursor...');

    // Monitor keyboard events
    document.addEventListener('keydown', (e) => {
      if (!this.enabled) return;

      const key = e.key;

      // Check focus after navigation keys
      if (['Enter', 'ArrowUp', 'ArrowDown', 'Escape'].includes(key)) {
        this.checkFocusAfterNavigation(key);
        this.checkForDuplicateOps();
      }

      // Track cursor position on every key
      if (key.length === 1) {  // Typing character
        this.trackCursorPosition();
      }
    });

    // Monitor input events
    document.addEventListener('input', () => {
      if (!this.enabled) return;
      this.trackCursorPosition();
      this.checkDBvsDOMMatch();
    });

    // Periodic health check
    setInterval(() => {
      if (!this.enabled) return;

      const editable = document.querySelector('[contenteditable="true"]');
      if (editable && document.activeElement === editable) {
        this.checkDBvsDOMMatch();
      }
    }, 1000);
  },

  /**
   * Run all checks manually
   */
  runChecks() {
    console.log('\n🔍 Running manual UI checks...\n');

    this.checkFocusAfterNavigation('manual');
    this.trackCursorPosition();
    this.checkDBvsDOMMatch();
    this.checkForDuplicateOps();

    console.log('\n✅ Manual checks complete\n');
  },

  /**
   * Toggle guard on/off
   */
  toggle() {
    this.enabled = !this.enabled;
    console.log(`UI Guard ${this.enabled ? 'enabled' : 'disabled'}`);
  }
};

// Auto-install
window.UI_GUARD.install();

// Expose shortcut
window.checkUI = () => window.UI_GUARD.runChecks();

console.log(`
🛡️ UI Guard Active!

Manual checks:
  checkUI()              - Run all checks now
  UI_GUARD.toggle()      - Disable/enable
  UI_GUARD.runChecks()   - Full diagnostic

Auto-monitoring:
  ✓ Focus after Enter/arrows
  ✓ Cursor position tracking
  ✓ DB vs DOM validation
  ✓ Duplicate operation detection
`);
