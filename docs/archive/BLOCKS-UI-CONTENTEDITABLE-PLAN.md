# Blocks UI Contenteditable Lifecycle - Architecture Analysis & Fix Plan

**Status:** Draft
**Date:** 2025-10-24
**Problem:** Text persistence issues, race conditions, and broken navigation features in contenteditable block editing

---

## Executive Summary

The contenteditable lifecycle in `components/block.cljs` has fundamental race conditions between:
1. Browser DOM management (contenteditable's own textContent)
2. Framework reconciliation (Replicant rendering text as a child node)
3. State transitions (editing? flag toggling)

**Root Cause:** Attempting to synchronize these three independent systems through timing-based hacks (clearing textContent on blur) rather than using the architecture's abstractions correctly.

**Solution:** Lean into the existing architecture's dual-path intent system and let Replicant manage rendering consistently.

---

## Architecture Analysis

### The Dual-Path Intent System (ADR-016)

The codebase has a clean separation of concerns:

#### Path 1: View Changes (UI State Only)
```clojure
;; Defined via intent->db multimethod
;; Examples: selection, focus, edit mode
(defmethod intent/intent->db :enter-edit [db {:keys [block-id]}]
  (-> db
      (assoc-in [:view :editing] block-id)
      (assoc-in [:view :focus] block-id)))

(defmethod intent/intent->db :exit-edit [db _intent]
  (update db :view dissoc :editing))
```

**Key insight:** These return PURE DATA transformations. No side effects, no DOM manipulation.

#### Path 2: Structural Changes (Database Operations)
```clojure
;; Defined via intent->ops multimethod
;; Examples: create, update, delete nodes
(defmethod intent/intent->ops :update-content [_db {:keys [block-id text]}]
  [{:op :update-node :id block-id :props {:text text}}])
```

**Key insight:** These return OPERATIONS that flow through the kernel's transaction pipeline (normalize → validate → apply → derive).

### Component Contract

From examining `plugins/editing/core.cljc`, `plugins/selection/core.cljc`, and `plugins/navigation/core.cljc`:

**Components MUST:**
1. Read state via plugin getters: `(edit/editing-block-id db)`, `(edit/get-block-text db block-id)`
2. Dispatch intents for ALL changes: `(on-intent {:type :update-content :block-id id :text new-text})`
3. Trust the framework to re-render based on state changes

**Components MUST NOT:**
1. Manipulate DOM directly (except via event handlers responding to user input)
2. Cache state in local atoms or refs
3. Make assumptions about timing of state changes

---

## Current Implementation Problems

### Problem 1: Text Reversal & Duplication

**Symptom:** Text appears backwards or duplicates when transitioning between edit/non-edit modes.

**Root Cause:** Fighting the framework by having TWO sources of truth:
```clojure
[:span.content
 {:contentEditable true
  :suppressContentEditableWarning true}
 ;; React child (only when not editing)
 (when-not editing? text)]
```

When `editing?` is false, Replicant renders `text` as a child. But if contenteditable's own textContent wasn't cleared, there are now TWO text sources:
1. The contenteditable element's internal textContent
2. The Replicant child node

Replicant's reconciliation sees the child and tries to sync it with the DOM, causing conflicts.

### Problem 2: Blur Handler Timing

Current blur handler (line 248-251 in block.cljs):
```clojure
:blur (fn [e]
        ;; Exit edit mode - text child will render on next frame
        ;; Don't clear textContent here - it causes race conditions
        (on-intent {:type :exit-edit}))
```

**The race condition:**
1. User clicks away from block A to block B
2. Block A fires blur event → dispatches `:exit-edit` intent
3. State updates: `[:view :editing]` is removed
4. Replicant re-renders: `(when-not editing? text)` evaluates to true
5. Replicant adds text child to contenteditable
6. But contenteditable STILL has its own textContent from editing
7. Result: DUPLICATION or text disappears depending on reconciliation order

### Problem 3: Manual DOM Manipulation

Escape handler (line 125-129):
```clojure
(defn handle-escape [e db block-id on-intent]
  (.preventDefault e)
  ;; Clear contenteditable BEFORE exiting edit mode
  (set! (-> e .-target .-textContent) "")
  (on-intent {:type :exit-edit}))
```

This is a CODE SMELL. We're manually manipulating DOM because we don't trust the framework to do it correctly. This suggests the abstraction is leaking.

---

## The Logseq Pattern (Reference Implementation)

From examining `~/Projects/best/logseq/src/main/frontend/components/editor.cljs`:

**Key observations:**
1. Logseq uses contenteditable with `dangerouslySetInnerHTML` for editing mode
2. When not editing, they render static markup (NOT contenteditable)
3. They DON'T try to synchronize contenteditable's content with framework state during editing
4. Text updates flow through a buffer/atom that batches changes before dispatching to database
5. Cursor position is tracked separately from content

**Critical insight:** Logseq treats contenteditable as an EXTERNAL SYSTEM during editing, not as framework-managed DOM. The content flows:
- **User types** → contenteditable updates itself → onChange event → update buffer
- **Buffer persists** → dispatch operation → DB updates
- **Exit edit** → contenteditable unmounts, static view mounts

They NEVER try to have the framework render children into a contenteditable element.

---

## Proposed Solution

### Strategy: Separate Edit and View Rendering

Instead of conditional rendering WITHIN contenteditable, render DIFFERENT ELEMENTS for edit vs view:

```clojure
;; Current (WRONG):
[:span.content
 {:contentEditable true}
 (when-not editing? text)]  ; ← Fighting Replicant

;; Proposed (RIGHT):
(if editing?
  ;; Edit mode: contenteditable manages itself
  [:span.content-edit
   {:contentEditable true
    :suppressContentEditableWarning true
    :ref (fn [elem]
           ;; Set initial content ONCE on mount
           (when elem
             (when (empty? (.-textContent elem))
               (set! (.-textContent elem) text))))
    :on {:input (fn [e] ...)
         :blur (fn [e]
                 ;; Just exit edit mode - unmounting handles cleanup
                 (on-intent {:type :exit-edit}))
         :keydown (fn [e] ...)}}]

  ;; View mode: static text (NOT contenteditable)
  [:span.content-view
   {:on {:click (fn [e]
                  ;; Enter edit mode
                  (on-intent {:type :enter-edit :block-id block-id}))}}
   text])
```

**Why this works:**
1. **No synchronization needed** - When `editing?` changes, Replicant unmounts one element and mounts the other
2. **No race conditions** - Each mode has its own element with its own lifecycle
3. **Framework-aligned** - Replicant manages mounting/unmounting, we just provide different elements
4. **Separation of concerns** - View mode is Replicant-managed, edit mode is contenteditable-managed

### Focus Management

When entering edit mode, we need to focus and position cursor:

```clojure
[:span.content-edit
 {:contentEditable true
  :ref (fn [elem]
         (when elem
           ;; Set initial content
           (when (empty? (.-textContent elem))
             (set! (.-textContent elem) text))

           ;; Focus and position cursor at end
           (.focus elem)
           (let [range (.createRange js/document)
                 sel (.getSelection js/window)]
             (.selectAllChildren range elem)
             (.collapseToEnd range)
             (.removeAllRanges sel)
             (.addRange sel range))

           ;; Initialize mock-text for cursor detection
           (update-mock-text! text)))
  :on {...}}]
```

### Handling Navigation Boundaries

The arrow up/down handlers already use `detect-cursor-row-position` which relies on mock-text. This should continue working because:
1. Mock-text is updated on every `:input` event
2. Cursor detection happens in keydown handler BEFORE navigation
3. Navigation dispatches `:navigate-up` or `:navigate-down` intents
4. Intent changes focus → triggers re-render → new block enters edit mode

**No changes needed to navigation logic** - the bug is in the rendering lifecycle, not navigation.

---

## Implementation Plan

### Phase 1: Fix Rendering (Core Issue)

**Goal:** Eliminate text persistence issues and duplication by separating edit/view rendering.

**Changes:**
1. Modify `components/block.cljs` lines 222-255:
   - Replace single contenteditable with conditional edit/view elements
   - Move ref initialization logic to edit mode element
   - Remove manual textContent clearing from escape handler
   - Simplify blur handler (just dispatch `:exit-edit`)

2. Test criteria:
   - Click between blocks: text persists in all blocks ✅
   - Enter key: creates new block and focuses it ✅
   - Escape key: exits edit mode without duplication ✅
   - Tab/Shift+Tab: indent/outdent works ✅

**Estimated time:** 1-2 hours (implementation + testing)

### Phase 2: Verify Navigation (Should Already Work)

**Goal:** Confirm arrow key navigation at boundaries works correctly.

**Testing:**
1. Multi-line block: arrow up/down within block (should move cursor)
2. Single-line block: arrow up (should navigate to previous block)
3. Single-line block: arrow down (should navigate to next block)
4. First block: arrow up (should do nothing or wrap)
5. Last block: arrow down (should do nothing or wrap)

**Expected outcome:** Navigation already works because we're not changing navigation logic, only fixing rendering race conditions that broke it.

### Phase 3: Verify Keyboard Shortcuts

**Goal:** Ensure all keyboard shortcuts work as documented in hotkeys reference.

**Test matrix:**

| Shortcut | Expected | Status |
|----------|----------|--------|
| ↑/↓ | Navigate at boundary | 🔍 Verify |
| Enter | New block | ✅ Works |
| Shift+Enter | Newline | 🔍 Verify |
| Backspace | Delete/merge | 🔍 Verify |
| Tab | Indent | ✅ Works |
| Shift+Tab | Outdent | 🔍 Verify |
| Esc | Exit edit | 🔍 Verify |
| Cmd+Z | Undo | 🔍 Verify |
| Cmd+Shift+Z | Redo | 🔍 Verify |
| Alt+↑/↓ | Move block | 🔍 Verify |
| Shift+Click | Multi-select | 🔍 Verify |

### Phase 4: Run Playwright Tests

**Goal:** Systematic verification of all features.

**Process:**
1. Update test selectors if needed (we changed CSS classes)
2. Update test expectations if needed (we changed title)
3. Run full test suite: `npx playwright test`
4. Fix any remaining issues revealed by tests

**Acceptance criteria:** All 13 tests passing.

### Phase 5: Cleanup & Documentation

**Goal:** Remove technical debt and document the pattern.

**Tasks:**
1. Remove commented-out code and TODOs
2. Add architectural comments explaining edit/view separation
3. Update component docstring with rendering contract
4. Add example usage in docstring

---

## Testing Strategy

### 1. Test-Driven Fix (Recommended)

**Before making ANY changes:**
```bash
# Capture current state
npx playwright test --reporter=list > before-fix.txt

# Make Phase 1 changes

# Verify improvement
npx playwright test --reporter=list > after-fix.txt
diff before-fix.txt after-fix.txt
```

**Goal:** Ensure we're not breaking MORE things. Every change should move the needle from "9 failing" toward "0 failing".

### 2. Manual Spot Checks

After each phase, manually verify in browser:
1. Type in first block
2. Press Enter (creates new block)
3. Type in new block
4. Click back to first block
5. Verify both blocks still show correct text
6. Press Escape
7. Verify text still visible (not duplicated)

### 3. REPL Verification

For data flow verification:
```clojure
;; In REPL
@!db  ; Check current state
(:view @!db)  ; Check editing/focus state
(get-in @!db [:nodes "a" :props :text])  ; Check block content
```

---

## Rollback Plan

If fixes cause more breakage:

```bash
# View file at last known good commit
git log --oneline | head -20
git show <commit>:src/components/block.cljs > block.cljs.backup

# Or revert specific file
git checkout HEAD~5 -- src/components/block.cljs
```

**Last known good:** Before this session, blocks UI was working (per user: "the stuff that used to work doesn't anymore").

Commits to check:
- `f225b57` - "fix(blocks-ui): implement Logseq-style editing mode behavior"
- `b5cc105` - "feat(blocks-ui): add Enter key, text editing, and Up/Down navigation"

---

## Open Questions

### Q1: Should we use `:split-at-cursor` for Enter key?

**Current:** Enter key uses `:create-and-place` intent (creates empty block after current)

**Alternative:** Use `:split-at-cursor` intent (splits current block's text)

**Decision:** Check Logseq behavior. If Enter splits text, use `:split-at-cursor`. If it always creates empty block, keep current.

### Q2: Should Alt+Up/Down move blocks or navigate?

**Current implementation:** Likely tries to navigate (based on user complaint)

**Logseq behavior:** Alt+Up/Down moves blocks (reorders children)

**Decision:** Need to add reorder intent handlers. Check `plugins/struct/core.cljc` lines 88-129 for `:reorder/move-blocks` intent.

### Q3: Should we preserve cursor position on navigation?

**Current:** Cursor jumps to end when navigating between blocks

**Logseq:** Attempts to maintain horizontal position (column) when moving up/down

**Decision:** Phase 2 enhancement - use mock-text to track cursor column and restore it.

---

## Success Criteria

**Minimum viable (MVP):**
- ✅ Text persists when clicking between blocks
- ✅ No duplication on edit/view transitions
- ✅ No text reversal
- ✅ Enter, Escape, Tab work consistently
- ✅ Arrow up/down navigation works at boundaries

**Full success:**
- ✅ All 13 Playwright tests passing
- ✅ All keyboard shortcuts documented in hotkeys reference work
- ✅ No console errors or warnings
- ✅ Undo/redo works for all operations

**Stretch goals:**
- ✅ Alt+Up/Down block reordering
- ✅ Cursor position preservation on navigation
- ✅ Shift+Enter for newlines within block
- ✅ Backspace at start merges with previous

---

## Architectural Lessons

### What Worked

1. **Dual-path intent system** - Clean separation of view vs structural changes
2. **Plugin getters** - Centralized state access prevents inconsistencies
3. **Multimethod dispatch** - Extensible intent handling without modifying core
4. **Derived indexes** - Pre-computed relationships (prev/next) make navigation trivial

### What Didn't Work

1. **Fighting the framework** - Trying to synchronize contenteditable with Replicant rendering
2. **Manual DOM manipulation** - Setting textContent directly breaks framework assumptions
3. **Timing-based fixes** - setTimeout, "clear before blur", etc. are red flags
4. **Conditional rendering within contenteditable** - Creates ambiguity about who owns the content

### The Core Principle

> When you find yourself fighting the framework, step back and ask:
> "Am I trying to make the framework do something it wasn't designed to do?"

In this case: YES. Replicant expects to own the DOM tree. Contenteditable expects to own its content. We tried to make them share ownership. The solution is to give each exclusive ownership in different modes.

---

## Next Steps

1. **Review this plan** with fresh eyes (or another AI agent)
2. **Start with Phase 1** - Fix the core rendering issue
3. **Test rigorously** - Playwright tests BEFORE and AFTER each change
4. **Iterate carefully** - One phase at a time, verify each works before proceeding
5. **Document learnings** - Update this plan as we discover edge cases

**Key principle:** SLOW IS SMOOTH, SMOOTH IS FAST. Better to take 3 hours and get it right than to rush and break things 5 times.

---

## Appendix: Code Snippets

### Current Problematic Rendering (block.cljs:222-255)

```clojure
;; Contenteditable span
;; CRITICAL: Only render text when NOT editing to avoid DOM conflicts
[:span.content
 {:contentEditable true
  :suppressContentEditableWarning true
  :style {:outline "none"
          :min-width "1px"
          :display "inline-block"}
  :data-block-id block-id
  :on {:input (fn [e]
                (let [new-text (-> e .-target .-textContent)]
                  ;; Update mock-text for cursor detection
                  (update-mock-text! new-text)
                  ;; Update block text (structural change via intent)
                  (on-intent {:type :update-content
                              :block-id block-id
                              :text new-text})))
       :focus (fn [e]
                ;; Enter edit mode AND set initial content
                (on-intent {:type :enter-edit :block-id block-id})
                ;; Ensure element has text content on focus
                (let [target (.-target e)]
                  (when (empty? (.-textContent target))
                    (set! (.-textContent target) text)))
                ;; Initialize mock-text
                (update-mock-text! (-> e .-target .-textContent)))
       :blur (fn [e]
               ;; Exit edit mode - text child will render on next frame
               ;; Don't clear textContent here - it causes race conditions
               (on-intent {:type :exit-edit}))
       :keydown (fn [e]
                  (handle-keydown e db block-id on-intent))}}
 ;; Only render text as child when NOT in edit mode
 (when-not editing? text)]
```

### Proposed Fix (Separate Edit/View Elements)

```clojure
;; Conditional rendering: different elements for different modes
(if editing?
  ;; EDIT MODE: contenteditable element (framework doesn't manage content)
  [:span.content-edit
   {:contentEditable true
    :suppressContentEditableWarning true
    :style {:outline "none"
            :min-width "1px"
            :display "inline-block"}
    :data-block-id block-id
    :ref (fn [elem]
           (when elem
             ;; Set initial content ONCE on mount
             (when (empty? (.-textContent elem))
               (set! (.-textContent elem) text))

             ;; Focus and position cursor at end
             (.focus elem)
             (let [range (.createRange js/document)
                   sel (.getSelection js/window)]
               (.selectAllChildren range elem)
               (.collapseToEnd range)
               (.removeAllRanges sel)
               (.addRange sel range))

             ;; Initialize mock-text for cursor detection
             (update-mock-text! text)))
    :on {:input (fn [e]
                  (let [new-text (-> e .-target .-textContent)]
                    (update-mock-text! new-text)
                    (on-intent {:type :update-content
                                :block-id block-id
                                :text new-text})))
         :blur (fn [e]
                 ;; Just exit edit mode - unmounting cleans up
                 (on-intent {:type :exit-edit}))
         :keydown (fn [e]
                    (handle-keydown e db block-id on-intent))}}]

  ;; VIEW MODE: static span (framework-managed, NOT contenteditable)
  [:span.content-view
   {:style {:min-width "1px"
            :display "inline-block"
            :cursor "text"}
    :data-block-id block-id
    :on {:click (fn [e]
                  ;; Enter edit mode on click
                  (.stopPropagation e)
                  (on-intent {:type :enter-edit :block-id block-id}))}}
   text])
```

### Simplified Escape Handler

```clojure
(defn handle-escape [e db block-id on-intent]
  (.preventDefault e)
  ;; Just exit edit mode - element will unmount, view will mount
  (on-intent {:type :exit-edit}))
```

No more manual DOM manipulation!

---

**END OF PLAN**
