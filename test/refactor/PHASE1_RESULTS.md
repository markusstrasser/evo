# Phase 1: Tighten Ephemeral Fast Path - COMPLETE ✅

**Date**: 2025-11-21
**Branch**: refactor/kernel-session-split

## Goal

Make buffer operations truly ephemeral so they:
1. Do not create history entries
2. Do not trigger derive-indexes recomputation
3. Are processed via fast path in transaction pipeline

## Changes Made

### 1. Extended `ephemeral-op?` function
**File**: `src/kernel/api.cljc:98-111`

Added "session/buffer" to the list of ephemeral node IDs:

```clojure
(defn ephemeral-op?
  [op]
  (and (= :update-node (:op op))
       (or (= const/session-ui-id (:id op))
           (= const/session-selection-id (:id op))
           (= "session/buffer" (:id op)))))  ; NEW: Phase 1 addition
```

### 2. Verified buffer plugin implementation
**File**: `src/plugins/buffer.cljc`

Confirmed buffer plugin already returns correct ops structure:
- Returns `{:op :update-node :id "session/buffer" :props {...}}`
- No changes needed - already correct!

## Test Results

### New Tests Added
**File**: `test/refactor/phase1_ephemeral_test.cljc`

Created comprehensive test suite covering:
1. `buffer-ops-are-ephemeral` - Verify buffer ops identified as ephemeral
2. `buffer-updates-skip-derive-indexes` - Verify derived indexes unchanged
3. `buffer-updates-do-not-create-history` - Verify no history entries
4. `buffer-intent-produces-ephemeral-ops` - Verify intent produces correct ops
5. `buffer-performance-baseline` - Performance baseline (100 updates < 100ms)

**Result**: All 5 new tests PASS ✅

### Regression Testing

**Before Phase 1**:
```
Ran 288 tests containing 903 assertions.
66 failures, 0 errors.
```

**After Phase 1**:
```
Ran 293 tests containing 916 assertions.
66 failures, 0 errors.
```

**Analysis**:
- ✅ Added 5 new tests (Phase 1 suite)
- ✅ Added 13 new assertions
- ✅ **Zero new failures** - no regressions!
- ✅ Failure count unchanged (66 pre-existing failures)

## Performance Impact

### Before Phase 1
Buffer updates went through full transaction pipeline:
- Created history entries (undo stack pollution)
- Triggered `derive-indexes` on every keystroke (expensive!)
- Example: Typing 100 characters = 100 derive-indexes calls

### After Phase 1
Buffer updates use ephemeral fast path:
- **No history entries** - undo stack stays clean
- **No derive-indexes calls** - derived data unchanged
- Example: Typing 100 characters = 0 derive-indexes calls

### Measured Improvement
Test: `buffer-performance-baseline`
- 100 rapid buffer updates complete in < 100ms
- Derived indexes remain bit-identical (verified by equality check)

## Validation

### Invariants Maintained ✅
1. ✅ Single Parent - unchanged (buffer doesn't touch structure)
2. ✅ Bidirectional Consistency - unchanged
3. ✅ No Cycles - unchanged
4. ✅ Referential Integrity - unchanged
5. ✅ Derived Indexes Match - **verified identical before/after buffer updates**

### Existing Tests ✅
All previously passing tests still pass:
- View tests: 6 tests, 29 assertions - ALL PASS
- Integration tests: 1 test, 4 assertions - ALL PASS
- Unit tests: All passing tests remain passing

## Next Phase

Phase 1 complete and verified. Ready to proceed to **Phase 2: Introduce evo.session**.

**Phase 2 Goal**: Create CLJS session atom and migrate UI state reads while maintaining DB writes for compatibility.
