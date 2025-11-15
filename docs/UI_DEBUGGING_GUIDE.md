# UI Debugging Guide: Finding and Fixing Rendering Bugs

This document describes techniques for debugging UI issues, particularly rendering bugs, duplication issues, and state synchronization problems in event-sourced UIs.

## Case Study: Enter Key Duplication Bug

### Initial Symptoms
- Pressing Enter while editing causes blocks to swap positions or duplicate
- User reported: "blocks block-fa932cf3 and block-2974711e swapped when I pressed Enter"

### Investigation Workflow

#### 1. Reproduce the Issue in Browser DevTools

**Tools Used:**
- Chrome DevTools (MCP integration via `mcp__chrome-devtools__*` tools)
- Operations log (built into app)
- Human-Spec trees (BEFORE/AFTER DB state visualization)

**Steps:**
```javascript
// 1. Take snapshot to see current state
await mcp__chrome-devtools__take_snapshot()

// 2. Perform the action (click, press key)
await mcp__chrome-devtools__click({uid: "block-id"})
await mcp__chrome-devtools__press_key({key: "Enter"})

// 3. Take screenshot to see visual output
await mcp__chrome-devtools__take_screenshot()

// 4. Check operations log in app UI
// Look for duplicate events, validation errors
```

**What to Look For:**
- **Operations count**: How many events were dispatched?
- **DB changes**: Did the DB actually change?
- **Node count**: Did it increase (new block created)?
- **Human-Spec trees**: What does DB think vs what DOM shows?

#### 2. Compare DB State vs DOM State

**DB State** (from Human-Spec tree):
```
:E
- Tech Stack: ClojureScript + Replicant
- |  ← Empty block being edited
- See also: [[Tasks]] page for work items
```

**DOM State** (from accessibility snapshot):
```
- Tech Stack: ClojureScript + Replicant (view)
- See also: [[Tasks]] page for work items (editing, focused)
- See also: [[Tasks]] page for work items (duplicate)
```

**Key Insight:** DB and DOM don't match! This means rendering bug, not intent bug.

#### 3. Check for Duplicate Event Dispatch

**Pattern to Look For:**
```clojure
;; Bad: Keymap binding + Component handler both fire
Operations:
  {:type :context-aware-enter, :block-id "...", :cursor-pos :cursor-pos}  ← Keymap (unresolved placeholder)
  {:type :context-aware-enter, :block-id "...", :cursor-pos 37}           ← Component (correct values)
```

**Fix:**
```clojure
;; Remove Enter from :editing keymap (src/keymap/bindings_data.cljc)
:editing [
  ;; NOTE: Enter/Shift+Enter are NOT bound in keymap
  ;; They MUST be handled by Block component which provides correct cursor position context
  ...
]
```

#### 4. Trace Event Flow

**Full Pipeline:**
```
User Press Enter
    ↓
Block component keydown handler (components/block.cljs:344)
    ↓
handle-enter (line 261) → Nexus action [:editing/smart-split ...]
    ↓
Nexus registry (shell/nexus.cljs:145) → smart-split function
    ↓
Effect [:effects/dispatch-intent {:type :context-aware-enter ...}]
    ↓
Intent handler (plugins/smart_editing.cljc:340)
    ↓
Generate operations: :create-node, :update-node, :place
    ↓
Kernel transaction (kernel/api.cljs)
    ↓
DB atom update
    ↓
watch triggers re-render (shell/blocks_ui.cljs:408)
    ↓
Replicant reconciliation
```

#### 5. Check for Stale Closures & Rendering Issues

**Common Pattern:**
```clojure
(defn Block [{:keys [db block-id ...]}]
  (let [text (get-in db [:nodes block-id :props :text] "")  ; ← Read once!
        editing? (= (q/editing-block-id db) block-id)]
    [:span.content-edit
     {:replicant/on-render
      (fn [_]
        (set! (.-textContent node) text))}]))  ; ← Closure over stale `text`
```

**What Happens:**
- Block component function runs with `db` snapshot
- `text` variable captures value at that moment
- `:replicant/on-render` function closes over `text`
- On re-render, `text` is stale if DB changed

**Look For:**
- `let` bindings that capture DB values
- Lifecycle hooks (`:replicant/on-render`) that use those captured values
- Conditional text updates (`when mount` vs `on every render`)

#### 6. Use Browser Console for Runtime Inspection

**Check contenteditable elements:**
```javascript
// Find what's actually being edited
const editables = document.querySelectorAll('[contenteditable="true"]');
editables.forEach(el => {
  console.log('Editing:', el.textContent, 'Focus:', el === document.activeElement);
});

// Check block IDs
const blocks = document.querySelectorAll('[data-block-id]');
blocks.forEach(el => {
  const id = el.dataset.blockId;
  const editing = el.querySelector('[contenteditable="true"]');
  console.log('Block:', id, 'Editing?:', !!editing);
});
```

**Check DB state:**
```javascript
// If DEBUG object exposed
const db = window.DEBUG?.state?.();
console.log('Editing block ID:', db?.nodes?.['session/ui']?.props?.['editing-block-id']);
console.log('Children:', db?.['children-by-parent']?.['projects']);
```

#### 7. Verify Keybinding Conflicts

