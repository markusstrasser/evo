---
skill: cljs-ui-debugging
version: 1.0.0
description: Debug ClojureScript UI issues using Playwright MCP, focus/cursor testing, and Replicant lifecycle patterns
triggers:
  - cursor
  - focus
  - contenteditable
  - typing
  - replicant
  - ui bug
  - playwright
---

# ClojureScript UI Debugging Skill

Expert UI debugging for ClojureScript apps using Replicant and Playwright MCP.

## When to Use This Skill

- Cursor resets to position 0 while typing
- Focus doesn't attach after navigation/Enter
- Can't type without clicking first
- Empty blocks behave differently than blocks with text
- Stale text appears in contenteditable elements
- Lifecycle hooks showing unexpected behavior

## Critical Failure Modes

### 1. Focus vs Cursor Confusion ⚠️

**Symptoms Look Similar:**
- Typing doesn't work as expected
- Characters appear in wrong order
- Must click before typing

**But Root Causes Are Different:**
- **Focus broken**: `document.activeElement !== contenteditable`
- **Cursor wrong**: Focus works but `cursorOffset` resets to 0

**Always Check Both:**
```javascript
const editable = document.querySelector('[contenteditable="true"]');
const sel = window.getSelection();
const range = sel.rangeCount ? sel.getRangeAt(0) : null;

{
  isFocused: document.activeElement === editable,  // Focus check
  cursorOffset: range ? range.startOffset : null,   // Cursor position
  canType: document.activeElement === editable      // Can type without click?
}
```

### 2. Playwright Snapshots Hide Critical State ⚠️

**What `browser_snapshot` Shows:**
- DOM structure and text content
- Element attributes and ARIA roles
- Visibility and layout

**What `browser_snapshot` DOESN'T Show:**
- Which element has focus ❌
- Cursor position within text ❌
- Whether typing will work ❌

**Solution: Always Use `browser_evaluate` for Focus/Cursor Bugs**

### 3. Empty Blocks = Different Code Path ⚠️

**The Trap:**
```clojure
(set! (.-textContent node) text)  ; text = ""

;; Later...
(let [text-node (.-firstChild node)]  ; nil for empty!
  (when text-node  ; ← Fails for empty blocks
    (.focus node)))  ; Never called!
```

**Why It Happens:**
- `textContent = ""` creates ZERO child nodes
- `textContent = "foo"` creates a text node (nodeType 3)
- Code that checks `firstChild` fails silently for empty blocks

**Always Test BOTH:**
1. Blocks with text content
2. Completely empty blocks (text = "")

### 4. Stale Closures in Lifecycle Hooks ⚠️

**The Bug:**
```clojure
(let [text (get-in db [:nodes block-id :props :text])]  ; ← Captured
  [:span
   {:replicant/on-render
    (fn [{:replicant/keys [node]}]
      (set! (.-textContent node) text))}])  ; ← Stale on re-render!
```

**Why It Fails:**
- Component function runs ONCE, captures `text` in closure
- Replicant reuses lifecycle function across renders
- Function sees old `text` from original closure

**The Fix:**
```clojure
[:span
 {:replicant/on-render
  (fn [{:replicant/keys [node memory remember]}]
    (when-not memory  ; Only set ONCE
      (set! (.-textContent node) text)  ; Fresh text from THIS render
      (remember true)))}]  ; Never touch again
```

**Key Insight:** Use `:replicant/remember` to set DOM state ONCE with fresh values, then let browser manage it.

## DOM Inspection Snippets

### Quick Focus Check
```javascript
// Paste in browser console
const focused = document.activeElement;
console.log({
  tag: focused.tagName,
  isContentEditable: focused.contentEditable === "true",
  canTypeNow: focused.tagName !== "BODY"
});
```

### Complete Contenteditable State
```javascript
const editables = document.querySelectorAll('[contenteditable="true"]');
Array.from(editables).map(el => ({
  blockId: el.dataset.blockId,
  text: el.textContent,
  isEmpty: el.textContent === "",
  hasTextNode: !!el.firstChild,
  isFocused: el === document.activeElement,
  cursorOffset: (() => {
    const sel = window.getSelection();
    if (el === document.activeElement && sel.rangeCount) {
      return sel.getRangeAt(0).startOffset;
    }
    return null;
  })()
}));
```

### Typing Test (Can Type Without Clicking?)
```javascript
// After navigation/Enter, check if typing works immediately
const editable = document.querySelector('[contenteditable="true"]');
const canTypeImmediately = document.activeElement === editable;

if (!canTypeImmediately) {
  console.error("FOCUS BUG: Must click before typing!");
  console.log("Focus is on:", document.activeElement.tagName);
}
```

## Playwright MCP Testing Patterns

### Pattern: Focus After Navigation
```clojure
;; 1. Trigger navigation
(mcp__playwright__browser_press_key {:key "Enter"})

;; 2. IMMEDIATELY check focus (don't wait)
(mcp__playwright__browser_evaluate
  {:function "() => ({
     focused: document.activeElement.tagName,
     isEditable: document.activeElement.contentEditable === 'true',
     canType: document.activeElement.tagName !== 'BODY'
   })"})

;; 3. Test typing without clicking
(mcp__playwright__browser_press_key {:key "t"})
;; Should work! If nothing happens, focus is broken.
```

