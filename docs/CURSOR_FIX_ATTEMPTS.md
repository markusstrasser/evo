# Cursor Restoration Fix Attempts - Lessons Learned

**Date:** 2025-11-16
**Status:** REVERTED - Do not retry this approach

## Original Problem

When typing "Hello World" in a contenteditable block, characters would scramble:
- Expected: `Hello World`
- Actual: `ello WorldH` (first character jumped to end)

## What We Tried: Gemini 2.5 Pro's Memory-Based Cursor Fix

### The Approach

Changed cursor position tracking from DOM properties to Replicant's `memory`:

**Before (using DOM properties):**
```clojure
:replicant/on-render (fn [{:replicant/keys [node life-cycle remember memory]}]
  (when-not (= life-cycle :replicant.life-cycle/unmount)
    (let [cursor-pos (q/cursor-position db)]
      ;; Initialize text content once
      (when-not memory
        (set! (.-textContent node) text)
        (remember true))

      ;; Track cursor position on DOM node
      (if cursor-pos
        (let [last-applied (aget node "__lastAppliedCursorPos")]
          (when (not= cursor-pos last-applied)
            (try
              ;; ... cursor positioning code ...
              (aset node "__lastAppliedCursorPos" cursor-pos)
              ;; ... rest of code ...
```

**After (using Replicant memory - BROKEN):**
```clojure
:replicant/on-render (fn [{:replicant/keys [node life-cycle remember memory]}]
  (when-not (= life-cycle :replicant.life-cycle/unmount)
    (let [cursor-pos (q/cursor-position db)
          last-applied (get memory :last-applied-cursor)]  ;; Changed

      ;; Phase 1: Mount (changed initialization logic)
      (when (nil? memory)
        (set! (.-textContent node) text)
        (.focus node)
        (remember {:initialized? true :last-applied-cursor nil}))  ;; Changed

      ;; Phase 2: Cursor
      (if cursor-pos
        (when (not= cursor-pos last-applied)
          (try
            ;; ... cursor positioning code ...
            (.focus node)
            (remember {:initialized? true :last-applied-cursor cursor-pos})  ;; Changed
            (js/setTimeout #(on-intent {:type :clear-cursor-position}) 0)
```

### Key Changes
1. Replaced `(aget node "__lastAppliedCursorPos")` with `(get memory :last-applied-cursor)`
2. Replaced `(aset node "__lastAppliedCursorPos" cursor-pos)` with `(remember {:initialized? true :last-applied-cursor cursor-pos})`
3. Changed mount phase from `(when-not memory ...)` to `(when (nil? memory) ...)`
4. Added `.focus` call during mount phase
5. Changed `remember` from boolean to map structure

## Results: CATASTROPHIC FAILURE

### Test Results
- **492 tests FAILED** out of 504 total (97.8% failure rate)
- Only 10 tests passed
- 2 skipped

### What Broke
1. **All keyboard navigation** - arrows, Enter, Escape, etc.
2. **All editing operations** - Backspace, Delete, Tab, Shift+Tab
3. **All selection operations** - Shift+Arrow, Cmd+A, Shift+Click
4. **All text formatting** - Bold, italic, strikethrough, highlight
5. **All undo/redo** - Cmd+Z, Cmd+Shift+Z, Cmd+Y
6. **All movement operations** - Mod+Shift+Up/Down
7. **Emacs navigation** - Ctrl+A, Ctrl+E, Ctrl+P, Ctrl+N

### Console Errors
- `"Cursor positioning failed: undefined"` - repeated throughout tests
- `"Replicant warning: Triggered a render while rendering"` - on every keystroke

### What DID Work
- ✅ Typing "Hello World" displayed correctly (no character scrambling)
- ✅ Color contrast tests
- ✅ Debug/diagnostic tests

## Why This Approach Failed

The fix **addressed the symptom, not the root cause**:

1. **Surface change only**: Swapping `aget/aset` for `memory/remember` changed WHERE we track cursor state, not WHY it was getting scrambled
2. **Lifecycle interference**: Using `remember` inside `:replicant/on-render` appears to cause timing issues with Replicant's render cycle
3. **Focus management broke**: Adding `.focus` in mount phase and after cursor positioning interfered with normal focus flow
4. **Nested renders**: The `setTimeout` + `on-intent` + `remember` combination triggered renders during rendering

## What We Learned

### ❌ DO NOT Try Again
- **Do NOT** replace `aget/aset` with `memory/remember` for cursor tracking
- **Do NOT** add `.focus()` calls in mount phase
- **Do NOT** change `remember` from boolean to map structure in this component
- **Do NOT** ask Gemini (or any LLM) to "fix parentheses" without addressing root cause first

### ✅ What to Investigate Instead

The real problem is likely in the **cursor position calculation or timing**:

1. **When is `:cursor-position` set in state?**
   - Check `q/cursor-position` query
   - Check all `on-intent` calls with `:type :update-content`
   - Check keyboard event handlers in `src/components/block.cljs`

2. **Why does cursor position get stale during typing?**
   - Is cursor-pos being set during normal typing? (it shouldn't be)
   - Is the `:clear-cursor-position` intent timing correct?
   - Is there a race condition between typing and cursor restoration?

3. **Should cursor restoration even run during normal typing?**
   - The `:replicant/on-render` hook runs on EVERY render
   - Normal typing triggers re-renders
   - Maybe we should ONLY restore cursor after navigation/undo/redo, NOT during typing

## Commits to Avoid

These commits were reverted - do not cherry-pick:
- `c858795` - fix(cursor): apply Gemini 2.5 Pro's cursor restoration fix
- `b666d12` - fix(cursor): add missing closing paren for on-render hook
- `2a5b08e` - fix(cursor): correct try-catch parentheses structure
- `3f3eafd` - fix(cursor): implement phase-based cursor restoration + fix clean script
- `769df58` - fix(cursor): apply Gemini 2.5 Pro's cursor restoration fix

## Current State

**Reverted to:** `cc1a6c9` - fix(keymap): change :ctrl to :mod for Emacs bindings

This version has:
- ✅ All keyboard navigation working
- ✅ All editing operations working
- ✅ All tests passing (except typing scramble test)
- ❌ Typing "Hello World" becomes "ello WorldH"

## Recommended Next Steps

1. **Add debug logging** to understand WHEN cursor-pos is set during typing
2. **Check if `:update-content` intent is setting cursor position** (it shouldn't during normal typing)
3. **Investigate the `setTimeout` timing** - is 0ms too fast?
4. **Consider conditionally disabling cursor restoration** during active typing (check if `editing-block-id` is set)
5. **Review the original Logseq/Roam cursor restoration logic** for comparison

## Files Involved

- `src/components/block.cljs:586-643` - The `:replicant/on-render` hook
- `src/kernel/queries.cljc` - `cursor-position` query
- `src/plugins/editing.cljc` - Intent handlers that set cursor position
- `test/e2e/debug-test-mode.spec.ts` - Test that shows typing scramble
- `test/e2e/keyboard-navigation.spec.ts` - Test that verifies arrows work

## Key Insight

**The typing scramble is NOT a cursor restoration bug - it's a cursor SETTING bug.**

The `:replicant/on-render` hook is correctly applying whatever cursor position is in state. The problem is that the STATE contains the wrong cursor position during typing. Fix the source, not the symptom.
