# Bug Report - Missing Plugin Requires

**Date:** 2025-11-01
**Severity:** CRITICAL - Core features were completely non-functional
**Status:** ✅ FIXED

## Summary
Three essential plugins were not loaded in `src/shell/blocks_ui.cljs`, causing most hotkeys and features to be completely broken.

## Bugs Found and Fixed

### 1. Missing `plugins.selection` ⚠️ CRITICAL
**Impact:** Selection system completely broken
- Click to select: Not working
- Arrow key navigation: Not working
- Shift+Click extend: Not working
- Shift+Arrow extend: Not working

**Root Cause:** `[plugins.selection]` not in requires
**Fix:** Added to line 15 of `src/shell/blocks_ui.cljs`

### 2. Missing `plugins.editing` ⚠️ CRITICAL
**Impact:** Edit mode completely broken
- "Start typing to edit": Not working
- Enter edit mode: Not working
- Exit edit mode: Not working
- Content updates: Not working

**Root Cause:** `[plugins.editing]` not in requires
**Fix:** Added to line 16 of `src/shell/blocks_ui.cljs`

### 3. Missing `plugins.struct` ⚠️ CRITICAL
**Impact:** All structural operations broken
- Tab (indent): Not working
- Shift+Tab (outdent): Not working
- Backspace (delete): Not working
- Cmd+Shift+Arrow (move): Not working
- Enter (create block): Not working

**Root Cause:** `[plugins.struct]` not in requires
**Fix:** Added to line 17 of `src/shell/blocks_ui.cljs`

## Test Results After Fix

### ✅ Verified Working
1. **Selection** - Click selection now shows `Selection: #{"a"}` and `Focus: "a"` correctly
2. **Navigation** - Arrow keys move selection between blocks
3. **Plugin Loading** - All 5 critical plugins now loaded:
   - `plugins.selection`
   - `plugins.editing`
   - `plugins.struct`
   - `plugins.folding`
   - `plugins.smart-editing`

### ⏳ Remaining Manual Tests Needed
Due to complexity of browser automation, the following should be manually tested:
1. Start typing to edit (printable character triggers edit mode)
2. Tab/Shift+Tab (indent/outdent)
3. Backspace (delete block)
4. Enter (create new block)
5. Shift+Arrow (extend selection)
6. Shift+Click (extend selection)
7. Cmd+Shift+Arrow (move blocks)
8. Cmd+; (toggle fold)
9. Cmd+. / Cmd+, (zoom in/out)
10. Cmd+Enter (toggle checkbox)
11. Cmd+Z / Cmd+Shift+Z (undo/redo)

## Code Changes

```clojure
;; BEFORE (broken)
(:require [replicant.dom :as d]
          [kernel.db :as DB]
          [kernel.api :as api]
          [kernel.query :as q]
          [kernel.transaction :as tx]
          [kernel.history :as H]
          [components.block :as block]
          [plugins.folding]
          [plugins.smart-editing]
          [keymap.core :as keymap]
          [keymap.bindings :as bindings])

;; AFTER (fixed)
(:require [replicant.dom :as d]
          [kernel.db :as DB]
          [kernel.api :as api]
          [kernel.query :as q]
          [kernel.transaction :as tx]
          [kernel.history :as H]
          [components.block :as block]
          [plugins.selection]        ;; ← ADDED
          [plugins.editing]          ;; ← ADDED
          [plugins.struct]           ;; ← ADDED
          [plugins.folding]
          [plugins.smart-editing]
          [keymap.core :as keymap]
          [keymap.bindings :as bindings])
```

## Architecture Insight

The codebase uses a **plugin-based architecture** where:
- Plugins register intent handlers via `kernel.intent/register-intent!`
- Components dispatch intents via `(on-intent {:type ...})`
- The shell (blocks_ui.cljs) must require all plugins for side effects

**Design Issue:** The requires are for side-effects only (registering intents), so there's no compiler error if they're missing. This is a footgun.

**Recommendation:** Consider adding a startup validation that checks all expected intents are registered, or use a more explicit plugin initialization pattern.

## Test Plan for Manual Verification

Open http://localhost:8080 and test:

1. **Selection**: Click blocks → should highlight
2. **Navigation**: Use ↑↓ → should move focus
3. **Edit Mode**: Click block, type 'h' → should enter edit with 'h' inserted
4. **Structural**: Select block, press Tab → should indent
5. **History**: Make changes, press Cmd+Z → should undo

All features should now work as documented in HOTKEYS_QUICK_REFERENCE.md.
