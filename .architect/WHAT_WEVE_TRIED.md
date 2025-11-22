# What We've Actually Tried: A Forensic Analysis

## Current State (as of 2025-11-21)

**Branch**: `feature/editable-text-selection`
**Status**: Mixed success - some fixes work, architecture partially implemented
**Test Status**: Unknown (need to run tests)
**Working**: `:replicant/key` fix, `textContent` vs `element->text` fix
**In Progress**: Controlled editable with Range API (partially implemented)

---

## The Attempts: Good, Bad, and Secret Third Thing

### ✅ GOOD: Fixes That Actually Worked

#### 1. **Fix: `:key` → `:replicant/key`** (Commit e870374)

**What**: Changed element keys from `:key` to `:replicant/key` in block.cljs

**Why it worked**:
- Replicant's `reusable?` function only checks `:replicant/key`, not `:key`
- Using wrong key caused element reuse instead of recreation
- `:on-mount` never fired → MutationObserver never set up → focus broken

**Evidence of success**:
```
Console before: {"life-cycle":"update"} ❌
Console after:  {"life-cycle":"mount"}  ✅
```

**Impact**: Fundamental fix. Without this, ALL lifecycle-based solutions fail silently.

**Why it took so long to discover**:
- No error message (silent failure)
- Requires reading Replicant source code
- Not well-documented in Replicant docs

**Lesson**: Framework escape hatches (lifecycle hooks) require deep understanding of framework internals.

---

#### 2. **Fix: `textContent` instead of `element->text` for input handlers** (Commit 03bc808)

**What**: Changed input handler from `element->text` to `.textContent` directly

**Why it worked**:
```clojure
;; element->text adds trailing \n (for contenteditable quirk)
(element->text node) ;; => "Hello\n"

;; textContent is raw DOM content
(.-textContent node) ;; => "Hello"
```

**Impact**: Fixed off-by-one cursor positioning bugs

**Files changed**: `src/components/block.cljs` (3 lines)

**Why we needed this**: `element->text` was designed for cursor positioning with `make-range` (which expects trailing \n), not for simple text extraction.

**Lesson**: Don't reuse functions designed for different purposes. `element->text` is for DOM traversal with Range API, not input handling.

---

#### 3. **Add: Text selection utilities from `use-editable`** (Commit 64a72cf)

**What**: Created `src/util/text_selection.cljs` with ported utilities from use-editable

**Functions added**:
- `element->text` - DOM traversal for plain text
- `get-position` - Extract cursor position from Range API
- `make-range` - Create Range at character positions
- `move-cursor!` - Position cursor programmatically
- `get-state` - Complete editor state

**Status**: ✅ Implemented, committed, currently on branch

**Files**:
- `src/util/text_selection.cljs` (262 lines)
- `test/util/text_selection_test.cljs` (15 lines - placeholder)
- `test/e2e/text-selection.spec.js` (300 lines - E2E tests)
- `docs/TEXT_SELECTION.md` (280 lines - documentation)

**Integration**: Partially integrated into `block.cljs` (lines 57 mentions, but not fully used)

**Why it's good**:
- Battle-tested code from production library
- Handles complex DOM structures (text nodes, BR elements)
- Framework-agnostic (pure DOM APIs)
- Comprehensive (covers full text selection lifecycle)

**Current problem**: Implemented but not fully integrated. We added the utilities but didn't remove the old mock-text system.

---

### ❌ BAD: Approaches That Failed

#### 1. **Failed: `:replicant/remember` for cursor preservation** (Commit 4b54da0, reverted)

**What**: Tried using Replicant's `:replicant/remember` to preserve cursor across renders

**Why it failed**:
- `:replicant/remember` prevents re-renders entirely
- Breaks intentional updates (text changes, mode switches)
- Creates stale DOM state

**From docs/specs/CURSOR_GUARD_FLAG.md**:
> "`:replicant/remember` is too blunt an instrument. We need selective updates, not no updates."

