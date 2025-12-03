# E2E Testing Guide

End-to-end tests for Evo use Playwright with intent-based helpers that bypass unreliable DOM interactions.

## Quick Start

```javascript
import { test, expect } from '@playwright/test';
import {
  selectPage,
  selectBlock,
  enterEditMode,
  exitEditMode,
  waitForState,
  waitForBlocks,
  getFirstBlockId,
  debugIntent
} from './helpers/index.js';

test('example test', async ({ page }) => {
  // Load app in test mode (empty DB)
  await page.goto('/index.html?test=true');
  await selectPage(page);  // Close overlays, load blocks
  await waitForBlocks(page);

  // Get first block and enter edit mode
  const blockId = await getFirstBlockId(page);
  await enterEditMode(page, blockId);

  // Type (real keyboard is fine for text input)
  await page.keyboard.type('Hello World');

  // Exit edit mode
  await exitEditMode(page);
  await waitForState(page, 'idle');
});
```

## Key Principles

### 1. Use Intent Dispatch for Actions (Except Keyboard)

For most actions, use intent helpers instead of DOM clicks:

```javascript
// ❌ UNRELIABLE - may silently fail
await page.click('[data-block-id="123"]');

// ✅ RELIABLE - uses TEST_HELPERS API
await enterEditMode(page, blockId);
await selectBlock(page, blockId);
await exitEditMode(page);
```

**Exception: Keyboard events on contenteditable WORK with proper helpers**

Playwright's native keyboard API **DOES** work, but you must:
1. Use the keyboard helpers from `test/e2e/helpers/keyboard.js`
2. Use `'+'` notation for modifiers: `'Shift+ArrowDown'`, not `{ modifiers: ['Shift'] }`

```javascript
import { pressKeyOnContentEditable, pressKeyCombo } from './helpers/keyboard.js';

// ✅ WORKS - Use keyboard helpers for contenteditable
await pressKeyOnContentEditable(page, 'Enter');
await pressKeyCombo(page, 'ArrowDown', ['Shift']);  // Shift+ArrowDown

// ❌ WRONG - Don't use raw page.keyboard.press() with modifiers object
await page.keyboard.press('ArrowDown', { modifiers: ['Shift'] });  // Modifiers ignored!

// ✅ CORRECT - Playwright's '+' notation works
await page.keyboard.press('Shift+ArrowDown');  // OK but prefer helper for contenteditable
```

**Why keyboard helpers?**
- They verify contenteditable is focused before pressing keys
- They use Playwright's `'+'` notation which actually works (not `modifiers` array)
- Better error messages if element isn't focused

### 2. Wait for State, Not Time

Replace fragile `waitForTimeout` with deterministic state waits:

```javascript
// ❌ FRAGILE - arbitrary delay
await page.waitForTimeout(100);

// ✅ DETERMINISTIC - waits for actual state change
await waitForState(page, 'editing');
await waitForEditing(page, blockId);
await waitForSelection(page, blockId);
await waitForIdle(page);
```

### 3. Use Direct DB Manipulation for Fixtures

For test setup, bypass the state machine entirely:

```javascript
// Set block text without entering edit mode
await page.evaluate(({ id, text }) => {
  window.TEST_HELPERS.setBlockText(id, text);
}, { id: blockId, text: 'Test content' });
```

### 4. Debug Blocked Intents

When a test fails because an intent was blocked:

```javascript
const debug = await debugIntent(page, { type: 'enter-edit', 'block-id': blockId });
console.log(debug);
// {
//   allowed: false,
//   currentState: 'idle',
//   intentType: 'enter-edit',
//   requiredStates: ['selection'],
//   reason: 'Intent :enter-edit requires states #{:selection} but current state is :idle'
// }
```

## Available Helpers

### From `helpers/index.js`

| Helper | Purpose |
|--------|---------|
| `selectPage(page, name?)` | Close overlays, switch to page |
| `selectBlock(page, blockId)` | Select a block (selection state) |
| `clearSelection(page)` | Clear all selection |
| `enterEditMode(page, blockId, cursorAt?)` | Enter edit mode (selects first if needed) |
| `exitEditMode(page)` | Exit edit mode |
| `updateBlockText(page, blockId, text)` | Set block text directly (bypasses state machine) |
| `getBlockText(page, blockId)` | Get block's text content |
| `getFirstBlockId(page)` | Get first block's ID |
| `getBlockIdAt(page, index)` | Get block ID at index |
| `countBlocks(page)` | Count total blocks |
| `isEditing(page)` | Check if any block is being edited |
| `isBlockSelected(page, blockId)` | Check if block is selected |
| `countSelectedBlocks(page)` | Count selected blocks |
| `waitForBlocks(page, timeout?)` | Wait for blocks to render |
| `waitForState(page, state, timeout?)` | Wait for state machine state |
| `waitForEditing(page, blockId, timeout?)` | Wait for specific block to be editing |
| `waitForSelection(page, blockIds, timeout?)` | Wait for blocks to be selected |
| `waitForIdle(page, timeout?)` | Wait for idle state |
| `getSession(page)` | Get full session state |
| `getStateMachineState(page)` | Get current state: 'idle', 'selection', 'editing' |
| `getStateSnapshot(page)` | Get debug snapshot of app state |
| `debugIntent(page, intent)` | Check if intent would be allowed |
| `getSelectedBlockIds(page)` | Get IDs of selected blocks from session |
| `pressKeyOnContentEditable(page, key)` | Reliable key press on contenteditable |

