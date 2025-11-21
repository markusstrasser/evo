# Replicant `:key` vs `:replicant/key` Fix

## Problem Summary

After refactoring to controlled contenteditable with MutationObserver pattern, keyboard navigation broke completely. Enter key wouldn't create new blocks, arrow keys didn't work, and the element never received focus.

## Root Cause

**Using `:key` instead of `:replicant/key` in component attributes.**

```clojure
;; ❌ WRONG - Replicant doesn't recognize this!
[:span.content-edit {:key (str block-id "-edit") ...}]
[:span.content-view {:key (str block-id "-view") ...}]

;; ✅ CORRECT - Replicant uses this for element identity
[:span.content-edit {:replicant/key (str block-id "-edit") ...}]
[:span.content-view {:replicant/key (str block-id "-view") ...}]
```

### Why This Matters

From Replicant source (`replicant/src/replicant/core.cljc`):

```clojure
(defn reusable?
  "Two elements are considered similar enough for reuse if they are both hiccup
  elements with the same tag name and the same key (or both have no key)"
  [headers vdom]
  (and (= (hiccup/rkey headers) (vdom/rkey vdom))
       (= (hiccup/tag-name headers) (vdom/tag-name vdom))))
```

**When keys are wrong:**
- `:key` is NOT checked by `rkey` function
- Both edit/view spans have `nil` keys
- Same tag name (`span`) + same key (`nil`) = **REUSABLE**
- Replicant **reuses** the DOM element when transitioning edit ↔ view
- Lifecycle phase = `:update` not `:mount`
- `:replicant/on-mount` never fires
- MutationObserver never set up
- Element never focused
- Keyboard events don't work

**When keys are correct:**
- `:replicant/key` IS checked by `rkey` function
- Edit span has key `"block-id-edit"`, view span has key `"block-id-view"`
- Different keys = **NOT REUSABLE**
- Replicant **creates new element** when transitioning edit ↔ view
- Lifecycle phase = `:mount` (confirmed in console: `"life-cycle":"mount"`)
- `:replicant/on-mount` fires properly
- MutationObserver set up
- Element focused
- Keyboard events work ✅

## Fix Applied

**File**: `src/components/block.cljs`

### Change 1: Edit Mode Key (line ~593)

```clojure
;; Before
[:span.content-edit
 {:key (str block-id "-edit")  ; ❌ WRONG
  ...}]

;; After
[:span.content-edit
 {:replicant/key (str block-id "-edit")  ; ✅ CORRECT
  ...}]
```

### Change 2: View Mode Key (line ~720)

```clojure
;; Before
[:span.content-view
 {:key (str block-id "-view")  ; ❌ WRONG
  ...}]

;; After
[:span.content-view
 {:replicant/key (str block-id "-view")  ; ✅ CORRECT
  ...}]
```

### Change 3: Simplified Lifecycle Hooks

With proper `:replicant/key`, we can now rely on `:on-mount` firing correctly:

**Edit Mode Lifecycle**:
- `:replicant/on-mount` - Setup MutationObserver + focus element (runs ONCE)
- `:replicant/on-render` - Update DOM to reflect DB state (runs on every render)
- `:replicant/on-unmount` - Disconnect observer

```clojure
;; On mount: Setup observer + focus
:replicant/on-mount
(fn [{:replicant/keys [node]}]
  (let [cleanup! (editable/setup-controlled-editable! ...)]
    (aset node "__editable-cleanup" cleanup!)
    (.focus node)))  ; Focus element so keyboard works

;; On render: Just update DOM (no setup, no focus)
:replicant/on-render
(fn [{:replicant/keys [node life-cycle]}]
  (when (and (not= life-cycle :replicant.life-cycle/unmount)
             (aget node "__editable-cleanup"))
    (editable/update-controlled-editable! node text cursor-map)))

;; On unmount: Cleanup
:replicant/on-unmount
(fn [{:replicant/keys [node]}]
  (when-let [cleanup! (aget node "__editable-cleanup")]
    (cleanup!)))
```

**Key insight**: Before the fix, we had redundant setup logic in `:on-render` with guards like `(when-not (aget node "__editable-cleanup"))` because we couldn't trust `:on-mount` to fire. Now we can trust `:on-mount`, so `:on-render` only needs to update the DOM.

## Verification

### Console Logs Confirm Fix

**Before** (with `:key`):
```
[on-render] called {"block-id":"proj-1-1","life-cycle":"update"}  ❌
```

