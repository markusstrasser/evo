# Agent Testing

This is the browser-debugging workflow for agents working on Evo.
Canonical test truth stays in repo-owned `bb` and Playwright tests.

## Baseline Commands

```bash
npm start
npm run test:e2e:smoke
npm run test:e2e:ui
npm run test:e2e:debug
npx playwright test --debug=cli
npx playwright show-report
bb e2e:clean
```

Run one spec directly with:

```bash
PLAYWRIGHT_HEADLESS=true npx playwright test --project=chromium test/e2e/<spec>.spec.js
```

## Keyboard Contracts

Use `pressKeyOnContentEditable(page, key)` for editing-surface keys:
`Enter`, arrows, `Backspace`, `Delete`, `Tab`, and contenteditable modifier
combos. It asserts that a contenteditable element owns focus before pressing.

Use `pressGlobalKey(page, keyCombo)` for app-global idle/selected-block keys.
It asserts that no contenteditable owns focus.

Use `pressQuickSwitcherKey(page, keyCombo)` for Arrow/Enter keys handled by
the quick switcher search dialog. It asserts that the dialog is visible and
its search input is focused.

Do not replace overlay, idle, or selected-block keys with the contenteditable
helper. The helper should state the focus contract being tested.

## Fixture Contracts

`window.TEST_HELPERS.loadFixture` and direct fixture transactions are for setup
only. Use them to create deterministic document graphs before the behavior
under test starts.

Do not use fixture injection as the action under test. For user-facing flows,
drive the real UI or dispatch the same intent the UI would dispatch, then
assert visible DOM, focus, selection, or a narrow app-state contract.

## Debugging Failures

Use traces first:

```bash
npx playwright show-report
npx playwright show-trace test-results/<case>/trace.zip
```

Use the UI/debug modes when the failure depends on focus, selection, or event
ordering:

```bash
npm run test:e2e:ui
npm run test:e2e:debug
npx playwright test --debug=cli
```

For contenteditable failures, assert both browser state and app state when
relevant: focused element, `window.getSelection()`, visible text, and narrow
`window.TEST_HELPERS` contracts such as `getBlockText`.

## Playwright MCP

Playwright MCP is for exploration and debugging, not CI truth.

Recommended exploration mode:

```bash
npx @playwright/mcp@latest --caps=testing,storage
```

Debug-only mode:

```bash
npx @playwright/mcp@latest --caps=devtools
```

Use MCP to inspect accessibility snapshots, generate candidate locators, and
understand failing UI state. Convert any useful finding into normal Playwright
or CLJS tests before committing.

Do not commit self-healing tests, natural-language runtime assertions, SaaS
runner artifacts, or selector-only changes that do not prove user behavior.
