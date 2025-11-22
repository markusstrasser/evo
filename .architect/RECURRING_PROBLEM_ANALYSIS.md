# Deep Analysis: The Recurring Problem Pattern

## Executive Summary

**Problem**: You're stuck in a **testing-reality mismatch loop** where unit tests pass but browser bugs persist. Over the last 2 months, you've made ~1,874 commits with ~330 fixes (17.6% of all commits are fixes), and the majority cluster around the same root issues: **contenteditable lifecycle management, cursor positioning, and focus handling with Replicant**.

**Root Cause**: The architecture has a **fundamental impedance mismatch** between three layers that don't share the same lifecycle model:
1. **Kernel** (pure data, event sourcing)
2. **Replicant** (declarative UI with lifecycle hooks)
3. **Browser DOM** (imperative, stateful, contenteditable quirks)

## The Pattern: Why Bugs Keep Recurring

### Symptom Timeline (Last 2 Months)

From git history analysis:

```
213 commits in last month = cursor/focus/edit/selection/lifecycle issues
1874 total commits in 2 months
330 fix commits (17.6% of all work is fixing bugs)
```

**Most frequently touched file**: `src/components/block.cljs`
- 20+ commits in last 30 days
- Each "fix" creates new edge cases
- No clear convergence to stability

**Recurring issues documented**:
1. `:key` vs `:replicant/key` (lifecycle hooks not firing)
2. `element->text` vs `textContent` (off-by-one cursor bugs)
3. Edit mode not entered after Enter key
4. Cursor jumping to wrong position
5. Focus not attaching to contenteditable
6. MutationObserver setup in wrong lifecycle hook
7. Selection state desync between kernel and DOM
8. Text duplication on rapid typing

### The Core Architectural Problem

You have **three incompatible state models** fighting each other:

#### 1. Kernel Model (Pure Event Sourcing)
```clojure
{:nodes {"block-a" {:props {:text "Hello"}}}
 :ui {:editing-block-id "block-a"
      :cursor-position 5}}
```
- **Immutable snapshots**
- **Every change is an operation**
- **Undo/redo by replaying history**
- **No side effects**

#### 2. Replicant Model (React-like VDOM)
```clojure
[:span.edit {:replicant/key "block-a-edit"
             :replicant/on-mount setup-focus!
             :replicant/on-render update-cursor!}]
```
- **Element identity via :replicant/key**
- **Lifecycle hooks** (:mount, :render, :unmount)
- **Declarative rendering with imperative escape hatches**
- **Diffs between renders to minimize DOM changes**

#### 3. Browser DOM Model (Imperative + Stateful)
```javascript
// contenteditable element
<span contenteditable="true">Hello</span>

// Selection API (global mutable state!)
window.getSelection().getRangeAt(0).startOffset = 5
```
- **Global mutable Selection API**
- **contenteditable maintains internal state**
- **Focus is a side effect**
- **Cursor position must be explicitly managed**

### Where They Clash

The bugs occur at **boundaries between these models**:

1. **Kernel → Replicant**: When does `:cursor-position 5` in kernel become `setSelectionRange(5)` in DOM?
   - **Problem**: Replicant re-renders on every kernel change, but cursor setting is a side effect
   - **Current approach**: `:replicant/on-render` hook sets cursor on every render
   - **Bug**: Cursor setting during render can trigger new renders (nested render warning)

