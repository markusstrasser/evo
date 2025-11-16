# ContentEditable Rendering: Failure Modes & Solutions

**Date**: 2025-11-15
**Issue**: Cursor reset during typing + Empty blocks showing stale text after Enter
**Root Cause**: Controlled component pattern + stale closures in lifecycle hooks
**Solution**: Uncontrolled pattern with `:replicant/remember`

---

## Failure Modes Discovered

### 1. Cursor Reset During Typing (FIXED)

**Symptoms:**
- Type "test" → get "tset" (letters appear at position 0)
- Cursor jumps to start after each keystroke
- Makes typing impossible

**Root Cause:**
- Declarative text child `(or text "")` in contenteditable span
- Replicant sees text change → re-renders → sets textContent → resets cursor
- Controlled component anti-pattern for contenteditable

**How to Detect:**
```clojure
;; In REPL
(def test-block "some-block-id")
;; Click block, press Enter to edit, type "abc" slowly
;; If you see "cba" or cursor jumps → bug present
```

**Solution:**
```clojure
;; WRONG: Controlled component (declarative child)
[:span.content-edit
 {:contentEditable true}
 (or text "")]  ; ← Re-renders reset cursor

;; RIGHT: Uncontrolled component (lifecycle hook sets ONCE)
[:span.content-edit
 {:contentEditable true
  :replicant/on-render (fn [{:replicant/keys [node memory remember]}]
                         (when-not memory
                           (set! (.-textContent node) text)
                           (remember true)))}]  ; ← Set once, browser manages after
```

### 2. Empty Block Shows Stale Text (FIXED)