## TEST_HELPERS API

The app exposes `window.TEST_HELPERS` for E2E tests:

```javascript
// Intent dispatch (goes through state machine)
window.TEST_HELPERS.dispatchIntent({ type: 'selection', mode: 'replace', ids: 'block-1' });
window.TEST_HELPERS.dispatchIntent({ type: 'enter-edit', 'block-id': 'block-1' });
window.TEST_HELPERS.dispatchIntent({ type: 'exit-edit' });

// Direct DB manipulation (bypasses state machine - for fixtures)
window.TEST_HELPERS.setBlockText('block-1', 'New text');
window.TEST_HELPERS.getBlockText('block-1');

// State inspection
window.TEST_HELPERS.getDb();      // Full database
window.TEST_HELPERS.getSession(); // Session state (selection, editing, etc.)

// Debugging
window.TEST_HELPERS.debugIntent({ type: 'enter-edit' });
// → { allowed: false, currentState: 'idle', reason: '...' }

window.TEST_HELPERS.snapshot();
// → { state: 'editing', editingBlockId: 'block-1', selectedIds: [], ... }

// Reset
window.TEST_HELPERS.resetToEmptyDb();
```

## State Machine Awareness

Tests must respect the UI state machine. See `docs/STATE_MACHINE.md` for details.

**Key rules:**
- `:enter-edit` requires `:selection` state (select block first)
- `:update-content` requires `:editing` state
- `:selection` works from any state

```javascript
// ❌ FAILS - can't enter-edit from idle
await page.evaluate(() => {
  window.TEST_HELPERS.dispatchIntent({ type: 'enter-edit', 'block-id': 'x' });
});

// ✅ WORKS - select first, then enter-edit
await selectBlock(page, blockId);  // idle → selection
await enterEditMode(page, blockId); // selection → editing
```

## Running Tests

```bash
# Run all E2E tests
bb e2e

# Run with visible browser
bb e2e-headed

# Run with Playwright debugger
bb e2e-debug

# Watch mode with UI
bb e2e-watch

# Filter by test name
bb e2e -- --grep "Enter"

# Run specific file
bb e2e -- test/e2e/enter-escape-parity.spec.js
```

## Common Patterns

### Testing Enter Key Behavior

```javascript
test('Enter creates new block', async ({ page }) => {
  await page.goto('/index.html?test=true');
  await selectPage(page);

  const blockId = await getFirstBlockId(page);
  await enterEditMode(page, blockId);

  await page.keyboard.type('First block');
  await page.keyboard.press('Enter');

  // Verify new block created
  const blocks = await countBlocks(page);
  expect(blocks).toBe(2);
});
```

### Testing Selection State

```javascript
test('Escape exits edit without selecting', async ({ page }) => {
  const blockId = await getFirstBlockId(page);
  await enterEditMode(page, blockId);

  await exitEditMode(page);

  // Verify: idle state, nothing selected
  const state = await getStateMachineState(page);
  expect(state).toBe('idle');

  const selected = await getSelectedBlockIds(page);
  expect(selected).toHaveLength(0);
});
```

### Testing Undo/Redo

```javascript
test('undo restores text', async ({ page }) => {
  const blockId = await getFirstBlockId(page);
  await enterEditMode(page, blockId);

  await page.keyboard.type('Hello');

  const isMac = process.platform === 'darwin';
  await page.keyboard.press(isMac ? 'Meta+z' : 'Control+z');

  const text = await getBlockText(page, blockId);
  expect(text).toBe('');
});
```

## Troubleshooting

### Intent Blocked Silently

Use `debugIntent` to see why:

```javascript
const debug = await debugIntent(page, { type: 'enter-edit', 'block-id': blockId });
console.log('Debug:', debug);
```

### Keyboard Events Not Working

Use `pressKeyOnContentEditable` for contenteditable elements:

```javascript
import { pressKeyOnContentEditable } from './helpers/index.js';
await pressKeyOnContentEditable(page, 'ArrowLeft');
```

### State Not Updating

Check the state machine state:

```javascript
const snapshot = await getStateSnapshot(page);
console.log('State:', snapshot);
```

### Flaky Tests

Replace `waitForTimeout` with proper state waits:

```javascript
// Before
await page.waitForTimeout(100);

// After
await waitForState(page, 'editing');
```