**Reverted in**: Next commit (bba473a)

**Lesson**: Replicant's built-in memoization doesn't distinguish between "cursor-only updates" and "text updates". Need custom logic.

---

#### 2. **Failed: Uncontrolled pattern** (Earlier, reverted)

**What**: Let contenteditable be fully uncontrolled, browser manages all state

**Why it failed**:
- Kernel state and DOM state desync
- Can't implement undo/redo (no operation log)
- Can't programmatically set cursor
- Violates "kernel is source of truth" architecture

**When reverted**: Before current branch (commit history unclear)

**Lesson**: Uncontrolled breaks event sourcing. Can't have it both ways.

---

#### 3. **Failed: Mock-text scaffolding** (Original implementation)

**What**: Hidden `<div class="mock-text">` with identical content, measure coordinates to detect cursor position

**Code location**: `src/components/block.cljs` (functions now deleted in evo/dom/editable.cljs branch)

**Why it was bad**:
- ~200 LOC of complex coordinate geometry
- Fragile (breaks on font changes, zoom, etc.)
- Performance overhead (duplicate DOM tree)
- Doesn't work with multi-line correctly
- Source of cursor jumping bugs

**Status**: Still present on main branch, removed in experimental branches

**From .architect/tiptap-proposal-context.md**:
> "The mock-text system is the root cause of cursor instability. It's trying to infer cursor position from coordinates, which is fundamentally unreliable."

**Lesson**: Don't reinvent what the browser already provides (Range API). Coordinate geometry is wrong abstraction.

---

### 🔶 SECRET THIRD THING: Partially Implemented / In Limbo

#### 1. **Partial: Controlled editable with MutationObserver** (src/evo/dom/editable.cljs)

**What**: Full implementation of controlled contenteditable using MutationObserver + rollback pattern

**Status**: ✅ Code written, ⚠️ Not fully integrated, ❓ Tests unknown

**Files created**:
- `src/evo/dom/editable.cljs` (239 lines) - DOM driver with MutationObserver
- `src/evo/text.cljs` (144 lines) - Pure text operations engine
- `.architect/controlled-editable-refactor-summary.md` - Implementation notes

**Architecture**:
```
User types → DOM mutates → Observer fires
  ↓
Extract new state from DOM
  ↓
Rollback DOM to last known state
  ↓
Dispatch onChange(text, cursor)
  ↓
Parent updates kernel DB
  ↓
Re-render applies DB state to DOM
```

**Key innovation**: **Atomic text + cursor updates**
```clojure
;; Old (split state)
{:type :update-content :text "hello"}
;; Cursor managed separately by browser ❌

;; New (atomic)
{:type :update-content
 :text "hello"
 :cursor-pos {:anchor 5 :head 5}}
;; DB owns both ✅
```

**From controlled-editable-refactor-summary.md**:
> **Testing Status**
> Total Tests: 275
> Failures: 58 (21%)
> Expected: Yes - fundamental architecture change

**Why partial**:
1. Code written and committed to `src/evo/`
2. But NOT integrated into `src/components/block.cljs` fully
3. Tests need updating (58 failures)
4. Old mock-text still present in some places

**What's blocking**:
- Need to update all tests to include `:cursor-pos` parameter
- Need to verify navigation logic works with new cursor detection
- Need to remove old mock-text completely
- Integration work between evo.dom.editable and block.cljs

---

#### 2. **Partial: use-editable extraction** (Planned but not executed)

**Plan documented in**: `.architect/use-editable-extraction-plan.md`

**Status**:
- ✅ Analysis complete (only 6% of use-editable is React-specific)
- ✅ Extraction strategy defined
- ⚠️ **Partially done** - `util.text_selection.cljs` has some functions
- ❌ Full extraction not completed
- ❌ `evo.dom.editable.cljs` reimplements instead of extracting

**What we extracted**: Core Range API utilities to `util.text_selection.cljs`

**What we didn't extract**: Event handlers, state management, MutationObserver pattern