**Pattern to Check:**
```clojure
;; In keymap/bindings_data.cljc
:editing [
  [{:key "Enter"} {:type :context-aware-enter
                   :block-id :editing-block-id  ; ← Placeholder!
                   :cursor-pos :cursor-pos}]    ; ← Placeholder!
]

;; In components/block.cljs
(defn handle-enter [e db block-id on-intent]
  (on-intent [[:editing/smart-split
               {:block-id block-id           ; ← Actual value
                :cursor-pos cursor-pos}]]))  ; ← Actual value
```

**Result:** Both fire, creating race condition!

**Fix:** Remove keymap binding, let component handle it exclusively.

## Debugging Techniques Summary

### 1. **Operations Log Analysis**
- Count events (should be exactly 1 per user action)
- Check for unresolved placeholders (`:cursor-pos :cursor-pos`)
- Verify DB changes match expectations

### 2. **Human-Spec Tree Comparison**
- BEFORE vs AFTER trees should show clear diff
- "No DB changes" = intent handler returned nil/[]
- Look for block IDs, not just text

### 3. **DOM Snapshot vs DB State**
- Use `take_snapshot()` for accessibility tree
- Compare with Human-Spec tree
- Mismatches indicate rendering bugs, not logic bugs

### 4. **Screenshot for Visual Confirmation**
- Take before/after screenshots
- Identify duplicate elements visually
- Confirm what user actually sees

### 5. **Console Message Inspection**
- Check for validation errors
- Look for intent logging patterns
- Verify no JavaScript errors

### 6. **JavaScript Evaluation**
- Query DOM directly (`document.querySelector`)
- Check `window.getSelection()` for cursor state
- Inspect element properties at runtime

## Common Bug Patterns

### Pattern 1: Keymap + Component Handler Conflict
**Symptoms:** Duplicate events in operations log
**Cause:** Both keymap and component handle same key
**Fix:** Remove from keymap, handle exclusively in component

### Pattern 2: Unresolved Placeholders
**Symptoms:** Intent dispatched with `:cursor-pos :cursor-pos`
**Cause:** Keymap tries to resolve runtime values at compile time
**Fix:** Use Nexus actions from components, not keymap intents

### Pattern 3: Stale Closure in Lifecycle Hook
**Symptoms:** DOM shows old data after DB update
**Cause:** `let` binding captured at component fn call time
**Fix:** Read from DB/node inside lifecycle hook, not closure

### Pattern 4: Missing/Wrong Keys in Lists
**Symptoms:** Items duplicate or swap when list changes
**Cause:** No `:key` prop or same key for different items
**Fix:** Add unique `:key` based on stable ID (block-id)

### Pattern 5: Text Duplication from Extra Wrappers
**Symptoms:** Content appears twice in DOM
**Cause:** `(into [:span] (map ...))` or nested content elements
**Fix:** Use `textContent` directly, avoid child reconciliation

## Tools & Commands Reference

### Chrome DevTools MCP Tools
```javascript
mcp__chrome-devtools__take_snapshot()           // Accessibility tree
mcp__chrome-devtools__take_screenshot()          // Visual state
mcp__chrome-devtools__press_key({key: "Enter"}) // Simulate input
mcp__chrome-devtools__click({uid: "..."})       // Simulate click
mcp__chrome-devtools__evaluate_script({         // Run JS
  function: "() => document.querySelectorAll('[contenteditable]')"
})
mcp__chrome-devtools__list_console_messages()   // Check errors
```

### Browser Console Snippets
```javascript
// 1. Find editing block
document.querySelector('[contenteditable="true"]')?.dataset?.blockId

// 2. List all block IDs
[...document.querySelectorAll('[data-block-id]')].map(el => el.dataset.blockId)

// 3. Check cursor position
const sel = window.getSelection();
console.log('Offset:', sel.anchorOffset, 'Node:', sel.anchorNode);

// 4. Get text content
document.querySelector('[contenteditable="true"]')?.textContent

// 5. Check mock-text (for cursor detection)
document.getElementById('mock-text')?.innerHTML
```

### REPL Debugging
```clojure
;; Check DB state
@!db

;; Get editing block ID
(get-in @!db [:nodes "session/ui" :props :editing-block-id])

;; Get block text
(get-in @!db [:nodes "block-123" :props :text])

;; Get children
(get-in @!db [:children-by-parent "projects"])

;; Dispatch test intent
(api/dispatch @!db {:type :context-aware-enter
                     :block-id "proj-2"
                     :cursor-pos 37})
```

## Workflow Checklist

- [ ] Reproduce issue in browser
- [ ] Take snapshot + screenshot (BEFORE)
- [ ] Perform action
- [ ] Take snapshot + screenshot (AFTER)
- [ ] Check operations log for duplicate events
- [ ] Compare Human-Spec BEFORE/AFTER trees
- [ ] Compare DOM snapshot vs DB state
- [ ] Check console for errors
- [ ] Identify root cause (logic vs rendering)
- [ ] Fix and verify
- [ ] Document findings

## Key Files to Check

- **src/keymap/bindings_data.cljc** - Keybinding conflicts
- **src/shell/nexus.cljs** - Action → Intent translation
- **src/components/block.cljs** - Rendering logic, lifecycle hooks
- **src/plugins/smart_editing.cljc** - Intent handlers
- **src/kernel/api.cljs** - Operation application

## Success Criteria

✅ **Operations log shows exactly ONE event per user action**
✅ **DB state (Human-Spec) matches visual DOM state**
✅ **No unresolved placeholders** (`:cursor-pos :cursor-pos`)
✅ **Node count changes reflect actual block creation/deletion**
✅ **No duplicate elements in DOM**
✅ **Cursor position preserved correctly**
