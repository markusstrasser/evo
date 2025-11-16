# Playwright MCP Testing Guide

## Overview

This project uses the Model Context Protocol (MCP) for AI-driven browser testing via Playwright. This approach enables Claude Code to interact with the browser using structured accessibility snapshots instead of visual screenshots.

## What is MCP?

Model Context Protocol (MCP) is an open standard developed by Anthropic that enables AI models to interact with external tools, data sources, and services. For browser automation, MCP provides:

- **Semantic Context**: Access to DOM roles, labels, and states through accessibility trees
- **No Visual Processing**: Works without screenshots or vision models
- **Standardized Integration**: Consistent interface across different testing tools

## Architecture

### Key Components

1. **Playwright MCP Server**: Runs browser automation via Claude Code
2. **Accessibility Snapshots**: YAML-based representation of page structure
3. **Intent Verification**: Tests verify UI state through DOM and accessibility tree

### Testing Stack

```
Claude Code (AI Agent)
    ↓ MCP Protocol
Playwright MCP Server
    ↓ CDP (Chrome DevTools Protocol)
Browser (Chromium/Firefox/WebKit)
    ↓ Accessibility API
DOM + Accessibility Tree
```

## Best Practices

### 1. Use Accessibility Snapshots (Modern Approach)

ARIA snapshots provide a YAML representation of the page's accessibility tree, making tests:
- **Stable**: Independent of CSS class changes
- **Semantic**: Tests against user-facing behavior, not implementation
- **Maintainable**: Snapshots are human-readable and versionable

Example from our tests:
```javascript
const snapshot = await page.accessibility.snapshot();
// Verify structure without brittle CSS selectors
```

### 2. Prefer DOM Verification Over Database Inspection

**Bad** (couples tests to implementation):
```javascript
const db = await page.evaluate(() => window.DEBUG.state());
expect(db.nodes[id].props.text).toBe('expected');
```

**Good** (tests user-facing behavior):
```javascript
await expect(page.locator('[contenteditable="true"]:has-text("expected")')).toBeVisible();
const tree = await getTreeStructure(page); // via DOM
expect(tree.find(b => b.text === 'expected').depth).toBe(1);
```

### 3. Test Isolation & Session Management

Each test should:
- Start with a clean browser context
- Not depend on state from previous tests
- Use `test.beforeEach()` for setup

```javascript
test.beforeEach(async ({ page }) => {
  await page.goto('/blocks.html');
  await enterEditMode(page);
});
```

### 4. Leverage `contenteditable` and Accessibility Roles

For contenteditable-based UI (like our outliner):
- Use `[contenteditable="true"]` selectors
- Verify visible text with `:has-text()` pseudo-class
- Check ARIA roles and labels when available

```javascript
// Good: tests actual rendered content
await page.locator('[contenteditable="true"]:has-text("Block text")').click();

// Good: uses semantic structure
const blocks = await page.locator('.block').all();
```

### 5. Handle Timing Properly

Use Playwright's built-in waiting mechanisms:
```javascript
// Preferred: wait for specific condition
await page.waitForSelector('[contenteditable="true"]', { timeout: 2000 });

// Use timeouts sparingly for rendering
await page.waitForTimeout(100); // Only when necessary
```

### 6. Test Real User Interactions

Simulate actual keyboard/mouse events:
```javascript
// Good: real keyboard input (non-contenteditable)
await page.keyboard.press('Shift+Tab');
await page.keyboard.type('Block text');

// Good: mouse interactions
await page.click('.block');
await page.dblclick('.bullet');
```

**CRITICAL: Keyboard Events on `contenteditable` Elements**

Playwright's `page.keyboard.press()` API does **NOT** reliably trigger keyboard event handlers on `contenteditable` elements in this application. This can cause silent test failures where navigation appears to work but handlers aren't called.

**Problem**: Playwright's keyboard abstraction bypasses the event handlers attached to `contenteditable` elements, leading to tests that pass even when the actual feature is broken.

**Solution**: Always use `pressKeyOnContentEditable()` helper from `test/e2e/helpers/keyboard.js`:

```javascript
// ❌ WRONG: May silently fail to trigger handlers
await page.keyboard.press('ArrowLeft');

// ✅ CORRECT: Guaranteed to dispatch events properly
import { pressKeyOnContentEditable } from './helpers/keyboard.js';
await pressKeyOnContentEditable(page, 'ArrowLeft');

// ✅ With modifiers
await pressKeyOnContentEditable(page, 'ArrowUp', { shiftKey: true });

// ✅ Keyboard combos
import { pressKeyCombo } from './helpers/keyboard.js';
const isMac = process.platform === 'darwin';
await pressKeyCombo(page, 'Enter', [isMac ? 'Meta' : 'Control']);
```

**When to use `page.keyboard.press()` vs helpers**:
- Use `page.keyboard.press()`: Keyboard shortcuts on non-contenteditable elements (modals, buttons, dialogs)
- Use `pressKeyOnContentEditable()`: Any key press when a contenteditable block is focused (arrow keys, Enter, Backspace, etc.)

See `test/e2e/helpers/keyboard.js` for the complete API and examples.

### 7. Verify Both State AND Structure

For tree-based UI, check:
1. Blocks are visible (presence)
2. Tree structure is correct (hierarchy)
3. Indentation/depth is accurate (relationships)

