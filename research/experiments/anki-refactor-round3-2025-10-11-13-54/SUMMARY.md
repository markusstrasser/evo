# Round 3 Refactoring Summary

## Overview

Analyzed 4 agent reports with 20+ suggestions. Current codebase is 579 LOC and clean.

**Verdict**: 8 clear wins, 12 rejections, 1 maybe

## Clear Wins (Apply These)

### Correctness Fixes (Priority 1)
1. **Remove redundant render! calls** (ui.cljs) - Watch already handles rendering, causing 2x renders per state change
2. **Fix IndexedDB write completion** (fs.cljs) - Currently doesn't wait for .put to complete before logging success
3. **Fix flatten bug** (fs.cljs) - Use `(into [] cat)` instead of `flatten` to avoid tearing apart maps

### Code Quality (Priority 2)
4. **Extract idb-request->promise helper** (fs.cljs) - Eliminates ~18 LOC of duplication across 3 functions
5. **Use medley/filter-vals in due-cards** (core.cljc) - More idiomatic and clear
6. **Tighten destructuring in ::rate-card** (ui.cljs) - Destructure @!state once instead of multiple lookups
7. **Separate async from pure in load-and-sync-cards!** (ui.cljs) - Makes 2 I/O ops explicit
8. **Remove unused :log []** (core.cljc) - Dead code in reduce-events initial state

**Net LOC impact**: -12 lines (579 → 567)

## Rejections (Don't Apply)

1. **Replace loop/recur with reduce** (core.cljc) - Less clear, has bug
2. **Use medley/update-existing** (core.cljc) - Hurts clarity in review event
3. **Use assoc-some in card-with-meta** (core.cljc) - No actual benefit
4. **Narrow error recovery** (fs.cljs) - Over-engineering for prototype
5. **Extract card->view helper** (ui.cljs) - Doesn't reduce complexity
6. **Create apply-state! wrapper** (ui.cljs) - Doesn't address root cause
7. **Add bang suffix to I/O functions** (fs.cljs) - Wrong convention (bang is for Clojure state mutation)
8. **Extract current-date helper** (core.cljc) - Over-abstraction for 2-char savings
9. **Shared card-type registry** (cross-cutting) - YAGNI for 3 card types
10. **medley/update-existing in apply-event** - Trading clarity for brevity
11. **assoc-some in card-with-meta** - Solving non-problem
12. **Narrow error handling in load-log** - Theoretical concern, not practical

## Maybe (Edge Case)

- **Dedupe parsed-cards with distinct-by** (ui.cljs) - Protects against duplicate cards in markdown, but probably overkill

## Key Insights

### What Makes Code "Clean"?
- **Explicitness over cleverness**: `if current-meta` is clearer than `medley/update-existing`
- **Right abstractions**: IDB helper eliminates duplication, but `current-date` doesn't
- **YAGNI applies**: 3 card types don't need a registry system

### Bugs Found
1. **Render duplication**: Watch + explicit calls = 2x rendering
2. **IndexedDB race**: Not waiting for write completion
3. **Flatten bug**: Would tear apart nested maps

### Agent Quality
- **Codex**: Good at finding patterns, sometimes over-abstracts
- **Best suggestions**: Structural improvements (IDB helper, async separation)
- **Weakest suggestions**: Micro-optimizations that add indirection

## Implementation Order

1. Remove redundant render! calls (1 min)
2. Remove :log [] dead code (30 sec)
3. Extract IDB helper + fix write completion (5 min)
4. Fix flatten bug (2 min)
5. Apply clarity improvements (5 min)

**Total time**: ~15 minutes

## Files Changed

- **core.cljc**: 2 changes (-2 LOC)
- **fs.cljs**: 2 changes (-12 LOC)
- **ui.cljs**: 3 changes (+2 LOC)

**Net**: -12 LOC, 3 correctness fixes, 5 clarity improvements