2. **Replicant → DOM**: When is a new DOM element created vs reused?
   - **Problem**: Wrong `:key` means element reused, `:on-mount` never fires, MutationObserver never attached
   - **Current fix**: Use `:replicant/key` instead of `:key`
   - **Gotcha**: This is non-obvious and breaks silently (no error, hooks just don't fire)

3. **DOM → Kernel**: How do contenteditable changes flow back to kernel?
   - **Problem**: User types → DOM mutates → Need to sync back to kernel → Triggers re-render → May reset cursor
   - **Current approach**: MutationObserver captures changes, dispatches intent with cursor position
   - **Bug**: Cursor position calculation wrong if using `element->text` (adds trailing `\n`)

4. **Kernel History → DOM State**: When undo/redo, how to restore cursor + editing mode?
   - **Problem**: Kernel history contains only canonical ops (create/place/update), not ephemeral UI state
   - **Current approach**: Separate `:ui` map (not in history) for editing-block-id, cursor-position
   - **Bug**: Undo changes text, cursor position in `:ui` now stale, points to wrong offset

## Why Tests Don't Catch This

### Test Coverage Analysis

From your documentation (TESTING_DISCONNECT_FIXES.md):

**Unit Tests (259 passing)**:
- ✅ Test kernel operations in isolation
- ✅ Test intent handlers return correct ops
- ✅ Test derived index computation
- ❌ Don't test Replicant lifecycle
- ❌ Don't test DOM Selection API
- ❌ Don't test focus/blur events
- ❌ Don't test contenteditable behavior

**E2E Tests (Playwright)**:
- ✅ Test actual browser behavior
- ✅ Verify cursor position in DOM
- ✅ Check focus state
- ⚠️ But were added AFTER bugs found (reactive, not proactive)
- ⚠️ Coverage gaps for new features

### The Disconnect

```
Unit Test: "Intent :enter-edit sets :editing-block-id in :ui" ✅
Reality:   User clicks block, nothing happens, contenteditable never focused ❌

Unit Test: "Intent :smart-split creates new block below" ✅
Reality:   New block created, but exits edit mode immediately ❌

Unit Test: "Cursor position preserved on indent" ✅
Reality:   Cursor jumps to start of block ❌
```

**Why?** Unit tests verify **data transformations** but not **side effects** (focus, cursor position, DOM state).

## Attempted Fixes & Why They Failed

### Fix Attempt 1: Controlled Editable with MutationObserver

**Commit**: `64a72cf feat: Add robust text selection utilities from use-editable`

**Idea**: Port React's `use-editable` hook pattern to ClojureScript
- MutationObserver captures DOM changes
- Rollback mutations, dispatch intent with new text
- Re-render with updated kernel state

**Why it broke**:
- Used `:key` instead of `:replicant/key` → element reused → `:on-mount` never fired → observer never attached
- Used `element->text` in input handler → added `\n` → cursor offset wrong by 1

**Secondary bugs introduced**:
- Nested render warnings (setting cursor during render triggered new render)
- Edit mode exit after Enter (blur event during block creation)

### Fix Attempt 2: Use :replicant/remember for Cursor

**Commit**: `4b54da0 fix(block): use :replicant/remember to fix Enter key empty block bug`

**Idea**: Use Replicant's `:replicant/remember` to preserve cursor position across renders

**Why it failed** (from docs/specs/CURSOR_GUARD_FLAG.md):
- `:replicant/remember` prevents re-renders entirely
- Breaks intentional updates (text changes, mode switches)
- Creates new edge cases (stale DOM state)

**Reverted in next commit**

### Fix Attempt 3: Uncontrolled Pattern

**Earlier in history**: Used uncontrolled contenteditable, relied on browser's internal state

**Why it broke**:
- Kernel state and DOM state desync
- Undo/redo doesn't work (can't replay DOM mutations)
- Can't programmatically set cursor position
- Violates "kernel is source of truth" principle

### Fix Attempt 4: Separate Edit/View Elements with Keys

**Commit**: `e870374 fix(block): use :replicant/key instead of :key`

**Idea**:
- Two separate elements: `[:span.edit {:replicant/key "id-edit"}]` and `[:span.view {:replicant/key "id-view"}]`
- Replicant creates new DOM element when switching modes
- `:on-mount` fires reliably

**Status**: ✅ **This worked!** But only after 4+ previous attempts

**Why it took so long**:
- Replicant documentation doesn't emphasize `:replicant/key` vs `:key` difference
- No error message when using wrong key (silent failure)
- Requires deep understanding of Replicant's internal `reusable?` function

## Metrics That Reveal the Problem