Example:
```javascript
// Check visibility
await expect(page.locator('[contenteditable="true"]:has-text("Child")')).toBeVisible();

// Check structure
const tree = await getTreeStructure(page);
expect(tree.find(b => b.text === 'Child').depth).toBe(1);
expect(tree.find(b => b.text === 'Parent').hasChildren).toBe(true);
```

## ClojureScript-Specific Patterns

### Hot Reload Awareness

shadow-cljs provides hot reload, which can interfere with tests:
- Always reload page in `beforeEach`
- Don't rely on JavaScript state persisting between navigations
- Test against compiled output, not during active development

### Event Sourcing Architecture

Our app uses event sourcing (intents → operations → state):
```javascript
// Verify intent fired (via console logs)
const logs = await page.evaluate(() => console.logs);
expect(logs).toContain('Intent: {:type :outdent-selected}');

// Verify resulting DOM state
const blocks = await getTreeStructure(page);
expect(blocks[0].depth).toBe(0);
```

### Replicant Framework

Replicant uses virtual DOM diffing:
- Wait for renders to complete before assertions
- Use `waitForSelector` for newly created elements
- Test against actual DOM, not virtual DOM state

## Test Organization

### Critical Fixes Tests (`test/e2e/critical-fixes.spec.js`)

Tests for CRITICAL behavioral gaps:
1. **Backspace merge with children re-parenting** - Prevents data loss
2. **Direct outdenting (Logseq/Roam style)** - Right siblings become children

These tests use DOM verification exclusively.

### Feature Parity Tests (`test/e2e/editing-parity.spec.js`)

Comprehensive tests for all editing/navigation features:
- Shift+Arrow block selection
- Word navigation (Ctrl+Shift+F/B)
- Kill commands (Cmd+L, Cmd+U, Cmd+K, etc.)
- Selection operations (Cmd+A, Cmd+Shift+A)
- Undo/Redo
- Text formatting

### Helper Functions (`test/e2e/helpers/`)

Reusable test utilities:
- `keyboard.js`: **Keyboard event dispatch for contenteditable elements** (ALWAYS use for block interactions)
- `edit-mode.js`: Enter edit mode helpers
- `cursor.js`: Cursor position and selection helpers
- `blocks.js`: Block traversal and structure helpers
- `debug.js`: Debugging utilities for E2E tests

## Running Tests

```bash
# Run all E2E tests (headless, multi-browser)
bb e2e

# Watch mode with UI (for TDD)
bb e2e-watch

# Run with visible browser (debugging)
bb e2e-headed

# Run specific test file
npx playwright test test/e2e/critical-fixes.spec.js
```

## Common Pitfalls

### 1. Flaky Tests Due to Timing

**Problem**: Tests fail intermittently due to race conditions

**Solution**: Use Playwright's auto-waiting and explicit waits
```javascript
// Bad: hardcoded timeout without condition
await page.waitForTimeout(500);

// Good: wait for specific condition
await page.waitForSelector('[contenteditable="true"]');
```

### 2. Brittle Selectors

**Problem**: Tests break when CSS classes change

**Solution**: Use semantic selectors (contenteditable, ARIA roles, data attributes)
```javascript
// Bad: depends on CSS classes
await page.click('.block-component-style-123');

// Good: semantic selector
await page.click('[contenteditable="true"]');
```

### 3. Testing Implementation Instead of Behavior

**Problem**: Tests verify internal state instead of user-facing behavior

**Solution**: Test what users see and interact with
```javascript
// Bad: testing database structure
const db = await page.evaluate(() => window.DEBUG.state());
expect(db.nodes[id].props.text).toBe('expected');

// Good: testing visible UI
await expect(page.locator('[contenteditable="true"]:has-text("expected")')).toBeVisible();
```

### 4. Ignoring Accessibility

**Problem**: Tests don't verify screen reader compatibility

**Solution**: Check ARIA roles and accessibility tree structure
```javascript
const snapshot = await page.accessibility.snapshot();
expect(snapshot.role).toBe('textbox');
```

## AI-Driven Testing Considerations

When using Claude Code with MCP for testing:

1. **Prefer snapshots over screenshots**: Accessibility snapshots are more reliable
2. **Use structured assertions**: Make it easy for AI to understand test failures
3. **Write descriptive test names**: Helps AI understand context
4. **Document critical behaviors**: AI can generate regression tests from specs

Example of AI-friendly test structure:
```javascript
test('CRITICAL: backspace merge re-parents children to previous block', async ({ page }) => {
  // Setup: Clear description of initial state
  // Action: Single, clear user action
  // Verification: Multiple specific assertions with clear expectations
});
```

## References

- [Playwright Accessibility Testing](https://playwright.dev/docs/accessibility-testing)
- [Model Context Protocol Docs](https://modelcontextprotocol.io/)
- [ARIA Snapshot Testing (2024)](https://medium.com/syntest/how-aria-snapshot-testing-solves-common-playwright-issues-11d2678c4836)
- [ClojureScript Testing Patterns](https://betweentwoparens.com/blog/clojurescript-test-setup/)

## Future Improvements

- [ ] Add visual regression testing with Applitools or Percy
- [ ] Implement property-based testing for tree operations
- [ ] Add performance benchmarks (LCP, FID, CLS)
- [ ] Set up CI/CD with parallel test execution
- [ ] Add cross-browser visual comparison
- [ ] Integrate with Lighthouse for a11y audits