**What we reimplemented**: `evo.dom.editable.cljs` reimplements MutationObserver pattern independently

**Confusion**: We have THREE implementations now:
1. `util.text_selection.cljs` - Range API utilities (partial use-editable)
2. `evo.dom.editable.cljs` - Full controlled editable (custom implementation)
3. Old mock-text in `block.cljs` (original implementation, partially removed)

**Why this happened**: Multiple attempts in parallel, unclear which approach to commit to.

---

#### 3. **Plan: Editing buffer** (Proposed, not implemented)

**Documented in**: `dev/editing-ux-proposal.md` (mentioned in the plan you shared)

**Idea**:
- Ephemeral buffer in session state for active editing
- Keystrokes dispatch `:buffer-edit` (skip history + derivation)
- Checkpoint to canonical tree on blur/Enter/debounce

**Status**: ❌ Not implemented at all

**Why it makes sense**:
- Solves performance (every keystroke through full kernel is slow)
- Solves undo granularity (undo per edit session, not per character)
- Aligns with "edit mode vs view mode" separation

**Why it's not done**: Focused on fixing cursor bugs first, haven't gotten to performance optimization

---

## Current Branch Analysis: `feature/editable-text-selection`

### What's Actually In This Branch

From git log (last 10 commits):
1. `2125287` - docs: Add :replicant/key gotcha
2. `340b328` - docs: Recover testing guides
3. `662b440` - docs: Comprehensive :replicant/key guide
4. `e870374` - **FIX**: Use :replicant/key for lifecycle
5. `af9bdaf` - docs: Text selection gotcha
6. `473482b` - **FIX**: Test helper uses double-click
7. `03bc808` - **FIX**: Use textContent not element->text
8. `64a72cf` - **FEAT**: Add text selection utilities from use-editable
9. `419151c` - docs: DX improvements

### Modified Files (unstaged)
```
modified:   public/output.css
modified:   src/plugins/editing.cljc
modified:   src/plugins/selection.cljc
modified:   src/shell/blocks_ui.cljs
```

### Untracked Files
```
.architect/  (4 analysis docs)
src/evo/     (dom/editable.cljs + text.cljs)
```

**Interpretation**: We've done some fixes (commits 3-8), added utilities (commit 8), but the full refactor (evo namespace) is uncommitted and probably incomplete.

---

## The Confusion: Multiple Parallel Approaches

### Timeline of Approaches

```
Original → Mock-text system (coordinate geometry)
             ↓ (problems discovered)

Attempt 1 → Uncontrolled contenteditable
             ↓ (breaks event sourcing)

Attempt 2 → :replicant/remember
             ↓ (too blunt, breaks updates)

Attempt 3 → Use :replicant/key correctly ✅
             ↓ (partial fix, lifecycle works now)

Attempt 4 → Extract use-editable utilities
             ↓ (partial - added util.text_selection.cljs)

Attempt 5 → Full controlled editable (evo.dom.editable)
             ↓ (code written, not fully integrated)

Current → Stuck between Attempt 4 and 5
```

### Why We're Stuck

1. **Success of Attempt 3** (`:replicant/key`) gave quick wins
   - Fixed immediate lifecycle problems
   - Reduced urgency to finish full refactor
   - But didn't solve underlying architecture issue

2. **Parallel work** on utilities (Attempt 4) and full refactor (Attempt 5)
   - `util.text_selection.cljs` added in Attempt 4
   - `evo.dom.editable.cljs` written in Attempt 5
   - Not clear which to use or how to integrate both

3. **Test failures** (58/275 = 21%) from Attempt 5
   - Stopped progress on controlled editable
   - Unclear if failures are fixable or architectural

4. **No clear decision** on which approach to commit to:
   - Option A: Finish controlled editable (evo.dom.editable) + fix tests
   - Option B: Use util.text_selection utilities + lighter refactor
   - Option C: Different approach entirely

---

## What Actually Works Right Now

### On This Branch (feature/editable-text-selection)

