# Cursor Fix Attempts Log

Historical investigation log for cursor/selection issues. Kept for context and pattern recognition. Most recent attempts at top.

## Context

The block editor (`src/components/block.cljs`) handles cursor placement manually due to contenteditable re-rendering. This log tracks past debugging sessions, failed approaches, and working solutions.

## Key Learnings

1. **Never use `page.keyboard.press()` on contenteditable**: Playwright doesn't dispatch keyboard events reliably. Use `pressKeyOnContentEditable()` helper.

2. **Guard cursor placement**: Use `__lastAppliedCursorPos` pattern to prevent redundant `setSelectionRange` calls during composition.

3. **Single dispatcher rule**: Editing keys (arrows, Enter) must be handled ONLY in `components/block.cljs`, not in global keymap. Double-dispatch causes cursor jumps.

4. **Focus-first pattern**: Always restore focus before setting cursor position:
   ```javascript
   node.focus();  // Must come first
   setCursor(node, position);
   ```

5. **Re-render during composition breaks IME**: Defer text updates until `compositionend` event fires.

## Investigation Timeline

### 2025-01: Playwright Keyboard Events Not Firing

**Issue**: E2E tests passed but actual app behavior failed for arrow keys.

**Investigation**:
- Tests used `page.keyboard.press('ArrowLeft')`
- Worked in Firefox, failed in Chromium/WebKit
- `contenteditable` elements don't receive synthetic keyboard events reliably in Playwright

**Solution**:
- Created `test/e2e/helpers/keyboard.js` with `pressKeyOnContentEditable()`
- Dispatches native `KeyboardEvent` with `bubbles: true`
- Added `bb lint:e2e-keyboard` to detect direct `page.keyboard.press()` usage in tests

**Files changed**:
- `test/e2e/helpers/keyboard.js` (new helper)
- `scripts/lint-e2e-keyboard.js` (new linter)
- All E2E test files migrated to helper

### 2024-12: Focus Loss After Enter

**Issue**: After pressing Enter to create new block, typing didn't work until clicking.

**Investigation**:
- Focus moved to `<body>` after re-render
- `:replicant/on-mount` not sufficient (only fires once)
- `:replicant/on-render` needed but was creating infinite loop

**Failed Attempts**:
1. Restore focus in intent handler → Too early (before render)
2. Use `setTimeout` in handler → Race condition with Replicant
3. Focus in `:replicant/on-mount` → Only works for first render

**Solution**:
- Use `:replicant/on-render` lifecycle hook
- Guard with condition: only focus when this block is editing target
- Check kernel's `:editing-block-id` to avoid focusing wrong block

**Files changed**:
- `src/components/block.cljs:on-render` - Added focus guard

### 2024-11: Cursor Reset to Beginning

**Issue**: After typing, cursor jumped to position 0, causing text to appear backwards.

**Investigation**:
- Text updates triggered re-render
- Re-render called `setSelectionRange(0, 0)` due to stale `lastCursorPos`
- Component captured cursor position at render time, not event time

**Failed Attempts**:
1. Store cursor in atom → Still read stale value during render
2. Defer `setSelectionRange` with `setTimeout` → Caused visible flash
3. Use `beforeinput` event → Not fired for all input methods (voice, paste)

**Solution**:
- Introduced `__lastAppliedCursorPos` guard on DOM node
- Only call `setSelectionRange` if position actually changed
- Set guard value immediately after cursor placement
- Check guard before every placement attempt

**Files changed**:
- `src/components/block.cljs:set-cursor-position` - Added guard check
- All lifecycle hooks updated to check `__lastAppliedCursorPos`

### 2024-10: Double-Dispatch on Arrow Keys

**Issue**: Pressing ArrowDown triggered two navigation operations, cursor jumped two blocks.

**Investigation**:
- Global keymap bound `ArrowDown` → dispatch intent
- Component also bound `onKeyDown` → dispatch intent
- Both dispatchers active in editing mode

**Failed Attempts**:
1. Use `stopPropagation()` in component → Didn't prevent global handler
2. Conditional global handler → Too complex, easy to break
3. Use capture phase → Broke other event handlers

**Solution**:
- **Single dispatcher rule**: Editing keys handled ONLY in component
- Remove arrow/Enter bindings from global keymap when in `:editing` context
- Document rule in `docs/RENDERING_AND_DISPATCH.md`
- All editing keys now exclusively in `components/block.cljs`

**Files changed**:
- `keymap/bindings_data.cljc` - Removed editing context arrows
- `src/components/block.cljs` - Now sole owner of editing navigation
- `docs/RENDERING_AND_DISPATCH.md` - Documented single dispatcher rule

### 2024-09: DB/DOM Text Mismatch

**Issue**: Operations worked on stale text, causing data loss.

**Investigation**:
- Event handler closure captured text at render time
- By time handler executed (after user typed), text was outdated
- Handler sent operation with stale `old-text` value

**Failed Attempts**:
1. Pass text as parameter → Still captured at render time
2. Use ref to mutable cell → Not idiomatic Clojure
3. Read from atom in handler → Need DB subscription, too complex

**Solution**:
- Never capture text in closure
- Always read text from current DB at handler invocation time
- Handler reads `@db-atom` or calls `get-current-block` helper
- No stale captures possible

**Files changed**:
- `src/components/block.cljs` - All handlers now read fresh state
- Added `get-current-block-text` helper function

### 2024-08: IME Composition Broken

**Issue**: Japanese/Chinese input broke (cursor jumped during composition).

**Investigation**:
- Text updates fired on every `input` event
- Re-render during composition moved cursor mid-character
- IME expects DOM stability until `compositionend`

**Failed Attempts**:
1. Throttle text updates → Still too frequent for IME
2. Debounce updates → Caused input lag
3. Disable re-render during composition → Lost state sync

**Solution**:
- Track composition state (`isComposing` flag)
- Buffer text updates during composition
- Flush on `compositionend` event
- Never re-render contenteditable during composition

**Files changed**:
- `src/components/block.cljs` - Added composition event handlers
- `src/components/block.cljs:on-input` - Check `isComposing` flag

## Automated Guards

Current automated detection (see `dev/browser_guard.js`):

1. **Focus validation** - Warns when focus breaks after navigation
2. **Cursor tracking** - Detects cursor resets
3. **DB/DOM sync** - Catches text mismatches
4. **Duplicate ops** - Identifies double-dispatch

Browser guard auto-loads in development via shadow-cljs preloads.

## Testing Coverage

E2E scenarios covering cursor/selection (see `test/e2e/`):

- `navigation-selection-parity.spec.js` - Arrow key navigation with cursor
- `editing-boundary.spec.js` - Cursor at block boundaries
- `ime-composition.spec.js` - Japanese/Chinese input
- `focus-restoration.spec.js` - Focus after Enter/navigation

All tests use `pressKeyOnContentEditable()` helper, never `page.keyboard.press()` directly.

## Related Documentation

- `docs/CONTENTEDITABLE_DEBUGGING.md` - Debugging tactics and common fixes
- `docs/RENDERING_AND_DISPATCH.md` - Lifecycle and dispatch rules
- `docs/PLAYWRIGHT_MCP_TESTING.md` - E2E testing guide
- `CLAUDE.md` - Keyboard & selection gotchas
- `dev/browser_guard.js` - Automated guard implementation
