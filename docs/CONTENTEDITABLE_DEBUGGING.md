# ContentEditable Debugging Guide

This guide covers debugging tactics for contenteditable-related issues in the block editor.

## Quick Reference

**Most common issues:**
1. Focus loss after keyboard navigation (Enter, arrows)
2. Cursor position resets to beginning of text
3. DB/DOM text mismatch (stale closures)
4. Duplicate operations (double-dispatch)

## Browser Guard (Automated Detection)

The browser guard (`dev/browser_guard.js`) provides automated detection of common contenteditable bugs. It's loaded via shadow-cljs preloads during development.

### Automatic Checks

The guard monitors:
- **Focus validation**: Warns when focus leaves contenteditable after navigation
- **Cursor tracking**: Detects cursor resets (jump to position 0)
- **DB/DOM sync**: Compares database text with DOM content
- **Duplicate operations**: Identifies double-dispatch issues

### Console Output

When enabled, the guard logs to console:
```
✅ Focus OK after ArrowDown          # Everything working
🚨 FOCUS BUG: After pressing Enter   # Focus broke
🚨 CURSOR RESET BUG: Jumped to 0     # Cursor position lost
🚨 DB/DOM MISMATCH                   # Text out of sync
```

Visual indicators appear on affected elements (red outline flash).

## Manual Debugging Steps

### 1. Focus Inspection

After a suspected bug, check focus state:

```javascript
// In browser console
const editable = document.querySelector('[contenteditable="true"]');
const focused = document.activeElement;

console.log('Editable:', editable);
console.log('Focused:', focused);
console.log('Match:', editable === focused);
```

### 2. Cursor Position Tracking

Track cursor across operations:

```javascript
function logCursor() {
  const sel = window.getSelection();
  if (!sel.rangeCount) return;

  const range = sel.getRangeAt(0);
  console.log('Cursor:', {
    offset: range.startOffset,
    node: range.startContainer,
    text: range.startContainer.textContent
  });
}

// Run after each operation
logCursor();
```

### 3. DB vs DOM Comparison

Check for text synchronization issues:

```javascript
// Requires DEBUG helpers loaded
const blockId = document.querySelector('[contenteditable]').dataset.blockId;
const dbText = window.DEBUG.getBlockText(blockId);
const domText = document.querySelector('[contenteditable]').textContent;

console.log('DB:', dbText);
console.log('DOM:', domText);
console.log('Match:', dbText === domText);
```

### 4. Event Listener Inspection

Check for duplicate or conflicting handlers:

```javascript
// In browser console
getEventListeners(document.querySelector('[contenteditable="true"]'))
```

Look for multiple handlers on the same event (indicates double-dispatch).

## Common Patterns & Fixes

### Focus Loss After Navigation

**Symptom**: After pressing Enter/arrows, typing doesn't work until clicking.

**Cause**: Re-render without restoring focus.

**Fix**: Use `:replicant/on-render` lifecycle to restore focus:

```clojure
;; In component
{:replicant/on-render
 (fn [node]
   (when (should-restore-focus? node)
     (.focus node)))}
```

### Cursor Position Reset

**Symptom**: Cursor jumps to beginning after typing/navigation.

**Cause**: Text update without cursor restoration, or render during composition.

**Fix**: Guard cursor placement with `__lastAppliedCursorPos` pattern (see `components/block.cljs`).

### DB/DOM Mismatch

**Symptom**: Operations work on stale text values.

**Cause**: Stale closure capturing old DB state.

**Fix**: Always derive values from current DB, never capture in closure:

```clojure
;; ❌ WRONG: Captures text at handler creation time
[:input {:on {:input (fn [e] (update-text old-text))}}]

;; ✅ CORRECT: Reads text at handler invocation time
[:input {:on {:input (fn [e]
                       (let [current-text (get-current-text)]
                         (update-text current-text)))}}]
```

### Double-Dispatch

**Symptom**: Operations execute twice, causing jumps or duplicates.

**Cause**: Event bound in both component and global keymap.

**Fix**: Use single dispatcher rule (see `docs/RENDERING_AND_DISPATCH.md`):
- Editing keys handled ONLY in `components/block.cljs`
- Global keymap must not bind editing context arrows/Enter

## Playwright E2E Debugging

For E2E tests involving cursor/selection:

1. **Take snapshots**: Use accessibility snapshots, not screenshots
2. **Assert both states**: Check DOM selection AND kernel selection
3. **Use keyboard helper**: Never use `page.keyboard.press()` directly—use `pressKeyOnContentEditable()` from `test/e2e/helpers/keyboard.js`

See `docs/PLAYWRIGHT_MCP_TESTING.md` for complete E2E debugging guide.

## Related Documentation

- `docs/RENDERING_AND_DISPATCH.md` - Replicant lifecycle & dispatch rules
- `dev/browser_guard.js` - Automated guard implementation
- `docs/PLAYWRIGHT_MCP_TESTING.md` - E2E testing guide
- `CLAUDE.md` - Keyboard & selection gotchas section
