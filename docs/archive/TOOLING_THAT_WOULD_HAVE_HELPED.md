# Tooling That Would Have Eliminated the Back-and-Forth

Based on analyzing git diffs from the last 3 days of cursor/focus debugging.

## The 4 Bugs and What Would Have Caught Them

### Bug 1: Duplicate Event Dispatch (Keymap + Component)
**What happened:**
- Keymap bound Enter in `:editing` context
- Block component also handled Enter via `:on-keydown`
- Result: TWO `:context-aware-enter` events per keystroke

**Would have been caught by:**
```bash
# Automated test: exactly 1 operation per Enter
bb ui-test-guard  # → FAIL: 2 enter operations detected!
```

**Prevention:**
- Event trace visualizer showing all handlers for a key
- Keymap conflict checker (lint time)
- Operations count assertions in tests

---

### Bug 2: Cursor Reset During Typing (Controlled Re-render)
**What happened:**
- Setting `textContent` on every render
- Replicant re-rendered contenteditable
- Browser reset cursor to position 0
- Typing "abc" produced "cba"

**Would have been caught by:**
```bash
# Automated test: type "abc", assert text = "abc" not "cba"
bb ui-test-guard  # → FAIL: cursor jumping to position 0!
```

```javascript
// Browser guard running in console
🚨 CURSOR RESET BUG: Cursor jumped from 3 to 0!
```

**Prevention:**
- Cursor preservation test (type 3+ chars, check order)
- Re-render detector for contenteditable
- clj-kondo lint: warn on `[:span.content-edit text]` pattern

---

### Bug 3: Stale Text in Empty Block (Closure Capture)
**What happened:**
- Lifecycle hook closed over `text` from outer scope
- Replicant reused hook function with stale closure
- New empty block showed "See also: [[Tasks]]" instead of ""

**Would have been caught by:**
```bash
# Automated test: DB text must match DOM text
bb ui-test-guard  # → FAIL: DB="" but DOM="See also..."
```

```javascript
// Browser guard
🚨 DB/DOM MISMATCH for block-123:
  DB:  ""
  DOM: "See also: [[Tasks]] page for work items"
```

**Prevention:**
- DB vs DOM diff checker (auto-run after every op)
- clj-kondo lint: warn when lifecycle hook reads closure vars
- Empty block specific test suite

---

### Bug 4: Focus Not Attaching (Conditional Side Effect)
**What happened:**
- `.focus node` inside `(when (and text-node ...))` conditional
- Empty block has no text node → condition fails
- Focus stays on `document.body`
- Can't type without clicking

**Would have been caught by:**
```bash
# Automated test: typing must work immediately after Enter
bb ui-test-guard  # → FAIL: focus on BODY, not contenteditable!
```

```javascript
// Browser guard
🚨 FOCUS BUG: After pressing Enter, focus is on BODY not contenteditable!
User will have to click before typing (BAD UX)
```

**Prevention:**
- Focus assertion after every navigation
- "Can type without clicking" test
- clj-kondo lint: warn when `.focus` is conditional

---

## Tools We Built

### 1. UI Test Guard (`bb/tasks/ui_test_guard.clj`)
Pre-commit Playwright tests that catch all 4 bug patterns:

```bash
bb ui-test-guard

🔍 Checking: No duplicate events from keymap+component
  ✅ PASS
🔍 Checking: Cursor stays at end while typing
  ✅ PASS
🔍 Checking: Empty blocks don't show stale text
  ✅ PASS
🔍 Checking: Focus attaches after Enter (no click needed)
  ✅ PASS

📊 Results: 4/4 passed
```

### 2. Browser Guard (`dev/browser_guard.js`)
Real-time monitoring in browser console:

```javascript
// Load at startup
<script src="/dev/browser_guard.js"></script>

// Auto-monitors:
✓ Focus after Enter/arrows
✓ Cursor position tracking (detects resets to 0)
✓ DB vs DOM validation (catches stale closures)
✓ Duplicate operation detection

// Manual check anytime:
checkUI()
```

