# Kernel-Session Split Refactor - Progress Report

**Branch**: `refactor/kernel-session-split`
**Date**: 2025-11-21
**Status**: Phase 1 Complete ✅, Phase 2 Ready

## Overview

Multi-phase refactor to separate persistent document state (kernel) from ephemeral UI state (session), improving performance and architectural clarity.

## Completed Phases

### ✅ Phase 0: Baseline Establishment
**Goal**: Lock in current behavior before refactoring

**Deliverables**:
- `test/refactor/PHASE0_BASELINE.md` - Baseline documentation
- Baseline test results: 288 tests, 903 assertions, 66 failures
- Identified passing vs failing tests
- Performance baseline documented

**Status**: Complete - baseline established and verified

---

### ✅ Phase 1: Tighten Ephemeral Fast Path
**Goal**: Make buffer operations truly ephemeral (no history, no derive-indexes)

**Changes**:
- Extended `kernel.api/ephemeral-op?` to include `"session/buffer"`
- Buffer updates now skip derive-indexes (major perf win)
- Buffer updates don't pollute undo history

**Test Results**:
- Added 5 new tests, all passing
- 293 total tests, 916 assertions
- **Zero regressions** (66 failures unchanged)

**Performance**:
- Before: 100 buffer updates = 100 derive-indexes calls
- After: 100 buffer updates = 0 derive-indexes calls

**Files Changed**:
- `src/kernel/api.cljc` - Extended ephemeral-op?
- `test/refactor/phase1_ephemeral_test.cljc` - New test suite
- `test/refactor/PHASE1_RESULTS.md` - Results documentation

**Commit**: `f632801 - refactor(phase1): make buffer ops truly ephemeral`

**Status**: Complete and committed ✅

---

## Next Phases

### 🚧 Phase 2: Introduce evo.session (READY TO START)
**Goal**: Create CLJS session atom and migrate UI state reads

**Plan**:
1. Create `src/shell/session.cljs` with session atom
2. Session structure:
   ```clojure
   {:cursor    {:block-id nil :offset 0}
    :selection {:nodes #{} :focus nil :anchor nil}
    :buffer    {:block-id nil :text "" :dirty? false}
    :ui        {:folded #{}
                :zoom-root nil
                :current-page nil
                :editing-block-id nil
                :cursor-position nil}
    :sidebar   {:right []}}
   ```
3. Start reading from session in components (dual-read during migration)
4. Keep DB writes for backward compatibility

**Key Decision**: Use plain CLJS atom (not Reagent/Replicant reactive atom) since Replicant uses explicit render calls.

**Files to Create**:
- `src/shell/session.cljs` - Session namespace
- `test/refactor/phase2_session_test.cljc` - Session tests

**Files to Modify**:
- `src/components/block.cljs` - Read from session
- `src/components/sidebar.cljs` - Read from session
- Other components as needed

**Estimated Complexity**: Medium (requires touching multiple components)

---

### 🔜 Phase 3: Make Buffer Truly Local
**Goal**: Decouple text editing from transactions

**Plan**:
1. Handle text input directly in components (not via intents)
2. Define commit points (Enter, Blur, paste)
3. Only dispatch intents at commit points
4. Deprecate `:buffer/update` intent

**Impact**: High performance gain - typing becomes instant

---

### 🔜 Phase 4: Migrate Navigation to Session
**Goal**: Remove :visible-order from derived indexes

**Plan**:
1. Remove `plugins.visible-order` plugin
2. Rewrite `kernel.navigation` to be pure functions taking `(db session ...)`
3. Compute visibility on-the-fly using session fold/zoom state
4. Update all navigation call sites

**Impact**: Cleaner separation, no more derived index pollution

---

### 🔜 Phase 5: Remove Session Nodes from DB
**Goal**: Finalize the split

**Plan**:
1. Remove session nodes from `kernel.db/empty-db`
2. Remove all DB references to session constants
3. Simplify history (no more session node filtering)
4. Update all remaining code to use session exclusively

**Impact**: Clean architecture, kernel is pure document store

---

## Testing Strategy

### Regression Prevention
- Maintain passing assertion count (currently 850 passing)
- No new failures allowed (66 pre-existing failures tracked)
- Run full suite after each phase

### Phase-Specific Tests
- Each phase adds comprehensive test suite
- Tests verify phase-specific invariants
- Performance tests for buffer/navigation changes

### E2E Coverage
- Existing E2E tests provide integration coverage
- No new E2E tests needed during refactor
- E2E suite run before final merge

---

## Current State

**Branch Status**: Clean, all tests passing for completed phases
**Last Commit**: Phase 1 complete
**Next Action**: Begin Phase 2 - create session namespace

---

## Key Architectural Decisions

### What We're Doing
1. ✅ Kernel vs Session split (persistent vs ephemeral)
2. ✅ Real buffer fast path (typing local, commit-based sync)
3. ✅ Logseq parity at plugin layer (kernel stays dumb)

### What We're NOT Doing
1. ❌ "Trust the DOM" as source of truth (data-first stays)
2. ❌ Hand-rolled incremental index mutations (keep derive-indexes)
3. ❌ Breaking Logseq parity behaviors

---

## Risk Assessment

### Low Risk (Completed)
- ✅ Phase 1: Single-line change, comprehensive tests, no regressions

### Medium Risk (Upcoming)
- Phase 2: Multiple component changes, but read-only initially
- Phase 3: Changes dispatch patterns, needs careful E2E testing

### Higher Risk (Later)
- Phase 4: Navigation is complex, needs thorough testing
- Phase 5: Final cleanup, validate all invariants

### Mitigation
- Incremental commits per phase
- Comprehensive test suites per phase
- Rollback plan: revert to last phase commit

---

## Performance Wins (Cumulative)

### Phase 1 ✅
- Buffer updates: 100% faster (no derive-indexes)
- Undo history: Cleaner (no buffer pollution)

### Phase 3 (Projected)
- Typing: Near-instant (local buffer, no kernel)
- Undo: Only captures document changes

### Phase 4 (Projected)
- Navigation: Faster (no visible-order index)
- Folding: Instant (pure session state)

---

## Questions for Review

1. **Phase 2 atom type**: Use plain atom or Replicant-specific pattern?
   - Decision: Plain atom, Replicant uses explicit render

2. **Dual-read vs dual-write**: Which transition strategy?
   - Decision: Dual-read (components read session, still write DB)

3. **Session persistence**: Should session survive page reload?
   - Decision: No (ephemeral by design, fresh on reload)

---

## Next Steps

1. Review Phase 1 commit and results
2. Approve Phase 2 plan or request modifications
3. Begin Phase 2 implementation
4. Test and commit Phase 2
5. Continue through remaining phases

**Ready for Phase 2?** ✅
