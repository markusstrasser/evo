# Skipped E2E Tests Inventory

This document tracks all skipped tests in the E2E test suite, categorized by reason and priority.

## Categories

### 1. Out-of-Scope or Explicit Product Gaps
These are valid skips because the current project direction is a solid outliner,
not open-ended Logseq parity. Re-enable only when the feature becomes a real
product requirement, not because the parity fixture exists.

| Test | File | Feature | Priority |
|------|------|---------|----------|
| arrow navigation stops at current page boundaries | navigation-selection-parity.spec.js:82 | Page scope isolation (§4.1) | HIGH |
| vertical navigation respects zoom boundaries | navigation-selection-parity.spec.js:161 | User-facing zoom is not registered | MEDIUM |
| Cmd+Shift+A selects all blocks in view | editing-parity.spec.js:348 | Select-all-blocks is not a current product surface | LOW |

### 2. Flaky Tests (Need Investigation)
Tests that fail intermittently due to timing, browser behavior, or test infrastructure issues.

| Test | File | Issue | Fix Strategy |
|------|------|-------|--------------|
| handles text selection correctly | text-selection.spec.js:52 | Characters dropped during rapid typing | Use slower typing with delay: 50 or dispatchIntent |
| cursor positioning works with Enter key | text-selection.spec.js:84 | Enter creates new block, cursor lands in different element | Query new block's contenteditable after Enter |
| arrow keys maintain cursor position | text-selection.spec.js:111 | First keypress enters edit mode, subsequent lost | Use enterEditModeAndClick helper |
| handles paste with position tracking | text-selection.spec.js:223 | Timeout waiting for contenteditable focus | Use waitForEditing helper from index.js |
| Enter on selected block with multi-line text | enter-escape-parity.spec.js:116 | Multi-line cursor inconsistent | Investigate cursor memory with BR elements |

### 2a. Removed Tests (Implementation Details or Duplicates)
These tests were removed because they tested implementation details or were duplicate coverage:

| Test | File | Reason |
|------|------|--------|
| REGRESSION: mock-text element positioned correctly | editing.spec.js:101 | Tests internal mock-text implementation, not user behavior |
| text extraction handles complex DOM | text-selection.spec.js:150 | Tests internal element->text utility, covered by unit tests |
| cursor positioning after navigation | text-selection.spec.js:285 | Duplicate - covered by navigation.spec.js |
| maintains cursor during block operations | text-selection.spec.js:309 | Duplicate - covered by editing-parity.spec.js |

### 3. Feature Parity Tests (Divergent)
Tests for Logseq behaviors that are intentionally different or not worth
carrying as backlog.

| Test | File | Reason | Action |
|------|------|--------|--------|
| Word Navigation | editing-parity.spec.js:162 | Uses Ctrl+Shift+F/B (Emacs-style, not in Logseq spec) | Keep skipped |
| Kill Commands | editing-parity.spec.js:216 | Conflicts with Cmd+K (quick-switcher) | Keep skipped unless kill commands become a real requirement |
| Text Formatting - Highlight & Strikethrough | editing-parity.spec.js:478 | Relies on word selection | Keep skipped unless word selection becomes a real requirement |
| UI Feel - No Regressions | editing-parity.spec.js:528 | Uses word nav and kill commands | Keep skipped |
| Undo/Redo | editing-parity.spec.js:375 | History system tracks DB ops, not text edits | Document as expected behavior |

### 4. Shift+Click Tests (Known Limitation)
| Test | File | Issue |
|------|------|-------|
| §4.3: Shift+Click Range Selection | navigation-selection-parity.spec.js:288 | Relies on specific demo data layout |

## Recommendations

### Immediate (This Sprint)
1. **Remove mock-text test** (editing.spec.js:101) - Tests implementation detail
2. **Remove DOM extraction test** (text-selection.spec.js:150) - Covered by unit tests
3. **Add TODO comments** to valid skips with issue references

### Short-term
1. **Fix typing flakiness** - Add helper that types slowly with verification
2. **Fix cursor tracking** - Update tests to follow cursor to new elements
3. **Reassess page scope isolation** (§4.1) only if it reproduces as a current editor bug

### Long-term
1. Delete parity skips that no longer match the product scope
2. Convert real current bugs into focused E2E specs

## Test Count Summary

- **Total skipped tests**: ~14 (reduced from ~18)
- **Valid skips (out-of-scope/product gaps)**: 3
- **Flaky tests (need fixes)**: 5 (reduced from 9)
- **Intentionally different behavior**: 5
- **Known limitations**: 1
- **Removed tests**: 4 (implementation details/duplicates)
