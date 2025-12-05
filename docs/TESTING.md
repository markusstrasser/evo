# Testing

## Commands

```bash
# Unit tests (ClojureScript)
bb test              # Full suite
bb test:view         # View-only (<1s)
bb test-watch        # Watch mode

# E2E tests (Playwright)
bb e2e               # All E2E
bb e2e-headed        # Visible browser
bb e2e-debug         # Playwright debugger
bb e2e-watch         # Watch with UI

# Quality gates
bb check             # Lint + compile
bb lint:e2e-keyboard # Check for keyboard issues
```

---

## Philosophy

Replicant's superpower: UI is pure data (hiccup). Test without a browser:
1. Call view functions with state
2. Inspect resulting hiccup
3. Extract actions from event handlers
4. Test handlers as pure functions

---

## E2E Quick Start

```javascript
import { test, expect } from '@playwright/test';
import { selectPage, enterEditMode, exitEditMode, waitForState, getFirstBlockId } from './helpers/index.js';

test('example', async ({ page }) => {
  await page.goto('/index.html?test=true');
  await selectPage(page);

  const blockId = await getFirstBlockId(page);
  await enterEditMode(page, blockId);
  await page.keyboard.type('Hello');
  await exitEditMode(page);
});
```

---

## Critical: Keyboard on Contenteditable

Playwright's keyboard API works, but use helpers and `'+'` notation:

```javascript
import { pressKeyOnContentEditable, pressKeyCombo } from './helpers/keyboard.js';

// ✅ CORRECT
await pressKeyOnContentEditable(page, 'Enter');
await pressKeyCombo(page, 'ArrowDown', ['Shift']);
await page.keyboard.press('Shift+ArrowDown');  // '+' notation works

// ❌ WRONG - modifiers array is ignored!
await page.keyboard.press('ArrowDown', { modifiers: ['Shift'] });
```

Run `bb lint:e2e-keyboard` to detect issues.

---

## E2E Helpers

| Helper | Purpose |
|--------|---------|
| `selectPage(page)` | Close overlays, load page |
| `selectBlock(page, id)` | Select block |
| `enterEditMode(page, id)` | Enter edit (selects first if needed) |
| `exitEditMode(page)` | Exit edit |
| `waitForState(page, state)` | Wait for 'idle'/'selection'/'editing' |
| `waitForEditing(page, id)` | Wait for block to be editing |
| `getFirstBlockId(page)` | Get first block ID |
| `getBlockText(page, id)` | Get block text |
| `countBlocks(page)` | Count blocks |
| `debugIntent(page, intent)` | Check if intent allowed |

---

## TEST_HELPERS API

```javascript
// Intent dispatch (goes through state machine)
window.TEST_HELPERS.dispatchIntent({ type: 'enter-edit', 'block-id': id });

// Direct DB manipulation (bypasses state machine)
window.TEST_HELPERS.setBlockText(id, 'text');
window.TEST_HELPERS.getBlockText(id);

// State inspection
window.TEST_HELPERS.getSession();
window.TEST_HELPERS.snapshot();

// Debug blocked intents
window.TEST_HELPERS.debugIntent({ type: 'enter-edit' });
// → { allowed: false, currentState: 'idle', reason: '...' }
```

---

## State Machine Rules

Tests must respect the state machine:
- `:enter-edit` requires `:selection` state (select first)
- `:update-content` requires `:editing` state
- `:selection` works from any state

```javascript
// ❌ FAILS - can't enter-edit from idle
await dispatchIntent({ type: 'enter-edit', 'block-id': id });

// ✅ WORKS - select first
await selectBlock(page, id);
await enterEditMode(page, id);
```

---

## Common Patterns

### Testing Enter

```javascript
test('Enter creates block', async ({ page }) => {
  await enterEditMode(page, blockId);
  await page.keyboard.type('First');
  await page.keyboard.press('Enter');
  expect(await countBlocks(page)).toBe(2);
});
```

### Testing Selection

```javascript
test('Escape selects block', async ({ page }) => {
  await enterEditMode(page, blockId);
  await exitEditMode(page);
  const state = await getStateMachineState(page);
  expect(state).toBe('selection');
});
```

### Debugging Blocked Intents

```javascript
const debug = await debugIntent(page, { type: 'enter-edit', 'block-id': id });
console.log(debug);  // Shows why it was blocked
```

---

## Troubleshooting

**Intent blocked**: Use `debugIntent()` to see why.

**Keyboard not working**: Use `pressKeyOnContentEditable()`.

**Flaky tests**: Replace `waitForTimeout` with `waitForState`.

**State not updating**: Check `getStateSnapshot(page)`.
