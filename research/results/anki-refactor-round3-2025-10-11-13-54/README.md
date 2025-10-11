# Anki Refactoring Round 3 - Critical Analysis

**Date**: 2025-10-11
**Current LOC**: 579 (core.cljc: 183, fs.cljs: 206, ui.cljs: 190)
**Agents**: 4 (Codex high reasoning)
**Suggestions**: 20+
**Accepted**: 8
**Rejected**: 12

---

## Documents

1. **SUMMARY.md** - Executive summary (start here)
2. **CRITICAL_ANALYSIS.md** - Detailed evaluation of every suggestion
3. **ACTION_ITEMS.md** - Step-by-step implementation guide
4. **agent-*.md** - Original agent reports

---

## Quick Results

### ✅ Apply (8 suggestions)

**Correctness fixes (Priority 1):**
1. Remove redundant render! calls (2x rendering bug)
2. Fix IndexedDB write completion (race condition)
3. Fix flatten bug (tears apart nested maps)

**Code quality (Priority 2):**
4. Extract idb-request->promise helper (-12 LOC)
5. Use medley/filter-vals in due-cards (+clarity)
6. Tighten destructuring in ::rate-card (+clarity)
7. Separate async from pure in load-and-sync-cards! (+clarity)
8. Remove unused :log [] (dead code)

**Net impact**: -12 LOC, 3 bug fixes, 5 clarity improvements

### ❌ Reject (12 suggestions)

- Replace loop/recur with reduce (less clear, has bug)
- Use medley/update-existing (hurts clarity)
- Use assoc-some (no benefit)
- Narrow error recovery (over-engineering)
- Extract card->view helper (doesn't help)
- Create apply-state! wrapper (wrong fix)
- Add bang suffix to I/O (wrong convention)
- Extract current-date helper (over-abstraction)
- Shared card-type registry (YAGNI for 3 types)
- Other micro-optimizations

---

## Key Insights

### What Makes Code Clean?

1. **Explicitness over cleverness**: `if current-meta` beats `medley/update-existing`
2. **Right abstractions**: IDB helper (used 3x) is good, `current-date` (saves 2 chars) is not
3. **YAGNI applies**: 3 card types don't justify a registry system

### Bug Types Found

1. **Render duplication**: Watch + explicit calls = double rendering
2. **Async race**: Not waiting for IndexedDB write completion
3. **Data structure bug**: `flatten` tears apart nested maps

### Agent Performance

**Codex (high reasoning)**:
- ✅ Good at finding structural patterns
- ✅ Identifies duplication well
- ⚠️ Sometimes over-abstracts
- ❌ Occasional bugs in proposed code (parse-qa-multiline reduce had bug)

**Best suggestions**: IDB helper extraction, async/pure separation
**Weakest suggestions**: Micro-optimizations, wrong conventions (bang suffix)

---

## Implementation

**Time estimate**: 15-17 minutes
**Testing**: Run `npm test` after each change
**Files modified**: core.cljc, fs.cljs, ui.cljs

See **ACTION_ITEMS.md** for step-by-step guide.

---

## Methodology

For each suggestion, evaluated:
1. Is it ACTUALLY better than current code?
2. Does it reduce or increase cognitive load?
3. What's the trade-off?
4. Verdict: Apply / Reject / Maybe

**Ruthless standard**: Current code is 579 LOC and clean. Only recommend changes that are CLEARLY better.

---

## Statistics

**Before**: 579 LOC
**After**: 567 LOC
**Reduction**: 2.1%

**Suggestions**: 20+
**Accepted**: 8 (40%)
**Rejected**: 12 (60%)

**Bug fixes**: 3
**Quality improvements**: 5
**Dead code removed**: 1

---

## Files

- **agent-1-core.md**: Core logic (parse, events, queries) - 5 suggestions
- **agent-2-fs.md**: File System + IndexedDB - 4 suggestions
- **agent-3-ui.md**: Replicant UI components - 4 suggestions
- **agent-4-cross-cutting.md**: Patterns across all files - 6 suggestions

Total: 19 distinct suggestions evaluated

---

## Conclusion

The current codebase is already clean. Most suggestions are either:
1. Over-abstraction (wrong level)
2. Micro-optimizations (not worth it)
3. Incorrect conventions (bang suffix on I/O)

The 8 accepted suggestions are **clear wins**:
- 3 fix actual bugs
- 5 improve code quality with minimal trade-offs

The biggest wins are:
1. IDB helper extraction (-12 LOC, eliminates duplication)
2. Render duplication fix (correctness)
3. IndexedDB race condition fix (correctness)

**Next step**: Implement the 8 accepted suggestions (see ACTION_ITEMS.md)