### Pattern: Cursor Preservation During Typing
```clojure
;; Type characters one at a time, check cursor moves forward
(mcp__playwright__browser_press_key {:key "a"})
(def cursor-after-a
  (mcp__playwright__browser_evaluate
    {:function "() => window.getSelection().getRangeAt(0).startOffset"}))

(mcp__playwright__browser_press_key {:key "b"})
(def cursor-after-b
  (mcp__playwright__browser_evaluate
    {:function "() => window.getSelection().getRangeAt(0).startOffset"}))

;; Assert: cursor-after-b should be > cursor-after-a
;; If cursor-after-b === 0, cursor is resetting!
```

### Pattern: Empty vs Non-Empty Blocks
```clojure
;; Test 1: Block with text
(click-block "block-with-text")
(press-enter)
(check-focus)  ; Should work

;; Test 2: Empty block
(click-block "empty-block")
(press-enter)
(check-focus)  ; Test THIS path specifically!

;; Common bug: Works for text blocks, fails for empty
```

## Replicant Lifecycle Gotchas

### `:replicant/remember` vs Closure Capture

**Wrong (Stale Closure):**
```clojure
(defn block-component [db block-id]
  (let [text (get-in db [:nodes block-id :props :text])]  ; Captured
    [:span
     {:replicant/on-mount
      (fn [{:replicant/keys [node]}]
        (set! (.-textContent node) text))}]))  ; Stale!
```

**Right (Fresh Per Render):**
```clojure
(defn block-component [db block-id]
  (let [text (get-in db [:nodes block-id :props :text])]  ; Fresh each call
    [:span
     {:replicant/on-render
      (fn [{:replicant/keys [node memory remember]}]
        (when-not memory
          (set! (.-textContent node) text)  ; Fresh text
          (remember true)))}]))  ; Set once, browser owns it
```

### Focus Must Always Happen

**Bug Pattern:**
```clojure
(when (and text-node (= (.-nodeType text-node) 3))
  ;; Cursor positioning
  (.focus node))  ; ← Focus inside conditional, fails for empty blocks!
```

**Fixed:**
```clojure
;; Cursor positioning only if text node exists
(when (and text-node (= (.-nodeType text-node) 3))
  (let [pos ...]
    (.setStart range text-node pos)
    ...))

;; Focus ALWAYS happens (required side effect)
(.focus node)  ; ← Outside conditional
```

### When to Use Each Lifecycle Hook

**`:replicant/on-mount`** - Runs once when DOM node created
- Set up event listeners
- Initialize third-party widgets
- But: May not have fresh `db` for new blocks

**`:replicant/on-render`** - Runs on mount AND every update
- Update DOM based on state changes
- Use with `:replicant/remember` to avoid redundant updates
- Preferred for contenteditable initialization

**`:replicant/on-unmount`** - Cleanup before node removed
- Remove event listeners
- Clear timers
- Release resources

## Common Bug Patterns

### 1. "Cursor jumps to position 0 while typing"
- **Cause:** Setting textContent on every render
- **Fix:** Use `:replicant/remember` to set ONCE

### 2. "Can't type without clicking first"
- **Cause:** `.focus` inside conditional that fails for empty blocks
- **Fix:** Move `.focus` outside text-node checks

### 3. "Empty block shows wrong text"
- **Cause:** Stale closure in lifecycle hook
- **Fix:** Use `:replicant/remember` with fresh `text` from component args

### 4. "Hot reload breaks mid-test"
- **Cause:** shadow-cljs watch mode recompiles during Playwright test
- **Fix:** Test quickly after reload, or pause watch temporarily

### 5. "Typing works in dev, fails in prod build"
- **Cause:** Different optimization/minification breaks assumptions
- **Fix:** Test against `:advanced` compiled build before release

## Diagnostic Workflow

1. **Reproduce the bug** - Click, navigate, type the exact sequence
2. **Check focus immediately** - Is `document.activeElement` correct?
3. **Check cursor position** - Is `startOffset` where you expect?
4. **Inspect DOM nodes** - Does empty block have `firstChild`?
5. **Compare DB vs DOM** - Does `:text` match `textContent`?
6. **Test typing** - Press a key, does it insert correctly?
7. **Check for re-renders** - Is lifecycle hook being called repeatedly?

## Tools Checklist

- [ ] Use `browser_evaluate` for focus/cursor (NOT `browser_snapshot`)
- [ ] Test empty blocks separately from text blocks
- [ ] Check `document.activeElement` immediately after navigation
- [ ] Verify typing works WITHOUT clicking
- [ ] Use `:replicant/remember` for one-time DOM initialization
- [ ] Move `.focus` outside conditionals (always required)
- [ ] Compare DB state vs DOM state for text content
- [ ] Test in both watch mode and production build

## Resources

- `docs/REPLICANT.md` - Lifecycle hooks reference
- `docs/CONTENTEDITABLE_DEBUGGING.md` - Specific failure modes
- `docs/UI_DEBUGGING_GUIDE.md` - Step-by-step debugging workflow
- React docs on contenteditable (same issues apply to Replicant)

## Summary

**Key Principles:**
1. Focus and cursor are SEPARATE concerns - test both
2. Empty blocks hit different code paths - test explicitly
3. Lifecycle hooks capture closures - use `:replicant/remember`
4. Playwright snapshots insufficient - use `browser_evaluate`
5. `.focus` is always required - never conditional