**After** (with `:replicant/key`):
```
[on-mount] MutationObserver setup complete + focused {"block-id":"proj-1-1"}  ✅
```

### Manual Testing

1. Click block → Press Enter → Contenteditable appears and is focused ✅
2. Lifecycle logs show `:mount` not `:update` ✅
3. MutationObserver set up correctly ✅
4. Typing works (observer fires `onChange`) ✅
5. No more "nested render" warnings from observer setup ✅

## Remaining Issues

### Issue 1: `:exit-edit` Fires After `smart-split`

**Status**: NOT FIXED (separate bug, not related to `:replicant/key`)

**Symptom**:
- Press Enter in edit mode
- New block created ✅
- But then `:exit-edit` intent fires
- Exits edit mode instead of staying in edit mode on new block ❌

**Console sequence**:
```
keydown event! {key: "Enter"}
handle-enter called!
Dispatching smart-split
Intent: {:type :exit-edit}  ← WHY?
```

**Analysis**: This is a logic bug in the intent handlers, not a lifecycle issue. The `smart-split` intent successfully creates a new block, but something triggers `:exit-edit` afterward. Likely caused by:
1. Blur event firing during block creation?
2. View mode `:on-render` hook triggering render during transition?
3. Missing `:enter-edit` intent after `smart-split`?

**Next steps**: Investigate intent handler logic, not Replicant lifecycle.

### Issue 2: Nested Render Warning (View Mode)

**Status**: PARTIALLY FIXED (edit mode clean, view mode still warns)

**Symptom**:
```
Replicant warning: Triggered a render while rendering
Offending hiccup: [:span.content-view ...]
```

**Analysis**: View mode's `:replicant/on-render` hook sets `textContent` on every render:

```clojure
:replicant/on-render (fn [{:replicant/keys [node]}]
                       (set! (.-textContent node) (or text "")))
```

This DOM mutation during render triggers Replicant's nested render warning. However, this is intentional for uncontrolled view mode and doesn't break functionality.

**Resolution**: Low priority. Replicant docs say "Nested renders... isn't categorically wrong, but should be used with care." Since view mode doesn't have interactive behavior, this is acceptable.

## Lessons Learned

### 1. Always Use `:replicant/key` for Conditional Elements

When rendering different elements based on state (edit vs view mode), **always use `:replicant/key`** to ensure Replicant treats them as distinct elements:

```clojure
(if editing?
  [:span.edit {:replicant/key (str id "-edit")} ...]
  [:span.view {:replicant/key (str id "-view")} ...])
```

### 2. Lifecycle Hooks Require Correct Keys

`:replicant/on-mount` only fires when Replicant creates a **new** element. If keys are wrong and Replicant reuses elements, you get `:update` lifecycle, not `:mount`.

### 3. Use Methodical Debugging for Replicant Issues

As user advised:
1. **Check Replicant source code** - Don't guess how it works, read the implementation
2. **Web search for Replicant docs** - Find official documentation and examples
3. **Use AI tools** - Send detailed problem description to Gemini/Claude for analysis
4. **Check git history** - Deleted testing guides may have solutions

### 4. Trust Console Logs

The lifecycle logs (`"life-cycle":"update"` vs `"life-cycle":"mount"`) immediately revealed the problem. When debugging Replicant, **always log lifecycle phase** in hooks:

```clojure
:replicant/on-render
(fn [{:replicant/keys [node life-cycle]}]
  (.log js/console "Lifecycle:" life-cycle)  ; Critical for debugging!
  ...)
```

## References

- **Replicant source**: `~/Projects/best/replicant/src/replicant/core.cljc`
- **Element reuse logic**: `reusable?` function checks `hiccup/rkey` and `tag-name`
- **Recovered testing guides**:
  - `docs/REPLICANT_TESTING.md` - View testing, action extraction, integration tests
  - `docs/TESTING_DISCONNECT_FIXES.md` - Why tests passed but bugs existed
- **Fixed file**: `src/components/block.cljs` (lines 593, 720)
- **Controlled editable**: `src/evo/dom/editable.cljs` - MutationObserver + rollback pattern

## Summary

**The Fix**: Change `:key` → `:replicant/key` in both edit and view mode spans.

**Why It Worked**: Replicant now sees edit/view as different elements (different keys), creates new DOM nodes on transition, fires `:on-mount`, sets up observer, focuses element.

**Remaining Work**: Fix `:exit-edit` firing after `smart-split` (separate bug, investigate intent handlers).