### Commit Churn
```
src/components/block.cljs: 20+ commits in 30 days
17.6% of commits are fixes
Most fixes in same areas (cursor, focus, lifecycle)
```

### Documentation Density
```
4 major docs added in last week:
- REPLICANT_KEY_FIX.md (242 lines)
- TEXT_SELECTION.md (280 lines)
- TESTING_DISCONNECT_FIXES.md (434 lines)
- REPLICANT_TESTING.md (485 lines)

Total: 1,441 lines of "gotchas" documentation
```

**Interpretation**: You're documenting *workarounds* for architectural problems, not clarifying design intent.

### Test-to-Bug Ratio

```
259 unit tests passing
~30 E2E tests added reactively (after bugs found)
Still finding new cursor/focus bugs weekly
```

**Red flag**: Tests don't predict bugs, only confirm fixes work.

## Root Cause: Three-Model Impedance Mismatch

The fundamental issue is **you're trying to maintain referential transparency (kernel) while managing imperative side effects (DOM) through a declarative layer (Replicant)**.

### The Tension

**Kernel philosophy**:
> "Pure functions, immutable data, event sourcing, no side effects"

**DOM reality**:
> "Global mutable Selection API, focus is a side effect, contenteditable has internal state"

**Replicant's role**:
> "Mediate between pure data and imperative DOM, but lifecycle hooks are escape hatches"

### Where It Breaks Down

1. **Cursor position is not data, it's a side effect**
   - Storing `{:cursor-position 5}` in kernel doesn't move the cursor
   - Must imperatively call `setSelectionRange()` on DOM element
   - But when? On every render? Only on mount? Only on specific changes?