**Symptoms:**
- Press Enter at end of "Tech Stack" block
- DB shows empty block created with `{:text ""}`
- DOM shows "See also: [[Tasks]]" in contenteditable (wrong block's text!)
- "See also" appears duplicated below

**Root Cause:**
- Component closure captures `text` variable when function runs
- Lifecycle hook closes over this `text`
- When Replicant reconciles, closure has stale value from wrong block
- New empty block's hook sets textContent to stale "See also" text

**How to Detect:**
```clojure
;; In REPL or browser console
DEBUG.compareDBvsDOM("block-id")
;=> {:db {:text ""}
;    :dom {:text "See also: [[Tasks]]"}  ; ← Mismatch!
;    :match? false}

;; Or trace Enter key
DEBUG.traceEnter("proj-2", 37)
;; Check: DOM text should match DB text for new block
```

**Failed Solutions (tried but didn't work):**
1. ❌ Read text from DB inside lifecycle hook using closure `db`
   - Still stale because `db` is also captured in closure
2. ❌ Read block-id from `node.dataset.blockId` then fetch from `db`
   - Still stale because `db` itself is from closure
3. ❌ Set textContent only on `:mount` lifecycle
   - Component function not called with fresh DB for new blocks

**Working Solution:**
```clojure
;; Use :replicant/remember to set textContent ONLY ONCE
:replicant/on-render (fn [{:replicant/keys [node memory remember]}]
  (when-not memory  ; Only if not yet initialized
    (set! (.-textContent node) text)  ; text is fresh in THIS component instance
    (remember true)))  ; Never touch textContent again
```

**Why this works:**
- Each new DOM node starts with `memory = nil`
- Component function IS called with fresh `db` for new block
- `text` variable IS correct for this specific component instance
- We set textContent ONCE using this fresh `text`
- `remember true` marks node as initialized
- Future re-renders check `memory` → skip textContent update
- Browser manages contenteditable content from that point on

### 3. Focus Not Attaching to Empty Block (FIXED)

**Symptoms:**
- Press Enter at end of block to create new empty block
- New block appears in DOM with empty text (correct)
- BUT: Can't type immediately - must click first
- Focus remains on document body, not contenteditable

**Root Cause:**
- Empty block with `text = ""` creates NO text node in DOM
- `(set! (.-textContent node) "")` doesn't create a text node
- Cursor positioning code checks `(when (and text-node ...))`
- `.focus node` call was INSIDE this conditional (line 566 before fix)
- Since no text node exists, condition fails and `.focus` never executes

**How to Detect:**
```javascript
// In browser console after pressing Enter
const editable = document.querySelector('[contenteditable="true"]');
console.log({
  hasFirstChild: !!editable.firstChild,  // false for empty block
  isFocused: document.activeElement === editable,  // false - BUG!
  actualFocus: document.activeElement.tagName  // "BODY"
});
```

**Working Solution:**
```clojure
;; Move .focus OUTSIDE text-node check
(if cursor-pos
  (let [last-applied (aget node "__lastAppliedCursorPos")]
    (when (not= cursor-pos last-applied)
      (try
        (let [range (.createRange js/document)
              sel (.getSelection js/window)
              text-node (.-firstChild node)]
          ;; Position cursor ONLY if text node exists
          (when (and text-node (= (.-nodeType text-node) 3))
            (let [text-length (.-length text-node)
                  pos ...]
              (.setStart range text-node pos)
              (.setEnd range text-node pos)
              ...))
        (catch js/Error e
          (js/console.error "Cursor positioning failed:" e))))
    ;; CRITICAL FIX: Always focus, even for empty blocks
    (.focus node))
  (.focus node))
```

**Why this works:**
- Focus happens ALWAYS, regardless of whether text node exists
- Cursor positioning only attempts when text node present
- Empty blocks get focus, user can type immediately
- Matches Logseq behavior: mouse-free navigation

---

## Testing Methodology

### Comprehensive Test Checklist

#### 1. Cursor Preservation Test
```clojure
;; Steps:
1. Click any block to select
2. Press Enter to enter edit mode
3. Press End to move cursor to end
4. Type "test" slowly, one character at a time
5. ✅ PASS: Text appears as "test" (in order)
   ❌ FAIL: Text appears as "tset" or cursor jumps
```

#### 2. Enter Key Empty Block Test
```clojure
;; Steps:
1. Click "Tech Stack" block
2. Press Enter to edit
3. Press End, then Enter to create new block
4. Check DB state (Human-Spec tree should show `- |`)
5. Check DOM state:
   ;; In browser console:
   DEBUG.findContenteditable()
   ;=> [{:block-id "block-xxx", :text "", :is-focused true}]
6. ✅ PASS: contenteditable has text = "" (empty)
   ❌ FAIL: contenteditable has text from another block
7. ✅ PASS: No duplicate "See also" blocks visible
   ❌ FAIL: "See also" appears twice
```

#### 3. Multi-Block Workflow Test
```clojure
;; Steps:
1. Create new block (Enter)
2. Type "first" → verify cursor stays at end
3. Press Enter → create another block
4. Type "second" → verify cursor stays at end
5. Press Enter → create third block
6. Verify all three blocks have correct text in DOM
7. ✅ PASS: All blocks show correct text, no duplication
   ❌ FAIL: Any block shows wrong text or duplicates
```

### Browser Console Debugging

```javascript
// 1. Check what's being edited
DEBUG.findContenteditable()
// Should return exactly one element when editing

// 2. Compare DB vs DOM for a specific block
DEBUG.compareDBvsDOM("block-id")
// Should show match? true

// 3. Trace Enter key step-by-step
DEBUG.traceEnter("proj-2", 37)
// Watch console for DB vs DOM mismatch warnings

// 4. Inspect all blocks
DEBUG.snapshot()
// Returns full render cycle state
```

### REPL Debugging

```clojure
(require '[dev.debug.block-render :as dbg])

;; 1. Compare DB vs DOM
(dbg/compare-db-vs-dom @!db "block-id")

;; 2. Find all contenteditable elements
(dbg/find-contenteditable)

;; 3. Inspect block state
(dbg/inspect-block @!db "block-id")

;; 4. Take full snapshot
(dbg/snapshot-render-cycle @!db)
```

---

## Logseq Research: Uncontrolled Pattern

**Key Insight from Logseq source code:**

Logseq uses **uncontrolled textarea** with `default-value`:
```clojure
(ui/ls-textarea
  {:default-value content  ; ← Set ONCE on mount
   :on-change editor-on-change!})  ; ← Updates state, NOT DOM
```

**Why this works:**
1. `default-value` sets initial value (React-specific)
2. Browser manages cursor/content after that
3. `on-change` handler updates app state WITHOUT re-rendering input
4. Cursor position managed by browser naturally

**Replicant Translation:**

Since Replicant doesn't have `default-value` for contenteditable:
- Use `:replicant/remember` to set textContent ONLY ONCE
- Let browser manage content after initialization
- `:input` event updates DB without touching DOM

---

## Debugging Tools Created

### Location: `dev/debug/`

1. **`block_render.cljs`** - State inspection
   - `inspect-block` - DB state for a block
   - `inspect-dom-block` - Actual DOM state
   - `compare-db-vs-dom` - Side-by-side comparison
   - `find-contenteditable` - List all editing elements

2. **`enter_key_trace.cljs`** - Step-by-step tracing
   - Traces Enter key through full pipeline
   - Shows operations, DB changes, DOM state
   - Highlights DB/DOM mismatches

### Integration

Attach to browser console via `shell.blocks_ui`:
```clojure
(require '[dev.debug.block-render :as dbg])
(dbg/init-debug-tools shell.blocks-ui/!db)
```

Then use in browser console:
```javascript
DEBUG.compareDBvsDOM("block-id")
DEBUG.traceEnter("proj-2", 37)
```

---

## Common Pitfalls

### 1. Mixing Controlled + Uncontrolled
```clojure
;; ❌ WRONG: Can't have both
[:span {:replicant/on-render #(set-text! %)}
  (or text "")]  ; ← Conflict!
```

### 2. Setting textContent on Every Render
```clojure
;; ❌ WRONG: Resets cursor
:replicant/on-render (fn [{:replicant/keys [node]}]
  (set! (.-textContent node) text))  ; ← Every render!
```

### 3. Reading Stale Closure in Lifecycle Hook
```clojure
;; ❌ WRONG: db and text are stale
:replicant/on-render (fn [{:replicant/keys [node]}]
  (let [current-text (get-in db [:nodes block-id :props :text])]
    (set! (.-textContent node) current-text)))  ; ← db is stale!
```

### 4. Missing `:key` on Elements
```clojure
;; ❌ WRONG: No key = Replicant may reuse nodes
[:span.content-edit {:contentEditable true}]

;; ✅ RIGHT: Unique key per block
[:span.content-edit {:key (str block-id "-edit")
                     :contentEditable true}]
```

---

## Verification Checklist

Before claiming "bug is fixed":

- [ ] Test cursor preservation (type "test", verify order)
- [ ] Test Enter key creates empty block with empty text
- [ ] Test multiple Enter presses in sequence
- [ ] Verify DB state matches DOM state (`DEBUG.compareDBvsDOM`)
- [ ] Check for duplicated content in DOM
- [ ] Verify no console errors
- [ ] Test arrow key navigation still works
- [ ] Take screenshot showing correct rendering

---

## Related Issues

- **Cursor reset** → Controlled component anti-pattern
- **Stale closure** → Lifecycle hook captures old values
- **Block duplication** → Replicant reconciliation confusion
- **Wrong text in new blocks** → Component not re-rendered with fresh DB

## See Also

- `docs/RENDERING_AND_DISPATCH.md` - Replicant lifecycle hooks
- `docs/UI_DEBUGGING_GUIDE.md` - General UI debugging
- `dev/debug/block_render.cljs` - REPL debugging tools
- Logseq source: `src/main/frontend/components/editor.cljs`
- Replicant docs: https://replicant.fun/life-cycle-hooks/#memory