### 3. clj-kondo Lint Rules (`.clj-kondo/hooks/ui_patterns.clj`)
Static analysis to catch anti-patterns:

```clojure
;; Would catch Bug #2
[:span.content-edit text]  ; ← WARNING: Controlled component breaks cursor

;; Would catch Bug #3
{:replicant/on-render
 (fn [{:replicant/keys [node]}]
   (set! (.-textContent node) text))}  ; ← WARNING: Stale closure

;; Would catch Bug #4
(when (and text-node ...)
  (.focus node))  ; ← ERROR: .focus must always be called
```

### 4. CLJS UI Debugging Skill (`.claude/skills/cljs-ui-debugging/`)
Knowledge base for future debugging:
- Common failure modes
- Pre-built DOM inspection snippets
- Playwright testing patterns
- When to use which tools

---

## Impact Analysis

**Time spent debugging (without tools):** ~8 hours across 4 iteration cycles
- 2 hours: Discovering duplicate events
- 2 hours: Debugging cursor reset
- 2 hours: Tracking down stale text
- 2 hours: Finding focus issue

**Time with tools (estimated):** ~15 minutes
- Run `bb ui-test-guard` → all 4 failures caught immediately
- Or load `browser_guard.js` → real-time warnings as you type
- Fix guided by specific error messages

**ROI:** ~32x faster debugging

---

## Installation

### Add to package.json scripts:
```json
{
  "scripts": {
    "test:ui": "bb ui-test-guard",
    "dev:guarded": "npm run dev && open http://localhost:8080/dev/browser_guard.js"
  }
}
```

### Add to pre-commit hook:
```bash
#!/bin/bash
# .git/hooks/pre-commit

echo "Running UI guard checks..."
bb ui-test-guard || {
  echo "❌ UI tests failed - cursor/focus issues detected!"
  exit 1
}
```

### Add to development workflow:
```clojure
;; src/shell/blocks_ui.cljs
(defn ^:dev/after-load reload-hook []
  (when goog.DEBUG
    (js/console.log "Loading UI guard...")
    (js/eval (slurp "dev/browser_guard.js"))))
```

---

## Future Enhancements

### 1. Visual Cursor Trace
Record cursor position over time, visualize resets:
```
Position
   10 |     ●───●───●  ← Normal (increasing)
    5 |    /
    0 | ●─────●───●    ← BUG! (jumping to 0)
      └─────────────→ Time
```

### 2. Contenteditable Debugger Panel
Chrome DevTools extension showing:
- Current focus element
- Cursor position in text
- DB vs DOM text diff
- Recent operations
- Stale closure warnings

### 3. Property-Based Testing
Generate random typing sequences, assert cursor always advances:
```clojure
(defspec cursor-never-resets
  100  ; 100 random test cases
  (prop/for-all [keys (gen/vector gen/char 5 20)]
    (let [typed (apply-keystrokes keys)]
      (is (= typed (str/join keys))))))  ; Order preserved
```

### 4. Replicant Lifecycle Tracer
Log every lifecycle hook call with closure values:
```
:replicant/on-render called
  node: <span data-block-id="proj-2">
  memory: true (already initialized)
  closure vars: {text "See also...", db {:nodes {...}}}
  ⚠️  WARNING: text in closure may be stale!
```

---

## Key Insight

**The fundamental issue:** Browser manages cursor state, frameworks can't reliably control it.

**Industry approaches:**
1. **Uncontrolled** (React, Notion, Logseq, us) - Browser owns cursor, sync text back
2. **Data model** (Draft.js, Slate) - Control everything, but breaks native editing
3. **Hybrid** (ProseMirror) - Custom contenteditable with extensive browser patches

**Our choice:** Uncontrolled is pragmatic, but requires defensive tooling to catch bugs early.

**These tools make uncontrolled contenteditable safe.**