**Working fixes**:
1. ✅ `:replicant/key` instead of `:key` → lifecycle hooks fire correctly
2. ✅ `textContent` instead of `element->text` → no off-by-one cursor bugs
3. ✅ Text selection utilities available (util.text_selection namespace)

**Partially working**:
- ⚠️ Cursor positioning mostly works (using text_selection utilities)
- ⚠️ Focus management works (using lifecycle hooks)
- ⚠️ MutationObserver pattern implemented but not integrated

**Still broken**:
- ❌ Cursor still jumps in some cases (mock-text not fully removed)
- ❌ Navigation tests failing (need updates for new architecture)
- ❌ Integration between old and new code incomplete

### In .architect/ (Uncommitted)

**Implemented**:
- `src/evo/dom/editable.cljs` - Full MutationObserver + rollback
- `src/evo/text.cljs` - Pure text operations

**Status**: Compiles, runs, but has test failures

**From controlled-editable-refactor-summary.md**:
> ✅ Clean compilation with 0 warnings
> ✅ All syntax correct
> ✅ Shadow-cljs watch running successfully
> ❌ 58/275 tests failing

---

## The Plan You Shared: What Aligns / What Doesn't

### ✅ Already Done (From the plan)

> **1. Implement the Range API (The use-editable Extraction)**
> - Ensure the core utilities are fully ported

**Status**: ✅ Done in `util.text_selection.cljs` (commit 64a72cf)

BUT: Not fully integrated. We added utilities but didn't remove old mock-text.

---

> **Crucially, DELETE the ~200 LOC related to update-mock-text!**

**Status**: ⚠️ Partially done in `evo.dom.editable.cljs` branch, but not committed

From controlled-editable-refactor-summary.md:
> **Removed** old mock-text functions:
> - `update-mock-text!` (24-54) - Dead code, never called
> - `get-mock-text-tops` (67-78) - Dead code
> - Old `detect-cursor-row-position` (80-114) - Replaced

This deletion is in uncommitted work, not on the current branch.

---

> **Audit the Rollback Pattern**

**Status**: ✅ Implemented in `evo.dom.editable.cljs`

```clojure
(defn setup-controlled-editable! [element on-change initial-text initial-cursor]
  (let [observer (js/MutationObserver.
                   (fn [mutations]
                     ;; Read new state from DOM
                     (let [new-text (extract-text element)
                           new-cursor (get-position element)]

                       ;; Rollback DOM to last known state
                       (set! (.-textContent element) @!last-text)
                       (apply-selection! element @!last-cursor)

                       ;; Notify parent
                       (on-change new-text new-cursor))))])
    (.observe observer element {...})))
```

Pattern is correct: Read → Rollback → Dispatch → Re-render applies change.

---

### ❌ Not Done (From the plan)

> **2. Implement the Editing Buffer (Performance and Stability)**

**Status**: ❌ Not started

**Why**: Focused on correctness (cursor bugs) before performance

---

> **3. Enhance Debuggability with Runtime Guards**
> - Activate `browser_guard.js` by Default

**Status**: ❓ Unknown if `dev/browser_guard.js` exists

Need to check: `ls dev/browser_guard.js`

---

> **4. Refine and Enforce Linting**

**Status**: ⚠️ Partially done

We have:
- Pre-commit hooks
- Custom kondo hooks in `.clj-kondo/hooks/ui_patterns.clj` (mentioned in plan)

BUT: Unclear if they're enforced in CI or if they catch the cursor bugs.

---

## The Actual Problem: Architecture Indecision

### We Have Three Implementations

1. **Old** (mock-text) - Partially removed, still on main
2. **New-ish** (`util.text_selection`) - Committed, partially used
3. **New** (`evo.dom.editable`) - Written, uncommitted, 21% test failures

### Decision Point: Which Path Forward?

#### Option A: Finish `evo.dom.editable` Refactor

