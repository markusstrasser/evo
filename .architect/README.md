# Architecture Analysis Directory

This directory contains comprehensive analysis of the contenteditable cursor positioning challenges and various approaches explored.

## Key Documents

### Problem Analysis

- **RECURRING_PROBLEM_ANALYSIS.md** (32KB)
  - Deep dive into the 17.6% fix-commit rate
  - Three-model impedance mismatch (Kernel/Replicant/DOM)
  - Why unit tests don't catch side effect bugs
  - Recommendation: Uncontrolled edit mode with sync points

- **WHAT_WEVE_TRIED.md** (19KB)
  - Forensic analysis of attempted fixes
  - Good: `:replicant/key`, `textContent`, text selection utilities
  - Bad: `:replicant/remember`, uncontrolled, mock-text
  - In Limbo: Controlled editable (21% test failures)
  - Current state: THREE parallel implementations

### Implementation Plans

- **use-editable-extraction-plan.md** (21KB)
  - Strategy for extracting Range API utilities from use-editable library
  - Only 6% of use-editable is React-specific
  - Phase-by-phase extraction plan
  - Status: Partially done (`util.text_selection.cljs`)

- **controlled-editable-refactor-summary.md** (6.5KB)
  - Implementation notes for `src/evo/` namespace
  - MutationObserver + rollback pattern
  - Atomic text + cursor updates
  - Status: Code complete, 58/275 tests failing (21%)

### Alternative Evaluations

- **tiptap-proposal-context.md** (14KB)
  - Context for evaluating Tiptap (ProseMirror) adoption
  - Analysis of alignment with event-sourced kernel
  - CRDT considerations

- **tiptap-evaluation-summary.md** (16KB)
  - Detailed comparison of Tiptap vs use-editable extraction
  - Architecture compatibility analysis
  - Not recommended due to framework lock-in

- **tiptap-tournament-results.md** (2.2KB)
  - LLM tournament evaluation results

## Timeline of Approaches

```
Original (2024-09)
  └─> Mock-text scaffolding (~200 LOC coordinate geometry)
        └─> Cursor bugs, fragile, performance issues

Attempt 1 (2024-10)
  └─> Uncontrolled contenteditable
        └─> Failed: Breaks event sourcing

Attempt 2 (2024-11)
  └─> :replicant/remember for cursor preservation
        └─> Failed: Too blunt, broke updates

Attempt 3 (2024-11-19) ✅
  └─> Fix :replicant/key (not :key)
        └─> Success: Lifecycle hooks now fire

Attempt 4 (2024-11-20) ⚠️
  └─> Extract use-editable utilities
        └─> Partial: util.text_selection.cljs added
        └─> Not fully integrated

Attempt 5 (2024-11-20) ⚠️
  └─> Controlled editable (evo.dom.editable)
        └─> Code complete: 381 LOC
        └─> Not integrated: 21% test failures
        └─> Status: Experimental, not recommended

Current (2024-11-21)
  └─> THREE parallel implementations
        └─> Decision needed: Pick one approach
```

## Recommendations

From **RECURRING_PROBLEM_ANALYSIS.md**:

### Stop fighting the browser

The evidence (21% test failures, months of cursor bugs, 17.6% fix rate) suggests the controlled approach is the wrong abstraction.

### Recommended: Uncontrolled edit mode with sync points

```
VIEW MODE (Kernel → DOM)
  - Controlled, pure rendering
  - Kernel is source of truth
  - No contenteditable

↓ (Enter edit mode)

EDIT MODE (DOM → Kernel)
  - Uncontrolled contenteditable
  - Browser manages cursor
  - Debounced sync to kernel (500ms)
  - Final sync on blur

↓ (Exit edit mode)

VIEW MODE (Kernel → DOM)
```

**Benefits**:
- Simpler (don't fight browser)
- Better performance (no re-render on every keystroke)
- Aligns with browser behavior
- Acceptable UX (undo per session, not per character)

**Tradeoffs**:
- Can't undo individual keystrokes
- Kernel state stale during editing
- Breaks "kernel is always source of truth" purity

## Next Steps

1. **Decision**: Pick one approach and delete the others
   - Option A: Finish controlled editable (fix 58 tests) - Not recommended
   - Option B: Use text_selection + lighter refactor - Partial solution
   - Option C: Uncontrolled edit mode with sync points - **Recommended**

2. **Cleanup**: Remove dead code
   - Delete mock-text completely
   - Delete unused implementations
   - Consolidate to single approach

3. **Testing**: Update test strategy
   - Add E2E tests for cursor stability
   - Test mode transitions (view ↔ edit)
   - Verify side effects, not just data

4. **Performance**: Add editing buffer (after stability)
   - Ephemeral buffer for active editing
   - Checkpoint to kernel on blur/Enter
   - Debounced sync for autosave

## Status Summary

**Working** (Committed to main):
- ✅ `:replicant/key` fix
- ✅ `textContent` vs `element->text` fix
- ✅ Text selection utilities (`util.text_selection.cljs`)

**Experimental** (Committed but not integrated):
- ⚠️ Controlled editable (`src/evo/` - 381 LOC, 21% test failures)

**Planned** (Not started):
- ❌ Uncontrolled edit mode with sync points (recommended)
- ❌ Editing buffer for performance
- ❌ Runtime guards (`browser_guard.js`)

## Metrics

**Code churn** (last 2 months):
- 1,874 total commits
- 330 fix commits (17.6%)
- 213 cursor/focus/edit/selection commits
- `src/components/block.cljs`: 20+ commits in 30 days

**Documentation** (generated in last week):
- 1,441 lines of "gotchas" documentation
- 4 major analysis documents (this directory)
- 3,641 lines changed in merge (13 files)

**Test failures**:
- Controlled editable: 58/275 (21%)
- Current branch: Unknown (need to run)

## References

- Parent analysis: `RECURRING_PROBLEM_ANALYSIS.md`
- Forensic audit: `WHAT_WEVE_TRIED.md`
- Extraction plan: `use-editable-extraction-plan.md`
- Implementation notes: `controlled-editable-refactor-summary.md`

---

**Last updated**: 2024-11-21
**Status**: Analysis complete, decision needed
