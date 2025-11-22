# Phase 0: Baseline Behavior Documentation

This document establishes the baseline behavior before the kernel-session split refactor.

## Goal

Lock in current Logseq-parity behaviors so refactors can be verified against them.

## Key Functional Requirements Covered

### FR-Edit-01: Enter creates new sibling
- **Test**: `test/plugins/smart_editing_test.cljc::split-with-list-increment-test`
- **Behavior**: Enter on block creates new sibling, handles empty list items
- **Coverage**: :smart-split intent

### FR-Edit-07: Backspace merge
- **Test**: `test/plugins/smart_editing_test.cljc::merge-with-next-test`
- **Behavior**: Backspace merge combines text and reparents children
- **Coverage**: :merge-with-prev, :merge-with-next intents

### FR-Move-02: Climb/Descend
- **Test**: `test/plugins/struct_test.cljc::climb-out-test`, `::descend-into-test`
- **Behavior**: Mod+Shift+Up/Down boundary-aware movement
- **Coverage**: :move-selected-up, :move-selected-down intents

### FR-Nav: Vertical cursor memory
- **Test**: `test/plugins/navigation_test.cljc::navigate-down-with-cursor-memory-test`
- **Behavior**: Navigation preserves cursor column position
- **Coverage**: :navigate-with-cursor-memory intent

## Baseline Test Run

Run these tests to establish baseline before each phase:

```bash
# Smart editing (Enter, merge, list formatting)
bb test :only plugins.smart-editing-test

# Structural editing (indent, outdent, move, climb/descend)
bb test :only plugins.struct-test

# Navigation (cursor memory, visible order)
bb test :only plugins.navigation-test

# Selection (selection state, multi-select)
bb test :only plugins.selection-test

# Folding (fold/unfold state)
bb test :only plugins.folding-test
```

## Known Test Status (Pre-Refactor)

### Currently Passing
- `plugins.smart-editing-test` - All tests pass
- `plugins.struct-test` - Core tests pass (indent/outdent/delete)
- `plugins.folding-test` - All tests pass

### Currently Failing (Pre-existing)
- `plugins.selection-dom-order-test` - Multiple failures (unrelated to refactor)
- `kernel.navigation-test` - Folded navigation tests fail
- `integration.keybinding-test` - Arrow navigation failures

**Important**: The refactor should NOT introduce NEW test failures. We only care about maintaining the passing test count, not fixing pre-existing failures.

## Performance Baseline

### Current State (Before Phase 1)
- Buffer updates (:buffer/update intent) currently go through full transaction pipeline
- Each buffer update triggers derive-indexes (expensive)
- This is a known performance issue to be fixed in Phase 1

### Measurement
Run this in REPL to measure derive-indexes calls:

```clojure
(require '[kernel.db :as db])
(require '[kernel.transaction :as tx])
(require '[kernel.intent :as intent])

(def db0 (db/empty-db))

;; Add instrumentation to count derive-indexes calls
(def !derive-count (atom 0))
(def original-derive db/derive-indexes)
(alter-var-root #'db/derive-indexes
  (fn [_] (fn [db] (swap! !derive-count inc) (original-derive db))))

;; Simulate 100 buffer updates
(reduce (fn [d i]
          (:db (tx/interpret d [{:op :update-node
                                  :id "session/buffer"
                                  :props {"a" (str "text-" i)}}])))
        db0
        (range 100))

@!derive-count  ;; Should be 100 (BAD - Phase 1 should reduce this to 0)
```

## Invariants to Maintain

These invariants must hold after EVERY phase:

1. **Single Parent**: Every node has exactly one parent in :derived/:parent-of
2. **Bidirectional Consistency**: If A is parent of B, then B appears in :children-by-parent[A]
3. **No Cycles**: Document tree has no cycles
4. **Referential Integrity**: All IDs in :children-by-parent exist in :nodes
5. **Derived Indexes Match**: :prev-id-of, :next-id-of, :index-of all consistent with :children-by-parent

Test these with:

```bash
bb test :only core-schema-test
```

## Baseline Test Results (Pre-Refactor)

**Date**: 2025-11-21
**Branch**: refactor/kernel-session-split
**Commit**: (initial)

```
Ran 288 tests containing 903 assertions.
66 failures, 0 errors.
```

### Test Categories Passing
- View tests: 6 tests, 29 assertions - **ALL PASS**
- Integration tests: 1 test, 4 assertions - **ALL PASS**

### Known Failures (Pre-existing, NOT introduced by refactor)
- `plugins.selection-dom-order-test`: Multiple failures
- `plugins.navigation-test`: Folded navigation failures
- `integration.keybinding-test`: Arrow navigation failures
- `plugins.visible-order-test`: Visible order failures
- `shell.nexus-test`: Intent dispatch failures
- `refactor.logseq-parity-baseline-test`: New test file with expected failures (intent type mismatches)

**Critical**: The refactor should maintain the **222 passing assertions** count. Any reduction indicates a regression.

## Success Criteria for Phase 0

- [x] Document baseline tests covering key FRs
- [x] Identify currently passing vs failing tests
- [x] Establish performance baseline (derive-indexes count)
- [x] Document invariants to maintain
- [x] Run baseline tests and record results (288 tests, 222 passing assertions)

## Next Phase

Baseline established. Proceed to **Phase 1: Tighten Ephemeral Fast Path**.

**Goal**: Make buffer ops truly ephemeral (no history, no derive-indexes).