**Pros**:
- Most architecturally sound (controlled, atomic, single source of truth)
- Code already written (~400 LOC)
- Clear separation (evo.text pure, evo.dom.editable DOM driver)

**Cons**:
- 58 tests failing (21%)
- Need to update all call sites
- Need to update all tests
- Significant integration work

**Estimate**: 2-3 days to fix tests + integrate

---

#### Option B: Use `util.text_selection` + Lighter Refactor

**Pros**:
- Already committed and partially working
- Less breaking changes
- Incremental approach

**Cons**:
- Doesn't solve fundamental architecture (still split text/cursor state)
- Mock-text still partially present
- Will still have race conditions

**Estimate**: 1 day to finish integration, but doesn't solve root problem

---

#### Option C: Hybrid - Accept Uncontrolled Edit Mode

From my RECURRING_PROBLEM_ANALYSIS.md recommendation:

**Two-mode architecture**:
- **VIEW MODE**: Kernel → DOM (controlled, pure rendering)
- **EDIT MODE**: DOM is source of truth (uncontrolled contenteditable)

**Sync points**:
- Enter edit: Kernel → DOM (one-time cursor setup)
- Typing: DOM manages (browser handles)
- Exit edit: DOM → Kernel (final sync)
- Debounced: Periodic sync for autosave

**Pros**:
- Simpler (don't fight browser)
- Less code (no MutationObserver complexity)
- Better performance (no re-render on every keystroke)
- Aligns with browser behavior

**Cons**:
- Can't undo individual keystrokes (only edit sessions)
- Kernel state stale during editing
- Breaks "kernel is always source of truth" purity

**Estimate**: 1-2 days to implement + test

---

## What We Should Actually Do

Based on evidence:

### Immediate (1 day)

1. **Commit or discard** `src/evo/` changes
   - If keeping: Fix 58 test failures
   - If discarding: Clean up uncommitted work

2. **Choose one implementation**:
   - Either `evo.dom.editable` (controlled)
   - OR `util.text_selection` + uncontrolled edit mode
   - NOT both simultaneously

3. **Delete dead code**:
   - Remove mock-text completely (if going with Range API)
   - Remove unused text_selection utilities (if going with evo.dom.editable)

### Short-term (3-5 days)

4. **Finish integration** of chosen approach
   - Update all call sites
   - Fix all tests
   - Update documentation

5. **Add missing tests**:
   - E2E tests for cursor stability
   - Integration tests for mode transitions
   - Unit tests for text operations (if using evo.text)

### Medium-term (1-2 weeks)

6. **Implement editing buffer** (if still needed after refactor)
7. **Add runtime guards** (browser_guard.js)
8. **Enforce linting** in CI

---

## Summary: Good, Bad, Secret Third Thing

### ✅ GOOD (Working)
1. `:replicant/key` fix (committed)
2. `textContent` vs `element->text` fix (committed)
3. Text selection utilities added (committed but not fully used)

### ❌ BAD (Failed)
1. `:replicant/remember` approach (reverted)
2. Uncontrolled contenteditable (reverted)
3. Mock-text system (original, being removed)

### 🔶 SECRET THIRD THING (Limbo)
1. Controlled editable (`evo.dom.editable`) - Code written, 21% test failures, not committed
2. use-editable extraction - Partial (utilities yes, full extraction no)
3. Editing buffer - Planned, not started
4. Browser guards - Unknown status

### 🎯 THE REAL PROBLEM

**We're stuck between two architectures without committing to either**:
- Controlled (evo.dom.editable) - written but not finished
- Uncontrolled hybrid - not started but may be better approach

**We need to**: Pick one, finish it, delete the other.

**Recommendation**: Try Option C (uncontrolled edit mode with sync points) because:
1. Simpler implementation
2. Better performance
3. Aligns with browser behavior
4. Less code to maintain
5. Acceptable UX tradeoffs (undo per session vs per character)

The controlled approach (evo.dom.editable) is architecturally pure but fighting the browser is causing the 21% test failures and ongoing bugs.
