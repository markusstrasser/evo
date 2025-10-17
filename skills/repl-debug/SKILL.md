---
name: REPL-First Debugging
description: REPL-first debugging workflow for ClojureScript. Test hypotheses in REPL before editing code (30s vs 5+ min per bug). Includes browser console helpers (DEBUG.summary, DEBUG.events, DEBUG.inspectEvents). Triggers on debug, repl, troubleshoot, browser console, fast debugging. No network required.
---

# REPL-First Debugging

## Overview

This skill packages the REPL-first debugging philosophy: **test hypotheses in REPL before making code changes**. Fast iteration beats educated guessing.

## Core Philosophy

**❌ Slow Way (Don't Do This):**
1. Make educated guess
2. Edit code
3. Wait for compile
4. Reload browser
5. Check console
6. Repeat... (5+ iterations)

**✅ Fast Way (REPL-First):**
1. **Reproduce in REPL/console first** - Verify the problem
2. **Test hypothesis in REPL** - Try fixes interactively
3. **Only then update code** - Apply the working fix
4. **Verify with browser** - Final integration test

**Time savings: 30 seconds vs 5+ minutes**

## When to Use

Use this skill when:
- Debugging behavior issues
- Testing theories about code
- Exploring state
- Understanding async operations
- Checking if new code loaded
- Inspecting event sourcing state

## Browser Console Helpers

### DEBUG Namespace (Auto-loaded in Dev Mode)

**State Overview:**
```javascript
DEBUG.summary()           // Cards, events, stacks overview
DEBUG.events()            // All events
DEBUG.activeEvents()      // Only active events
DEBUG.undoneEvents()      // Only undone events
DEBUG.cards()             // All cards
DEBUG.dueCards()          // Cards due now
```

**Event Status:**
```javascript
DEBUG.inspectEvents()     // Recent events with ✅/❌ status
core.build_event_status_map(DEBUG.events())  // Build status map
```

**Stacks:**
```javascript
DEBUG.undoStack()         // Current undo stack
DEBUG.redoStack()         // Current redo stack
```

**Utilities:**
```javascript
DEBUG.reload()            // Hard reload page (clears cache)
```

### Quick Checks

**Verify code loaded:**
```javascript
() => lab.anki.fs.load_all_md_files.toString().includes("Processing entry")
```

**Inspect current state:**
```javascript
() => ({
  cards: cljs.core.count(cljs.core.get(state, cljs.core.keyword("cards"))),
  events: DEBUG.events().length,
  undoStack: DEBUG.undoStack().length
})
```

**Test theory:**
```javascript
// How many events?
DEBUG.events().length

// How many active?
DEBUG.activeEvents().length

// Check status map
core.build_event_status_map(DEBUG.events())
```

## REPL Debugging Patterns

### Pattern 1: Reproduce First

```clojure
;; ❌ Slow: Edit code → compile → reload → check
;; ✅ Fast: Reproduce in REPL first

;; 1. Reproduce the issue
(def test-data {...})
(my-function test-data)  ;; Observe the bug

;; 2. Test fix interactively
(defn my-function-v2 [data]
  ;; Try different approaches
  ...)

(my-function-v2 test-data)  ;; Verify fix works

;; 3. Only then update code
;; Now you know the fix works!
```

### Pattern 2: Async Iteration Debugging

**Problem: `Array.from(asyncIterator)` returns empty**

```clojure
;; Reproduce in REPL
(def handle (js/showDirectoryPicker))  ; User selects directory
(def values (.values handle))
(js/Array.from values)  ;; => [] - Empty! Why?

;; Test hypothesis
(.next values)  ;; => Promise! - AHA! It's async!

;; Solution found in 30 seconds via REPL
;; Now fix code:
(for await [entry (.values handle)]
  (process entry))
```

### Pattern 3: State Exploration

```clojure
;; Explore current state
@(re-frame.core/subscribe [:current-view])
@(re-frame.core/subscribe [:db])

;; Test state transition
(re-frame.core/dispatch [:event-name args])
;; Check result immediately
@(re-frame.core/subscribe [:result])

;; Try different approaches
(re-frame.core/dispatch [:alternative-event])
```

### Pattern 4: Function Behavior Testing

```clojure
;; Test with real data
(def real-events (get @app-db :events))
(filter active? real-events)  ;; Check filter works

;; Test edge cases
(filter active? [])  ;; Empty
(filter active? nil) ;; Nil
(filter active? [{:undone? true}])  ;; Undone event
```

## Common Pitfalls & Solutions

### 1. Browser Cache (Stale Code)

**Symptom:** Changes not appearing

**Debug:**
```javascript
// Check for loading message
console.log("Page loaded")

// Verify dev/debug.cljs loaded
typeof DEBUG !== 'undefined'

// Hard reload
DEBUG.reload()
```

**Solution:** Use `DEBUG.reload()` for hard refresh

### 2. Async Iteration

**Symptom:** `Array.from(asyncIterator)` returns `[]`

**Why:** Async iterators need `for await` or promises

**Solution:**
```javascript
// ❌ Wrong
Array.from(asyncIterator)  // Returns []

// ✅ Correct
for await (const item of asyncIterator) {
  // process item
}
```

### 3. Event Sourcing Status

**Symptom:** Events not appearing in UI

**Debug:**
```javascript
// Check event status
DEBUG.inspectEvents()  // Shows ✅/❌ status

// Verify active events
DEBUG.activeEvents().length

// Check if undone
DEBUG.undoneEvents()
```

**Solution:** Use `DEBUG.inspectEvents()` to see active/undone status

### 4. Stale Code Check

**Symptom:** Code changes not taking effect

**Debug:**
```javascript
// Check function source
myFunction.toString()

// Look for your changes
myFunction.toString().includes("my new code")
```

**Solution:** Check console for compilation errors or reload

## Testing Workflow

### Quick Test Script

```bash
# Test specific namespace
scripts/quick-test.sh lab.anki.core-test

# All tests
scripts/quick-test.sh
```

### REPL Test Pattern

```clojure
;; 1. Load namespace
(require '[lab.anki.core :as core] :reload)

;; 2. Test function
(core/my-function test-input)

;; 3. Check result
;; => expected output

;; 4. If broken, debug in REPL
(def result (core/my-function test-input))
(type result)
(keys result)
;; etc.
```

## Fast Debugging Loop

### Recommended Process

**For each bug:**

1. **Reproduce** (REPL/Console)
   - Load function
   - Call with real data
   - Observe behavior

2. **Hypothesis** (REPL)
   - Test theory interactively
   - Try different approaches
   - Verify fix works

3. **Apply** (Code)
   - Update code with working fix
   - Compile once
   - Done!

4. **Verify** (Browser/Tests)
   - Final integration check
   - Run tests if needed

**Time per cycle:**
- REPL-first: ~30 seconds
- Code-first: 5+ minutes

## Example: Debugging Array.from Bug

**Full workflow:**

```clojure
;; 1. Reproduce (30 seconds in REPL)
(def handle (js/showDirectoryPicker))  ; User selects
(def values (.values handle))
(js/Array.from values)  ;; => [] - AHA! Empty!
(.next values)          ;; => Promise! - It's async!

;; 2. Test fix
;; Use for-await or promises
;; (test in browser console first)

;; 3. Apply fix to code (once)
;; Update load_all_md_files function

;; 4. Verify
;; Reload, test - done!

;; Total time: <1 minute
;; vs editing code 5+ times: 5+ minutes
```

## Browser State Inspection

### Check If Code Loaded

```javascript
// Method 1: Look for specific string
() => myFunction.toString().includes("my change")

// Method 2: Check module loaded
typeof my.namespace !== 'undefined'

// Method 3: Version check
() => MY_APP_VERSION  // If you have version constant
```

### Inspect Component State

```javascript
// React/Replicant components
() => {
  const component = document.querySelector('.my-component');
  return component.__data;  // Access component data
}

// Check props
() => {
  const el = document.querySelector('[data-id="123"]');
  return {
    dataset: el.dataset,
    innerHTML: el.innerHTML,
    classes: el.className
  };
}
```

### Monitor State Changes

```javascript
// Watch state changes
setInterval(() => {
  console.log('State:', DEBUG.summary());
}, 1000);

// Watch specific value
setInterval(() => {
  console.log('Events:', DEBUG.events().length);
}, 500);
```

## Common Debug Scenarios

### Scenario 1: "Function not working"

```clojure
;; 1. Check it exists
(resolve 'my.namespace/my-function)  ;; => #'my.namespace/my-function

;; 2. Check signature
(doc my.namespace/my-function)

;; 3. Test with simple input
(my.namespace/my-function {:simple "input"})

;; 4. Test with real data
(def real-data (get @app-db :data))
(my.namespace/my-function real-data)

;; 5. Inspect result
(def result (my.namespace/my-function real-data))
(type result)
(count result)  ; If collection
```

### Scenario 2: "State not updating"

```javascript
// Check event fired
DEBUG.events().filter(e => e.type === 'my-event')

// Check event processed
DEBUG.activeEvents().find(e => e.type === 'my-event')

// Check state changed
const before = {...state};
// Trigger event
const after = {...state};
console.log('Diff:', diff(before, after));
```

### Scenario 3: "UI not reflecting state"

```javascript
// Check state has data
DEBUG.cards().length  // Should be > 0

// Check derived data
DEBUG.dueCards().length  // Should match expected

// Check React/rendering
() => {
  const el = document.querySelector('.card');
  return {
    exists: !!el,
    content: el?.textContent,
    classes: el?.className
  };
}
```

## Tips & Tricks

1. **Use DEBUG helpers in browser console**
   - Faster than adding console.log statements
   - Inspect state without code changes

2. **Test one thing at a time**
   - Isolate the problem
   - Test hypothesis individually

3. **Keep REPL open**
   - Faster than compile-reload cycle
   - Interactive experimentation

4. **Use `:reload` with require**
   - `(require '[ns] :reload)` - Get latest code
   - Without `:reload` = stale code

5. **Check console for hints**
   - Look for "🔧 Loading debug helpers..."
   - Check for compilation errors
   - Look for shadow-cljs warnings

## Configuration

### Browser Helpers

The DEBUG namespace is automatically loaded in dev mode via `dev/debug.cljs`. It provides:
- State overview functions (summary, events, cards)
- Event status inspection (inspectEvents, activeEvents)
- Stack inspection (undoStack, redoStack)
- Utilities (reload)

### Debugging Patterns

| Pattern | Description | Time Saved |
|---------|-------------|------------|
| Reproduce First | Test in REPL before editing | 10x faster |
| Async Iteration | Check for Promise returns | Immediate fix |
| State Exploration | Use subscriptions to inspect | Real-time insight |
| Function Testing | Test with real data first | Avoid wrong guesses |

### Common Pitfalls

| Pitfall | Symptom | Solution |
|---------|---------|----------|
| Browser Cache | Changes not appearing | DEBUG.reload() |
| Async Iteration | Array.from returns [] | Use for-await |
| Event Sourcing | Events missing in UI | DEBUG.inspectEvents() |
| Stale Code | Old code still running | Check .toString() |

## Workflow Summary

| Step | Action | Time |
|------|--------|------|
| 1. Reproduce | Test in REPL/console | 10-30s |
| 2. Hypothesis | Try fixes interactively | 10-30s |
| 3. Apply | Update code once | 10s |
| 4. Verify | Final integration test | 10s |
| **Total** | **REPL-first approach** | **~1 min** |
| vs Code-first | Edit → compile → reload × 5+ | **5+ min** |

## Resources (Level 3)

- `run.sh` - Show debugging guides, patterns, browser helpers
- `dev/debug.cljs` - Browser console DEBUG helpers
- `scripts/quick-test.sh` - Fast test runner
- `dev/repl/session.clj` - REPL session helpers

## See Also

- Project docs: `../../CLAUDE.md#debugging-workflow`
- Dev helpers: `../../dev/debug.cljs`
- Quick test: `../../scripts/quick-test.sh`
- REPL session: `../../dev/repl/session.clj`