2. **Edit mode is partly ephemeral, partly structural**
   - `:editing-block-id` is ephemeral (don't want in undo history)
   - But editing-related *text changes* are structural (do want in history)
   - Currently split across `:ui` (ephemeral) and `:nodes` (structural)
   - Leads to state desync

3. **Focus cannot be declarative**
   - Can't write `[:span {:focused true}]` and have it work
   - Must imperatively call `.focus()` on DOM element
   - Must happen AFTER element mounted (not during render)
   - Must not re-focus on every render (causes scroll jumps)

## The Layering Problem Visualized

```
┌─────────────────────────────────────────────────────────┐
│ USER ACTION: Press Enter in contenteditable            │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────┐
│ DOM EVENT: keydown {key: "Enter"}                      │
│ STATE: contenteditable has text "Hello|", cursor at 5   │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────┐
│ EVENT HANDLER (in block.cljs)                          │
│ - Extract cursor position from Selection API           │
│ - Dispatch intent {:type :smart-split :cursor-pos 5}   │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────┐
│ KERNEL INTENT HANDLER                                  │
│ - Calculate ops: create new block, update text         │
│ - Return [{:op :create ...} {:op :place ...}]         │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────┐
│ KERNEL TRANSACTION                                     │
│ - Apply ops, recompute derived indexes                 │
│ - New DB state with new block                          │
│ - PURE DATA, no side effects yet                       │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────┐
│ REPLICANT RENDER (shell/blocks_ui.cljs)               │
│ - Receive new DB, generate hiccup                      │
│ - Diff against current VDOM                            │
│ - Decide which elements to create/update/delete        │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────┐
│ REPLICANT LIFECYCLE                                    │
│ - :on-mount fired for NEW block's contenteditable      │
│ - :on-render fired for EXISTING block                  │
│ - Must set focus on NEW block                          │
│ - Must preserve cursor in EXISTING block               │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────┐
│ DOM SIDE EFFECTS (in lifecycle hooks)                 │
│ - .focus() on new contenteditable                      │
│ - setSelectionRange() to position cursor               │
│ - Setup MutationObserver for future changes            │
│ BUT: Which element? Old or new? Both?                  │
│      What if render triggered again during this?       │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────┐
│ BROWSER REALITY                                        │
│ - Focus moved to new element (maybe)                   │
│ - Cursor positioned (maybe)                            │
│ - Selection API updated (maybe)                        │
│ - IF WRONG: Silent failure, no error                   │
└─────────────────────────────────────────────────────────┘
```

**The Problem**: Each step has **implicit assumptions** about the others:
- Kernel assumes side effects handled by UI layer
- Replicant assumes data is ready when lifecycle fires
- DOM operations assume element is mounted and visible
- No single source of truth for "what should happen when"

## Why Current Architecture Makes This Hard

### Design Decision Analysis

From VISION.md and ADRs:

**ADR-017**: "Four-op kernel with ephemeral UI state"
- ✅ Good: Keeps ephemeral state out of history
- ❌ Bad: Now have two state models (`:nodes` + `:ui`) that can desync

**3-op kernel** (create, place, update):
- ✅ Good: Simple, minimal IR
- ❌ Bad: Doesn't model focus/cursor (side effects, not data)

**Event sourcing**:
- ✅ Good: Perfect undo/redo for structural changes
- ❌ Bad: Doesn't capture ephemeral UI state (cursor position, scroll position, focus)

**Replicant (not React)**:
- ✅ Good: ClojureScript native, smaller bundle
- ❌ Bad: Less documentation, fewer examples, lifecycle hooks are different
- ❌ Bad: `:key` vs `:replicant/key` difference is subtle and breaks silently

### Missing Layer: Side Effect Manager

You have:
- ✅ Kernel (data transformations)
- ✅ Replicant (view rendering)
- ❌ **No explicit side effect manager**

Currently, side effects are:
- Scattered in lifecycle hooks (`:on-mount`, `:on-render`)
- Mixed with rendering logic
- No declarative specification
- No testing strategy

**What you need** (from successful Elm/React architectures):
```
┌──────────────┐
│   Kernel     │  Pure data transformations
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ Effect Mgr   │  Declarative side effects
└──────┬───────┘  {effect: :focus, target: "block-a"}
       │           {effect: :cursor, pos: 5}
       ▼
┌──────────────┐
│  Replicant   │  Just rendering, no side effects
└──────────────┘
```

## Concrete Evidence This Is The Problem

### Pattern 1: Fixes Create New Bugs

```
Fix `:key` → `:replicant/key`
✅ Lifecycle hooks now fire
❌ But now nested render warnings

Fix nested render by moving cursor logic
✅ No more warnings
❌ But cursor jumps to wrong position

Fix cursor position with element->text
✅ Cursor mostly correct
❌ But off-by-one error (trailing \n)

Fix off-by-one with textContent
✅ Cursor position perfect
❌ But now cursor positioning in multi-line breaks
```

**Interpretation**: You're playing whack-a-mole because the **architecture doesn't have a principled answer** for "when and how to run side effects."

### Pattern 2: Documentation Complexity

From CLAUDE.md:
```markdown
### Replicant Keys

**Always use `:replicant/key`, never `:key`**

### Text Selection Utilities

Use `util.text-selection` for contenteditable DOM operations:

❌ WRONG: Using element->text for input/paste handlers
✅ CORRECT: Use textContent for simple text extraction

### Cursor Positioning

Guard cursor placement with the `__lastAppliedCursorPos` pattern
```

**Interpretation**: You're accumulating gotchas because the **framework doesn't enforce correct patterns**. Every developer (including AI assistants) must memorize all gotchas or risk introducing bugs.

### Pattern 3: Test Coverage Doesn't Help

```
259 unit tests pass
✅ Test: "smart-split creates new block"
❌ Reality: New block created but edit mode exits

✅ Test: "enter-edit sets editing-block-id"
❌ Reality: Contenteditable never focused
```

**Interpretation**: Testing the **data layer** doesn't validate the **side effect layer**. Need separate test strategy for side effects.

## Why This Keeps Happening

### 1. No Forcing Function for Correct Patterns

Replicant allows both `:key` and `:replicant/key` in attributes:
- Using `:key` doesn't error, just silently fails to provide element identity
- No compile-time or runtime check
- Only visible symptom: lifecycle hooks don't fire
- Takes hours of debugging to discover

**Fix requires**:
- Reading Replicant source code
- Understanding internal `reusable?` function
- Or: Finding it documented in CLAUDE.md gotchas

### 2. Imperative Escape Hatches Without Guardrails

Lifecycle hooks (`:on-mount`, `:on-render`) let you run arbitrary code:
```clojure
:replicant/on-render
(fn [{:replicant/keys [node]}]
  (.focus node)  ; Runs on EVERY render!
  (setSelectionRange ...)  ; May trigger NEW render!
  (setup-observer ...)  ; Should be in :on-mount!
)
```

**No guardrails**:
- Can violate Replicant's assumptions (triggering nested renders)
- No clear contract for what's safe to do in each hook
- Easy to create infinite render loops
- No way to test side effects in isolation

### 3. State Split Across Three Locations

For editing a block, state lives in:

1. **Kernel** (`:nodes`):
   ```clojure
   {"block-a" {:props {:text "Hello"}}}
   ```

2. **Kernel** (`:ui`):
   ```clojure
   {:editing-block-id "block-a"
    :cursor-position 5}
   ```

3. **DOM**:
   ```javascript
   window.getSelection().getRangeAt(0).startOffset  // 5
   document.querySelector('[data-id="block-a"]').textContent  // "Hello"
   ```

**Invariants must hold**:
- Kernel text matches DOM text
- Cursor position in `:ui` matches Selection API
- `editing-block-id` matches which element has contenteditable

**But no mechanism enforces this!**
- Changes can happen independently
- Desync is silent (no error)
- Only visible when user sees wrong behavior

### 4. Async Lifecycle Without Coordination

When user presses Enter:

```
t0: keydown event
t1: dispatch :smart-split intent
t2: kernel creates new block
t3: Replicant starts render
t4: VDOM diff calculated
t5: DOM mutations applied
t6: :on-mount fires for new block
t7: .focus() called on new element
t8: Browser focuses element
t9: Selection API updated
```

**But also**:
```
t3.5: blur event fires on old element
t3.6: blur handler dispatches :exit-edit
t3.7: kernel sets editing-block-id = nil
t3.8: Replicant re-render starts
t3.9: NEW VDOM removes contenteditable
t6: :on-mount fires but editing-block-id already nil
t7: .focus() called but element about to unmount
```

**No coordination mechanism**:
- Events can interleave
- State changes trigger cascading renders
- Race conditions are possible
- Hard to reason about order of operations

## What Would Fix This

### Option 1: Explicit Effect System (Elm-style)

**Kernel returns data + effects**:
```clojure
;; Intent handler returns [new-db effects]
(defn handle-smart-split [db intent]
  (let [new-db (apply-ops db ops)
        effects [{:effect :focus-block :id new-block-id}
                 {:effect :set-cursor :id new-block-id :pos 0}
                 {:effect :enter-edit-mode :id new-block-id}]]
    [new-db effects]))
```

**Effect manager executes after render**:
```clojure
(defn execute-effects! [effects]
  (doseq [{:keys [effect] :as e} effects]
    (case effect
      :focus-block (focus-element! (:id e))
      :set-cursor (set-cursor-position! (:id e) (:pos e))
      :enter-edit-mode (enter-edit-mode! (:id e)))))
```

**Benefits**:
- ✅ Effects are data (testable, serializable)
- ✅ Clear execution order
- ✅ No scattered side effects in lifecycle hooks
- ✅ Can log/replay effects for debugging

**Tradeoffs**:
- Need to build effect system
- Replicant lifecycle hooks still exist (can't eliminate)
- More boilerplate

### Option 2: Controlled Components with One-Way Data Flow

**Make contenteditable fully controlled**:
```clojure
;; Kernel state
{:editing-block-id "a"
 :cursor-position 5
 :blocks {"a" {:text "Hello"}}}

;; Component ONLY renders from kernel state
[:span.edit
 {:contenteditable true
  :value (get-in db [:blocks "a" :text])  ; One-way binding
  :on-input handle-change!}]

;; Input handler updates kernel, triggers re-render
(defn handle-change! [event]
  (let [new-text (.-textContent target)
        cursor-pos (get-cursor-position target)]
    (dispatch! [:update-text {:id "a" :text new-text :cursor cursor-pos}])))
```

**Use effect in lifecycle to sync kernel → DOM**:
```clojure
:replicant/on-render
(fn [{:replicant/keys [node]}]
  (let [kernel-text (get-in @db [:blocks "a" :text])
        dom-text (.-textContent node)
        kernel-cursor (get-in @db [:ui :cursor-position])]
    ;; Sync text if different
    (when (not= kernel-text dom-text)
      (set! (.-textContent node) kernel-text))
    ;; Sync cursor if different
    (when (not= kernel-cursor (get-dom-cursor node))
      (set-cursor! node kernel-cursor))))
```

**Benefits**:
- ✅ Kernel is single source of truth
- ✅ Explicit sync points (lifecycle hooks)
- ✅ Can test rendering separately from side effects

**Tradeoffs**:
- Must carefully manage cursor position (current challenge)
- Nested render warnings (if setting cursor triggers render)
- Performance (re-rendering on every keystroke)

### Option 3: Accept The Impedance Mismatch

**Current approach**:
- Kernel is source of truth for structure
- DOM is source of truth for editing state (contenteditable)
- Sync only when necessary (enter edit, exit edit, save)

**Make it explicit**:
```clojure
;; Two modes:
;; 1. VIEW MODE: Kernel → DOM (one-way)
;; 2. EDIT MODE: DOM is source of truth (uncontrolled)

(defn block-component [db block-id]
  (let [editing? (= block-id (get-in db [:ui :editing-block-id]))]
    (if editing?
      ;; EDIT MODE: Uncontrolled contenteditable
      [:span.edit
       {:contenteditable true
        :replicant/key (str block-id "-edit")
        :replicant/on-mount (fn [node]
                              (setup-edit-mode! node block-id))}]

      ;; VIEW MODE: Pure render from kernel
      [:span.view
       {:replicant/key (str block-id "-view")}
       (get-in db [:blocks block-id :text])])))

(defn setup-edit-mode! [node block-id]
  ;; Setup MutationObserver
  ;; On change: Extract text + cursor, dispatch to kernel
  ;; On blur: Sync final state to kernel, exit edit mode
  )
```

**Benefits**:
- ✅ Simpler: Two clear modes with different contracts
- ✅ Uncontrolled edit mode avoids cursor fighting
- ✅ Can leverage browser's contenteditable behavior

**Tradeoffs**:
- Can't undo individual characters (only whole edit sessions)
- Kernel state stale during editing
- Must carefully sync on mode transitions

## Recommendation

Based on the evidence, I recommend **Option 3** (Accept The Impedance Mismatch) with modifications:

### Proposed Architecture

```
┌──────────────────────────────────────────────────┐
│ VIEW MODE (Kernel → DOM, one-way)              │
│ - Kernel is source of truth                    │
│ - Replicant renders from kernel state          │
│ - No contenteditable                            │
│ - Click to enter edit mode                     │
└──────────────────────────────────────────────────┘
                     │
                     ▼ (transition)
┌──────────────────────────────────────────────────┐
│ EDIT MODE (DOM is source of truth)             │
│ - Contenteditable is uncontrolled              │
│ - MutationObserver captures changes            │
│ - Periodically sync to kernel (debounced)       │
│ - On blur: Final sync + exit edit mode         │
└──────────────────────────────────────────────────┘
```

### Key Changes

1. **Stop fighting the browser during edit mode**
   - Let contenteditable be uncontrolled
   - Don't try to set cursor on every render
   - Only set cursor on ENTER edit mode

2. **Debounced kernel sync**
   - Accumulate changes locally
   - Sync to kernel every 500ms OR on blur
   - Reduces re-render churn

3. **Undo granularity: Edit sessions, not characters**
   - Record snapshot when ENTER edit mode
   - Record snapshot when EXIT edit mode
   - Undo restores whole edit session (acceptable UX)

4. **Clear contracts for each mode**
   ```clojure
   ;; VIEW MODE contract
   - Input: kernel state
   - Output: rendered DOM
   - Side effects: NONE

   ;; EDIT MODE contract
   - Input: initial text + cursor position
   - Output: onChange events with new text + cursor
   - Side effects: focus, setup observer, manage cursor
   ```

5. **Testing strategy**
   - Unit tests: Kernel ops (current coverage is good)
   - Integration tests: Mode transitions (view → edit → view)
   - E2E tests: User interactions (type, enter, tab, etc.)

### Implementation Plan

1. **Separate edit/view elements with clear keys** (DONE ✅)

2. **Remove cursor setting from :on-render**
   ```clojure
   ;; BEFORE (current)
   :replicant/on-render (fn [node]
                          (set-cursor! node pos))  ; On EVERY render

   ;; AFTER
   :replicant/on-mount (fn [node]
                         (set-cursor! node initial-pos))  ; ONCE on enter edit
   ```

3. **Debounced kernel sync**
   ```clojure
   (defn setup-edit-mode! [node block-id initial-text initial-cursor]
     (let [local-state (atom {:text initial-text
                               :cursor initial-cursor
                               :dirty? false})]
       ;; MutationObserver updates local state
       (observe! node (fn [new-text new-cursor]
                        (swap! local-state assoc
                               :text new-text
                               :cursor new-cursor
                               :dirty? true)))

       ;; Periodic sync (500ms)
       (js/setInterval
         (fn []
           (when (:dirty? @local-state)
             (dispatch! [:update-block block-id @local-state])
             (swap! local-state assoc :dirty? false)))
         500)))
   ```

4. **Explicit transition points**
   ```clojure
   ;; ENTER edit mode
   - Set kernel state: {:editing-block-id id :cursor-position pos}
   - Replicant creates [:span.edit {:replicant/key "...-edit"}]
   - :on-mount sets up uncontrolled contenteditable
   - Focus + cursor positioned ONCE

   ;; EXIT edit mode
   - Final sync: Get DOM text + cursor, dispatch to kernel
   - Set kernel state: {:editing-block-id nil}
   - Replicant creates [:span.view {:replicant/key "...-view"}]
   - No side effects needed (pure render)
   ```

5. **Guard against cascading renders**
   ```clojure
   (def ^:private __transitioning (atom false))

   (defn enter-edit-mode! [id]
     (when-not @__transitioning
       (reset! __transitioning true)
       (dispatch! [:enter-edit {:id id}])
       (js/setTimeout #(reset! __transitioning false) 100)))
   ```

### Success Metrics

After implementation:
- ✅ Cursor doesn't jump during typing
- ✅ Focus works reliably when entering edit mode
- ✅ No nested render warnings
- ✅ Undo/redo works at edit-session granularity
- ✅ No more off-by-one cursor bugs
- ✅ E2E tests pass consistently
- ✅ < 5% of commits are fixes (vs current 17.6%)

## Summary

**You're stuck because**:
1. Three incompatible state models (kernel, Replicant, DOM) without clear boundaries
2. No explicit effect system (side effects scattered in lifecycle hooks)
3. Testing only covers data transformations, not side effects
4. Trying to make contenteditable "controlled" fights browser behavior

**The solution**:
1. Accept that edit mode is uncontrolled (DOM is source of truth during editing)
2. Clear contracts for view mode (pure kernel → DOM) vs edit mode (DOM is primary)
3. Explicit transition points with guarded side effects
4. Debounced sync to kernel (not on every keystroke)
5. Test mode transitions, not just individual operations

**This will work because**:
- Aligns with browser's contenteditable model (stop fighting it)
- Clear separation of concerns (when kernel is truth, when DOM is truth)
- Fewer re-renders during typing (better performance + fewer bugs)
- Testable contracts (can verify mode transitions in E2E)
